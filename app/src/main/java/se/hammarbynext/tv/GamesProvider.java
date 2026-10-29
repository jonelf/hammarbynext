package se.hammarbynext.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nästa match till cleanyourtv:s Home: panelen under klockan visar "Hammarby IF – IK Sirius" och
 * "Avspark om 6 h 18 min", sedan "Direkt nu". Launcherns game_day-kontrakt (docs/modules.md i cleanyourtv):
 * launchern läser bara, och bara medan Home syns. Är det sparade schemat gammalt när den frågar hämtas ett nytt
 * i bakgrunden, och notifyChange får Home att läsa igen. Inget körs alltså när ingen tittar på Home.
 *
 * Providern är exporterad utan behörighet, som kontraktet kräver: den lämnar bara ut det Home ändå visar.
 */
public class GamesProvider extends ContentProvider {

    static final String AUTHORITY = "se.hammarbynext.tv.games";
    static final Uri TURNS = Uri.parse("content://" + AUTHORITY + "/turns");

    private static final String[] COLUMNS = {"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds"};
    private static final int GREEN = 0xFF00A651;
    private static final long STALE_MS = 2 * 60 * 60_000L;
    /** Minsta tid mellan två försök, så ett nät som ligger nere inte ger en hämtning per Home-tryck. */
    private static final long RETRY_MS = 5 * 60_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean();
    private final AtomicLong lastAttempt = new AtomicLong();

    private static final String[] INFO_COLUMNS = {"top_line", "headline", "detail", "colour"};
    private static final int SKY = 0xFF4FC3F7;
    /** Stationen mäter var tionde minut; var tjugonde räcker för panelen. */
    private static final long WEATHER_STALE_MS = 20 * 60_000L;
    private static final long WEATHER_RETRY_MS = 3 * 60_000L;
    private final AtomicLong weatherAttempt = new AtomicLong();

    @Override
    public boolean onCreate() {
        // Home frågar efter matchen när TV:n startat: ett bra tillfälle att se till att bevakningen går.
        WatchService.ensure(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if ("info".equals(uri.getLastPathSegment())) return weather();
        MatrixCursor out = new MatrixCursor(COLUMNS);
        long now = System.currentTimeMillis();
        List<MatchSource.Match> left = MatchSource.upcoming(MatchSource.load(getContext()), now);
        if (!left.isEmpty()) {
            // Bara nästa match: panelen roterar mellan raderna, och fem matcher i rad blir mest brus.
            MatchSource.Match m = left.get(0);
            long end = Math.max(m.kickoffMs + MatchSource.MATCH_MS, m.live ? now + 15 * 60_000L : 0);
            out.addRow(new Object[] {m.home + " – " + m.away, "Avspark", m.kickoffMs, end, GREEN, "espn", 30});
        }
        out.setNotificationUri(getContext().getContentResolver(), TURNS);

        boolean stale = now - MatchSource.fetchedAt(getContext()) > STALE_MS || left.isEmpty();
        if (stale) refresh(now);
        return out;
    }

    private void refresh(long now) {
        long last = lastAttempt.get();
        if (now - last < RETRY_MS || !lastAttempt.compareAndSet(last, now)) return;
        if (!fetching.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                MatchSource.save(getContext(), MatchSource.fetch(), System.currentTimeMillis());
            } catch (Exception e) {
                // Inget nät eller konstigt svar: Home behåller det sparade, nästa försök om RETRY_MS.
            } finally {
                fetching.set(false);
            }
        });
    }

    /**
     * info_panel: vädret från Netatmo (NetatmoSource), bredvid matchen. Hämtas om det sparade är äldre än tjugo
     * minuter; är det äldre än två timmar visas inget, hellre än gammalt väder.
     */
    private Cursor weather() {
        MatrixCursor out = new MatrixCursor(INFO_COLUMNS);
        out.setNotificationUri(getContext().getContentResolver(), NetatmoSource.info());
        if (NetatmoSource.importCredentials(getContext())) weatherAttempt.set(0);   // nya nycklar: hämta direkt
        if (!NetatmoSource.configured(getContext())) return out;

        long now = System.currentTimeMillis();
        long age = now - NetatmoSource.fetchedAt(getContext());
        NetatmoSource.Row row = NetatmoSource.load(getContext());
        if (row != null && age < 2 * 60 * 60_000L) {
            out.addRow(new Object[] {row.topLine, row.headline, row.detail, SKY});
        }
        if (age > WEATHER_STALE_MS || row == null) refreshWeather(now);
        return out;
    }

    private void refreshWeather(long now) {
        long last = weatherAttempt.get();
        if (now - last < WEATHER_RETRY_MS || !weatherAttempt.compareAndSet(last, now)) return;
        io.execute(() -> {
            try {
                NetatmoSource.fetch(getContext());
            } catch (Exception e) {
                // Inget nät, eller Netatmo nekade: panelen behåller det sparade en stund, nytt försök senare.
            }
        });
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
