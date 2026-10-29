package se.hammarbynext.tv;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skärmsläckarens scen: Hammarbys och AIK:s nästa match i varsin ruta med lagets flagga, och domaren med
 * klockan och datumet på randig tröja. Rutorna studsar mot kanterna och mot varandra (Pitch); blixtar, kort och
 * mål ritas i EffectsView. Texten uppdateras varje hel minut och spelschemat hämtas varannan timme, bara medan
 * scenen syns. Delas av NextMatchDream (skärmsläckaren) och NextMatchActivity (rutan och kortet).
 */
final class NextMatchScene implements Pitch.Listener {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    /** Schemat ändras sällan: varannan timme räcker. Vid start hämtas det om det sparade är äldre än en timme. */
    private static final long REFRESH_MS = 2 * 60 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;

    private static final String[] WEEKDAYS = {"måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag"};
    private static final String[] MONTHS = {"januari", "februari", "mars", "april", "maj", "juni", "juli",
            "augusti", "september", "oktober", "november", "december"};

    private static final int[] TEAM_ROLES = {CardView.LABEL, CardView.TITLE, CardView.BODY, CardView.INFO,
            CardView.ACCENT};
    private static final int[] REFEREE_ROLES = {CardView.CLOCK, CardView.DATE};

    private final Context ctx;
    private final FrameLayout root;
    private final CardView hammarby, aik, referee;
    private final EffectsView effects;
    private final Pitch pitch = new Pitch(new Random(), this);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExecutorService network;
    private boolean running;
    private final Map<MatchSource.Team, List<MatchSource.Match>> matches = new EnumMap<>(MatchSource.Team.class);
    private final Map<MatchSource.Team, Boolean> failed = new EnumMap<>(MatchSource.Team.class);
    private boolean fetching;
    private long lastFrameNs;
    private int sizedW, sizedH;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nowNs) {
            if (!running) return;
            step(nowNs);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            updateText();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000L - now % 60_000L + 50);
        }
    };

    private final Runnable refresh = this::fetch;

    NextMatchScene(Context ctx) {
        this.ctx = ctx;
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        hammarby = new CardView(ctx, R.drawable.flag, 0xD0002A15, 0xFFFFCC00);
        aik = new CardView(ctx, R.drawable.aik_flag, 0xD8000000, 0xFFFFE100);
        referee = new CardView(ctx, 0, 0xCC000000, Color.WHITE);
        for (CardView c : new CardView[] {hammarby, aik, referee}) {
            c.setAlpha(0f);
            root.addView(c, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        }
        effects = new EffectsView(ctx);
        root.addView(effects, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    View view() {
        return root;
    }

    void start() {
        if (running) return;
        running = true;
        network = Executors.newSingleThreadExecutor();
        for (MatchSource.Team t : MatchSource.Team.values()) {
            matches.put(t, MatchSource.load(ctx, t));
            failed.put(t, false);
        }
        handler.post(minute);

        long oldest = Math.min(MatchSource.fetchedAt(ctx, MatchSource.Team.HAMMARBY),
                MatchSource.fetchedAt(ctx, MatchSource.Team.AIK));
        long age = System.currentTimeMillis() - oldest;
        handler.postDelayed(refresh, age >= REFRESH_MS / 2 ? 0 : REFRESH_MS / 2 - age);

        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frame);
        for (CardView c : new CardView[] {hammarby, aik, referee}) c.animate().alpha(1f).setDuration(1200).start();
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(frame);
        for (CardView c : new CardView[] {hammarby, aik, referee}) c.animate().cancel();
        effects.clear();
        if (network != null) {
            network.shutdownNow();
            network = null;
        }
        fetching = false;
    }

    // --- Rörelsen -------------------------------------------------------------------------------

    private void step(long nowNs) {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || hammarby.getWidth() == 0) return;
        if (w != sizedW || h != sizedH) {
            pitch.setSize(w, h, hammarby.getWidth(), hammarby.getHeight());
            sizedW = w;
            sizedH = h;
        }
        float dt = lastFrameNs == 0 ? 0 : Math.min((nowNs - lastFrameNs) / 1e9f, 0.1f);
        lastFrameNs = nowNs;
        long now = SystemClock.uptimeMillis();
        pitch.step(dt, now);
        sync(hammarby, pitch.body(Pitch.Role.HAMMARBY));
        sync(aik, pitch.body(Pitch.Role.AIK));
        sync(referee, pitch.body(Pitch.Role.REFEREE));
        effects.tick(now);
    }

    private static void sync(CardView v, Pitch.Body b) {
        v.setTranslationX(b.x);
        v.setTranslationY(b.y);
        v.setRotation(b.angle);
        v.setVisibility(b.visible() ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onClash(float x, float y) {
        effects.flash(x, y, SystemClock.uptimeMillis());
    }

    @Override
    public void onCard(Pitch.Body team, Pitch.Body ref, boolean red) {
        effects.card(ref, red, SystemClock.uptimeMillis());
    }

    @Override
    public void onGoal(Pitch.Body team, float x, float y) {
        effects.goal(team, x, y, SystemClock.uptimeMillis());
    }

    // --- Spelschemat ----------------------------------------------------------------------------

    private void fetch() {
        if (!running || fetching || network == null) return;
        fetching = true;
        ExecutorService exec = network;
        exec.execute(() -> {
            Map<MatchSource.Team, List<MatchSource.Match>> got = new EnumMap<>(MatchSource.Team.class);
            for (MatchSource.Team t : MatchSource.Team.values()) {
                try {
                    List<MatchSource.Match> list = MatchSource.fetch(t);
                    MatchSource.save(ctx, t, list, System.currentTimeMillis());
                    got.put(t, list);
                } catch (Exception e) {
                    // Inget nät, eller ESPN svarade konstigt: behåll det sparade och försök igen snart.
                }
            }
            handler.post(() -> {
                if (!running || exec != network) return;
                fetching = false;
                for (MatchSource.Team t : MatchSource.Team.values()) {
                    failed.put(t, !got.containsKey(t));
                    if (got.containsKey(t)) matches.put(t, got.get(t));
                }
                updateText();
                handler.removeCallbacks(refresh);
                handler.postDelayed(refresh, got.size() < MatchSource.Team.values().length ? RETRY_MS : REFRESH_MS);
            });
        });
    }

    // --- Texten ---------------------------------------------------------------------------------

    private void updateText() {
        long nowMs = System.currentTimeMillis();
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(ZONE);
        String clock = String.format(Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute());
        String date = WEEKDAYS[now.getDayOfWeek().getValue() - 1] + " " + now.getDayOfMonth() + " "
                + MONTHS[now.getMonthValue() - 1];
        referee.setContent(new String[] {clock, date}, REFEREE_ROLES);
        hammarby.setContent(teamLines(MatchSource.Team.HAMMARBY, now, nowMs), TEAM_ROLES);
        aik.setContent(teamLines(MatchSource.Team.AIK, now, nowMs), TEAM_ROLES);

        // Har en match precis tagit slut? Hämta om, så nästa match dyker upp.
        for (MatchSource.Team t : MatchSource.Team.values()) {
            MatchSource.Match next = nextMatch(t, nowMs);
            if (next != null && nowMs > next.kickoffMs + MatchSource.MATCH_MS - 60_000L && !fetching) {
                handler.post(refresh);
                break;
            }
        }
    }

    private String[] teamLines(MatchSource.Team team, ZonedDateTime now, long nowMs) {
        MatchSource.Match next = nextMatch(team, nowMs);
        if (next == null) {
            String msg, sub = "";
            if (matches.get(team) == null) {
                boolean f = Boolean.TRUE.equals(failed.get(team));
                msg = f ? "Kunde inte hämta spelschemat" : "Hämtar spelschemat…";
                if (f) sub = "Försöker igen om en stund";
            } else {
                msg = "Inga kommande matcher";
                sub = "Säsongen kan vara slut";
            }
            return new String[] {"NÄSTA MATCH", msg, sub, "", ""};
        }
        ZonedDateTime kick = Instant.ofEpochMilli(next.kickoffMs).atZone(ZONE);
        String info = next.league;
        if (!next.venue.isEmpty()) info = info.isEmpty() ? next.venue : info + " · " + next.venue;
        return new String[] {"NÄSTA MATCH", next.home + " – " + next.away, kickoffText(now.toLocalDate(), kick),
                info, countdown(next, nowMs)};
    }

    private MatchSource.Match nextMatch(MatchSource.Team team, long nowMs) {
        List<MatchSource.Match> left = MatchSource.upcoming(matches.get(team), nowMs);
        return left.isEmpty() ? null : left.get(0);
    }

    private static String kickoffText(LocalDate today, ZonedDateTime kick) {
        String time = String.format(Locale.ROOT, "kl. %02d:%02d", kick.getHour(), kick.getMinute());
        LocalDate day = kick.toLocalDate();
        if (day.equals(today)) return "I dag " + time;
        if (day.equals(today.plusDays(1))) return "I morgon " + time;
        String wd = WEEKDAYS[kick.getDayOfWeek().getValue() - 1];
        return Character.toUpperCase(wd.charAt(0)) + wd.substring(1) + " " + kick.getDayOfMonth() + " "
                + MONTHS[kick.getMonthValue() - 1] + " " + time;
    }

    private static String countdown(MatchSource.Match m, long nowMs) {
        long left = m.kickoffMs - nowMs;
        if (m.live || left <= 0) return "Pågår nu!";
        long minutes = left / 60_000L;
        if (minutes < 1) return "Avspark nu";
        long days = minutes / (24 * 60), hours = minutes / 60 % 24, mins = minutes % 60;
        if (days > 0) return "Om " + days + " d " + hours + " h " + mins + " min";
        if (hours > 0) return "Om " + hours + " h " + mins + " min";
        return "Om " + mins + " min";
    }
}
