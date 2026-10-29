package se.hammarbynext.tv;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skärmsläckarens scen: flaggan studsar långsamt runt en svart skärm, som DVD-loggan, så inget bränns in.
 * Texten uppdateras varje hel minut och spelschemat hämtas var 30:e minut, bara medan scenen syns.
 * Delas av NextMatchDream (skärmsläckaren) och NextMatchActivity (rutan och kortet).
 */
final class NextMatchScene {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    /** En match räknas som pågående så här länge efter avspark, om sparad data inte vet bättre. */
    private static final long MATCH_MS = 2 * 60 * 60_000L;
    /** Fart i andel av skärmbredden per sekund. */
    private static final float SPEED = 0.02f;

    private static final String[] WEEKDAYS = {"måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag"};
    private static final String[] MONTHS = {"januari", "februari", "mars", "april", "maj", "juni", "juli",
            "augusti", "september", "oktober", "november", "december"};

    private final Context ctx;
    private final FrameLayout root;
    private final FlagCardView card;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private ExecutorService network;
    private boolean running;
    private boolean fetching;
    private boolean fetchFailed;
    private List<MatchSource.Match> matches;

    private float x = -1, y, vx, vy;
    private long lastFrameNs;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nowNs) {
            if (!running) return;
            move(nowNs);
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
        card = new FlagCardView(ctx);
        card.setAlpha(0f);
        root.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return root;
    }

    void start() {
        if (running) return;
        running = true;
        network = Executors.newSingleThreadExecutor();
        matches = MatchSource.load(ctx);
        handler.post(minute);

        long age = System.currentTimeMillis() - MatchSource.fetchedAt(ctx);
        handler.postDelayed(refresh, age >= REFRESH_MS / 2 ? 0 : REFRESH_MS / 2 - age);

        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frame);
        card.animate().alpha(1f).setDuration(1200).start();
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(frame);
        card.animate().cancel();
        if (network != null) {
            network.shutdownNow();
            network = null;
        }
        fetching = false;
    }

    // --- Rörelsen -------------------------------------------------------------------------------

    private void move(long nowNs) {
        int maxX = root.getWidth() - card.getWidth();
        int maxY = root.getHeight() - card.getHeight();
        if (card.getWidth() == 0 || maxX < 0 || maxY < 0) return;

        if (x < 0) {   // första bildrutan: slumpad plats och riktning
            x = random.nextFloat() * maxX;
            y = random.nextFloat() * maxY;
            float speed = root.getWidth() * SPEED;
            vx = random.nextBoolean() ? speed : -speed;
            vy = (random.nextBoolean() ? speed : -speed) * 0.75f;
        }
        float dt = lastFrameNs == 0 ? 0 : Math.min((nowNs - lastFrameNs) / 1e9f, 0.1f);
        lastFrameNs = nowNs;

        x += vx * dt;
        y += vy * dt;
        if (x < 0) { x = 0; vx = Math.abs(vx); }
        if (x > maxX) { x = maxX; vx = -Math.abs(vx); }
        if (y < 0) { y = 0; vy = Math.abs(vy); }
        if (y > maxY) { y = maxY; vy = -Math.abs(vy); }
        card.setTranslationX(x);
        card.setTranslationY(y);
    }

    // --- Spelschemat ----------------------------------------------------------------------------

    private void fetch() {
        if (!running || fetching || network == null) return;
        fetching = true;
        ExecutorService exec = network;
        exec.execute(() -> {
            List<MatchSource.Match> result = null;
            try {
                result = MatchSource.fetch();
                MatchSource.save(ctx, result, System.currentTimeMillis());
            } catch (Exception e) {
                // Inget nät, eller ESPN svarade konstigt: behåll det sparade och försök igen snart.
            }
            List<MatchSource.Match> fetched = result;
            handler.post(() -> {
                if (!running || exec != network) return;
                fetching = false;
                fetchFailed = fetched == null;
                if (fetched != null) matches = fetched;
                updateText();
                handler.removeCallbacks(refresh);
                handler.postDelayed(refresh, fetched == null ? RETRY_MS : REFRESH_MS);
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

        MatchSource.Match next = nextMatch(nowMs);
        if (next == null) {
            String msg, sub = "";
            if (matches == null) {
                msg = fetchFailed ? "Kunde inte hämta spelschemat" : "Hämtar spelschemat…";
                if (fetchFailed) sub = "Försöker igen om en stund";
            } else {
                msg = "Inga kommande matcher";
                sub = "Säsongen kan vara slut";
            }
            card.setText(clock, date, msg, sub, "", "");
            return;
        }

        ZonedDateTime kick = Instant.ofEpochMilli(next.kickoffMs).atZone(ZONE);
        String info = next.league;
        if (!next.venue.isEmpty()) info = info.isEmpty() ? next.venue : info + " · " + next.venue;
        card.setText(clock, date, next.home + " – " + next.away, kickoffText(now.toLocalDate(), kick), info,
                countdown(next, nowMs));

        // Har matchen precis tagit slut? Hämta om, så nästa match dyker upp.
        if (nowMs > next.kickoffMs + MATCH_MS - 60_000L && !fetching) handler.post(refresh);
    }

    /** Första matchen som inte är färdig: pågående, eller med avspark högst två timmar bak i tiden. */
    private MatchSource.Match nextMatch(long nowMs) {
        if (matches == null) return null;
        for (MatchSource.Match m : matches) {
            if (nowMs < m.kickoffMs + MATCH_MS) return m;
        }
        return null;
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
