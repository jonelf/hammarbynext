package se.hammarbynext.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hammarbys kommande matcher från ESPN:s öppna JSON-API, samma källa som api-sports.py.
 * Ingen nyckel behövs, men API:t är inofficiellt och kan ändras utan förvarning.
 * Senaste svaret sparas, så skärmsläckaren visar något direkt även utan nät.
 */
final class MatchSource {

    private static final String SCHEDULE_URL =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/2495/schedule?fixture=true";
    private static final int KEEP = 5;
    private static final String PREFS = "matches";

    static final class Match {
        final long kickoffMs;
        final String home, away, league, venue;
        final boolean live;

        Match(long kickoffMs, String home, String away, String league, String venue, boolean live) {
            this.kickoffMs = kickoffMs;
            this.home = home;
            this.away = away;
            this.league = league;
            this.venue = venue;
            this.live = live;
        }
    }

    private MatchSource() {}

    /** Hämtar spelschemat. Körs på en bakgrundstråd. */
    static List<Match> fetch() throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(SCHEDULE_URL).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "hammarbynext/1.0");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream()) {
                return parse(readAll(in));
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Kommande och pågående matcher (state pre/in), sorterade på avspark. */
    static List<Match> parse(String json) throws JSONException {
        JSONArray events = new JSONObject(json).optJSONArray("events");
        List<Match> out = new ArrayList<>();
        if (events == null) return out;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            JSONObject comp = event.getJSONArray("competitions").getJSONObject(0);
            String state = comp.getJSONObject("status").getJSONObject("type").optString("state");
            if (!state.equals("pre") && !state.equals("in")) continue;

            String home = "?", away = "?";
            JSONArray teams = comp.getJSONArray("competitors");
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t);
                String name = c.getJSONObject("team").optString("displayName", "?");
                if (c.optString("homeAway").equals("home")) home = name; else away = name;
            }
            JSONObject venue = comp.optJSONObject("venue");
            JSONObject league = event.optJSONObject("league");
            // ESPN skriver "2026-10-29T17:30Z", utan sekunder: OffsetDateTime klarar det, Instant.parse inte.
            long kickoff = OffsetDateTime.parse(event.getString("date")).toInstant().toEpochMilli();
            out.add(new Match(kickoff, home, away,
                    leagueName(league == null ? "" : league.optString("name")),
                    venue == null ? "" : venue.optString("fullName"),
                    state.equals("in")));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.kickoffMs, b.kickoffMs));
        return out.size() > KEEP ? new ArrayList<>(out.subList(0, KEEP)) : out;
    }

    /** "Swedish Allsvenskan" blir "Allsvenskan". */
    private static String leagueName(String name) {
        return name.startsWith("Swedish ") ? name.substring("Swedish ".length()) : name;
    }

    static void save(Context ctx, List<Match> matches, long now) {
        JSONArray arr = new JSONArray();
        try {
            for (Match m : matches) {
                arr.put(new JSONObject()
                        .put("k", m.kickoffMs).put("h", m.home).put("a", m.away)
                        .put("l", m.league).put("v", m.venue).put("live", m.live));
            }
        } catch (JSONException e) {
            return;
        }
        prefs(ctx).edit().putString("list", arr.toString()).putLong("fetched", now).apply();
    }

    /** Senast sparade lista, eller null om inget hämtats än. */
    static List<Match> load(Context ctx) {
        String s = prefs(ctx).getString("list", null);
        if (s == null) return null;
        List<Match> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Match(o.getLong("k"), o.optString("h"), o.optString("a"),
                        o.optString("l"), o.optString("v"), o.optBoolean("live")));
            }
        } catch (JSONException e) {
            return null;
        }
        return out;
    }

    static long fetchedAt(Context ctx) {
        return prefs(ctx).getLong("fetched", 0L);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // InputStream.readAllBytes finns först från API 33.
    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
