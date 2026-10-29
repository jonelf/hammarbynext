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
 * Hammarbys och AIK:s kommande matcher från ESPN:s öppna JSON-API, samma källa som api-sports.py.
 * Ingen nyckel behövs, men API:t är inofficiellt och kan ändras utan förvarning.
 * Senaste svaret sparas, så skärmsläckaren visar något direkt även utan nät.
 */
final class MatchSource {

    private static final String SCHEDULE_URL =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/%d/schedule?fixture=true";
    private static final int KEEP = 5;

    /** ESPN:s lag-id, och var lagets senaste svar sparas. Hammarby behåller sitt gamla namn, "matches". */
    enum Team {
        HAMMARBY(2495, "matches"),
        AIK(994, "matches_aik");

        final int espnId;
        final String prefs;

        Team(int espnId, String prefs) {
            this.espnId = espnId;
            this.prefs = prefs;
        }
    }
    /** En match räknas som pågående så här länge efter avspark, om sparad data inte vet bättre. */
    static final long MATCH_MS = 2 * 60 * 60_000L;

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

    static List<Match> fetch() throws IOException, JSONException {
        return fetch(Team.HAMMARBY);
    }

    /** Hämtar lagets spelschema. Körs på en bakgrundstråd. */
    static List<Match> fetch(Team team) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                String.format(java.util.Locale.ROOT, SCHEDULE_URL, team.espnId)).openConnection();
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
        save(ctx, Team.HAMMARBY, matches, now);
    }

    static void save(Context ctx, Team team, List<Match> matches, long now) {
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
        prefs(ctx, team).edit().putString("list", arr.toString()).putLong("fetched", now).apply();
        // Home (cleanyourtv:s game_day-panel) läser Hammarby via GamesProvider: säg till att listan är ny.
        if (team == Team.HAMMARBY) ctx.getContentResolver().notifyChange(GamesProvider.TURNS, null);
    }

    /** Matcher som inte är färdiga: pågående, eller med avspark högst MATCH_MS bak i tiden. */
    static List<Match> upcoming(List<Match> matches, long nowMs) {
        List<Match> out = new ArrayList<>();
        if (matches == null) return out;
        for (Match m : matches) {
            if (nowMs < m.kickoffMs + MATCH_MS) out.add(m);
        }
        return out;
    }

    /** Senast sparade lista, eller null om inget hämtats än. */
    static List<Match> load(Context ctx) {
        return load(ctx, Team.HAMMARBY);
    }

    static List<Match> load(Context ctx, Team team) {
        String s = prefs(ctx, team).getString("list", null);
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
        return fetchedAt(ctx, Team.HAMMARBY);
    }

    static long fetchedAt(Context ctx, Team team) {
        return prefs(ctx, team).getLong("fetched", 0L);
    }

    private static SharedPreferences prefs(Context ctx, Team team) {
        return ctx.getSharedPreferences(team.prefs, Context.MODE_PRIVATE);
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
