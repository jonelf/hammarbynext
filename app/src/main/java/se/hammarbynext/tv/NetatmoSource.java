package se.hammarbynext.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vädret från en egen Netatmo-station (https://dev.netatmo.com/apidocumentation/weather), till panelen bredvid
 * matchen på cleanyourtv:s Home.
 *
 * Nycklarna kommer en gång via setup-netatmo.sh, som lägger netatmo.json i appens externa mapp. Appen läser in
 * filen, sparar nycklarna privat och tar bort filen. Netatmo byter refresh-token vid varje förnyelse, så den nya
 * sparas varje gång.
 */
final class NetatmoSource {

    /** Väderpanelens adress hos GamesProvider. En metod, inte ett fält: Uri finns inte i enhetstesterna. */
    static Uri info() {
        return Uri.parse("content://" + GamesProvider.AUTHORITY + "/info");
    }

    private static final String TOKEN_URL = "https://api.netatmo.com/oauth2/token";
    private static final String DATA_URL = "https://api.netatmo.com/api/getstationsdata?get_favorites=false";
    private static final String PREFS = "netatmo";
    private static final String IMPORT_FILE = "netatmo.json";
    private static final Locale SV = Locale.forLanguageTag("sv-SE");
    /** Höjs när radens text ändras, så en sparad rad i gammalt format hämtas om direkt efter en uppdatering. */
    private static final int ROW_VERSION = 2;

    /** Det Home visar: rubrikrad, stor rad och detaljrad. */
    static final class Row {
        final String topLine, headline, detail;

        Row(String topLine, String headline, String detail) {
            this.topLine = topLine;
            this.headline = headline;
            this.detail = detail;
        }
    }

    private NetatmoSource() {}

    /** Läser in nycklar som setup-netatmo.sh lagt på TV:n, och tar bort filen. Sant om något lästes in. */
    static boolean importCredentials(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, IMPORT_FILE);
        if (f == null || !f.isFile()) return false;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(readAll(in));
            String id = o.optString("client_id"), secret = o.optString("client_secret"),
                    refresh = o.optString("refresh_token");
            if (id.isEmpty() || secret.isEmpty() || refresh.isEmpty()) return false;
            prefs(ctx).edit()
                    .putString("client_id", id).putString("client_secret", secret).putString("refresh_token", refresh)
                    .putString("access_token", o.optString("access_token")).putLong("expires_at", 0L)
                    .remove("row_top").remove("row_headline").remove("row_detail").putLong("fetched", 0L)
                    .commit();
            return true;
        } catch (IOException | JSONException e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static boolean configured(Context ctx) {
        return !prefs(ctx).getString("refresh_token", "").isEmpty();
    }

    static long fetchedAt(Context ctx) {
        return prefs(ctx).getLong("fetched", 0L);
    }

    /** Senast hämtade rad, eller null. */
    static Row load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String headline = p.getString("row_headline", null);
        if (headline == null || p.getInt("row_version", 1) != ROW_VERSION) return null;
        return new Row(p.getString("row_top", ""), headline, p.getString("row_detail", ""));
    }

    /** Hämtar stationens data, förnyar token vid behov, och sparar raden. Körs på en bakgrundstråd. */
    static void fetch(Context ctx) throws IOException, JSONException {
        SharedPreferences p = prefs(ctx);
        String access = p.getString("access_token", "");
        if (access.isEmpty() || System.currentTimeMillis() > p.getLong("expires_at", 0L) - 60_000L) {
            access = refreshToken(ctx);
        }
        Response r = get(DATA_URL, access);
        if (r.code == 401 || r.code == 403) {   // utgången eller ogiltig token: förnya och försök en gång till
            access = refreshToken(ctx);
            r = get(DATA_URL, access);
        }
        if (r.code != 200) throw new IOException("Netatmo HTTP " + r.code);
        Row row = parse(r.body);
        if (row == null) throw new IOException("Netatmo: ingen station i svaret");
        p.edit().putString("row_top", row.topLine).putString("row_headline", row.headline)
                .putString("row_detail", row.detail).putInt("row_version", ROW_VERSION)
                .putLong("fetched", System.currentTimeMillis()).apply();
        ctx.getContentResolver().notifyChange(info(), null);
    }

    private static String refreshToken(Context ctx) throws IOException, JSONException {
        SharedPreferences p = prefs(ctx);
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + enc(p.getString("refresh_token", ""))
                + "&client_id=" + enc(p.getString("client_id", ""))
                + "&client_secret=" + enc(p.getString("client_secret", ""));
        HttpURLConnection conn = open(TOKEN_URL);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(form.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) throw new IOException("Netatmo token HTTP " + conn.getResponseCode());
            JSONObject o;
            try (InputStream in = conn.getInputStream()) {
                o = new JSONObject(readAll(in));
            }
            String access = o.getString("access_token");
            SharedPreferences.Editor e = p.edit()
                    .putString("access_token", access)
                    .putLong("expires_at", System.currentTimeMillis() + o.optLong("expires_in", 10800L) * 1000L);
            String refresh = o.optString("refresh_token");
            if (!refresh.isEmpty()) e.putString("refresh_token", refresh);
            e.commit();   // commit: en förlorad ny refresh-token kan låsa ute appen
            return access;
        } finally {
            conn.disconnect();
        }
    }

    /** Ute: temperatur och trend stort; min/max, fukt ute och inne, inne, regn i dag och vind på detaljraden. */
    static Row parse(String json) throws JSONException {
        JSONArray devices = new JSONObject(json).getJSONObject("body").optJSONArray("devices");
        if (devices == null || devices.length() == 0) return null;
        JSONObject station = devices.getJSONObject(0);
        JSONObject indoor = station.optJSONObject("dashboard_data");
        JSONObject outdoor = null, rain = null, wind = null;
        JSONArray modules = station.optJSONArray("modules");
        for (int i = 0; modules != null && i < modules.length(); i++) {
            JSONObject m = modules.getJSONObject(i);
            JSONObject d = m.optJSONObject("dashboard_data");
            if (d == null || !m.optBoolean("reachable", true)) continue;
            switch (m.optString("type")) {
                case "NAModule1": outdoor = d; break;
                case "NAModule3": rain = d; break;
                case "NAModule2": wind = d; break;
                default: break;
            }
        }

        Integer outHum = outdoor != null && outdoor.has("Humidity") ? outdoor.getInt("Humidity") : null;
        Integer inHum = indoor != null && indoor.has("Humidity") ? indoor.getInt("Humidity") : null;
        List<String> detail = new ArrayList<>();
        String headline;
        if (outdoor != null && outdoor.has("Temperature")) {
            headline = deg(outdoor.getDouble("Temperature")) + trend(outdoor.optString("temp_trend"));
            if (outdoor.has("min_temp") && outdoor.has("max_temp")) {
                detail.add("Min " + deg(outdoor.getDouble("min_temp")) + "  Max " + deg(outdoor.getDouble("max_temp")));
            }
            String hum = humidity(outHum, inHum);
            if (hum != null) detail.add(hum);
        } else if (indoor != null && indoor.has("Temperature")) {
            headline = deg(indoor.getDouble("Temperature")) + " inne";   // ingen utomhusmodul
            String hum = humidity(null, inHum);
            if (hum != null) detail.add(hum);
            indoor = null;
        } else {
            return null;
        }
        if (indoor != null && indoor.has("Temperature")) detail.add("Inne " + deg(indoor.getDouble("Temperature")));
        if (rain != null && rain.optDouble("sum_rain_24", 0) > 0) {
            detail.add(String.format(SV, "Regn %.1f mm", rain.getDouble("sum_rain_24")));
        }
        if (wind != null && wind.has("WindStrength")) {
            detail.add(String.format(SV, "Vind %.0f m/s", wind.getDouble("WindStrength") / 3.6));
        }
        return new Row("Torpet", headline, String.join("  ·  ", detail));
    }

    /** "Fukt ute 97% inne 57%", eller bara den ena om den andra saknas. */
    static String humidity(Integer out, Integer in) {
        if (out == null && in == null) return null;
        StringBuilder s = new StringBuilder("Fukt");
        if (out != null) s.append(" ute ").append(out).append('%');
        if (in != null) s.append(" inne ").append(in).append('%');
        return s.toString();
    }

    private static String deg(double t) {
        return String.format(SV, "%.1f°", t);
    }

    private static String trend(String t) {
        if ("up".equals(t)) return " ↑";
        if ("down".equals(t)) return " ↓";
        return "";
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static Response get(String url, String access) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("Authorization", "Bearer " + access);
        try {
            int code = conn.getResponseCode();
            if (code != 200) return new Response(code, "");
            try (InputStream in = conn.getInputStream()) {
                return new Response(code, readAll(in));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "hammarbynext/1.0");
        return conn;
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
