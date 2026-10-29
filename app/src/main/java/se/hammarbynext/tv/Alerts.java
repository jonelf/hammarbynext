package se.hammarbynext.tv;

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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Det WatchService bevakar: VMA från Sveriges Radio (originalkällan) och Polisens händelser i Stockholm.
 * Tolkningen är skild från nätet så den kan testas på datorn (AlertsTest).
 */
final class Alerts {

    static final String VMA_URL = "https://vmaapi.sr.se/api/v3/alerts";
    /** Sveriges Radios testflöde: för att prova notisen utan en riktig VMA. */
    static final String VMA_TEST_URL = "https://vmaapi.sr.se/testapi/v2/alerts";
    static final String POLISEN_URL = "https://polisen.se/api/events?locationname=Stockholm";

    /** Stockholms kommun och Stockholms län i VMA:s geokoder; 00 är hela landet. */
    private static final String[] VMA_CODES = {"0180", "01", "00"};

    static final class Notice {
        final String id;
        final boolean vma;
        final String title, meta, text;
        /** Polisen: när händelsen publicerades, i millisekunder; 0 om okänt. */
        final long publishedMs;

        Notice(String id, boolean vma, String title, String meta, String text, long publishedMs) {
            this.id = id;
            this.vma = vma;
            this.title = title;
            this.meta = meta;
            this.text = text;
            this.publishedMs = publishedMs;
        }
    }

    private Alerts() {}

    /**
     * VMA som gäller Stockholm (kommunen, länet eller hela landet), nyaste först. Upphävanden (Cancel) och tester
     * tas bort, utom med test, som visar allt med text oavsett område: testflödet gäller oftast en annan kommun.
     */
    static List<Notice> vma(String json, boolean test) throws JSONException {
        List<Notice> out = new ArrayList<>();
        JSONArray alerts = new JSONObject(json).optJSONArray("alerts");
        if (alerts == null) return out;
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.getJSONObject(i);
            JSONArray infos = a.optJSONArray("info");
            if (infos == null || infos.length() == 0) continue;   // Cancel saknar text
            String type = a.optString("msgType");
            if (!test && (!"Actual".equals(a.optString("status")) || "Cancel".equals(type))) continue;
            JSONObject info = infos.getJSONObject(0);
            JSONArray areas = info.optJSONArray("area");
            if (!test && !concernsStockholm(areas)) continue;
            String area = areas != null && areas.length() > 0
                    ? areas.getJSONObject(0).optString("areaDesc") : "";
            String text = info.optString("description", "");
            if (text.isEmpty() || "null".equals(text)) text = info.optString("headline", "");
            String title = "Update".equals(type) ? "VMA, uppdatering" : "VMA";
            out.add(new Notice(a.optString("identifier"), true, title, area, text.trim(), 0));
        }
        return out;
    }

    private static boolean concernsStockholm(JSONArray areas) throws JSONException {
        if (areas == null || areas.length() == 0) return true;   // inget område angivet: hellre visa
        for (int i = 0; i < areas.length(); i++) {
            JSONObject area = areas.getJSONObject(i);
            String desc = area.optString("areaDesc");
            if (desc.contains("Stockholm") || desc.contains("Sverige") || desc.contains("hela landet")) return true;
            JSONArray codes = area.optJSONArray("geocode");
            for (int j = 0; codes != null && j < codes.length(); j++) {
                String v = codes.getJSONObject(j).optString("value");
                for (String c : VMA_CODES) if (c.equals(v)) return true;
            }
        }
        return false;
    }

    private static final DateTimeFormatter POLISEN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss xxx");

    /** Polisens händelser, nyaste först, som Polisen själva sorterar dem. */
    static List<Notice> polisen(String json) throws JSONException {
        List<Notice> out = new ArrayList<>();
        JSONArray events = new JSONArray(json);
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            // "29 oktober 00.21, Mordbrand, Stockholm": tiden först, platsen sist; typen kan ha kommatecken.
            String name = e.optString("name");
            String type = e.optString("type").trim();
            int firstComma = name.indexOf(", "), lastComma = name.lastIndexOf(", ");
            String when = firstComma > 0 ? name.substring(0, firstComma) : "";
            String place = lastComma > 0 ? name.substring(lastComma + 2) : "";
            long published = 0;
            try {
                published = OffsetDateTime.parse(e.optString("datetime"), POLISEN_TIME).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // okänd tid: visas ändå, men räknas inte som gammal
            }
            String title = icon(type) + " " + type + (place.isEmpty() ? "" : " · " + place);
            out.add(new Notice("polisen-" + e.optLong("id"), false, title, when, e.optString("summary").trim(),
                    published));
        }
        return out;
    }

    private static String icon(String type) {
        String t = type.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("brand") || t.contains("explosion")) return "🔥";
        if (t.contains("trafik") || t.contains("rattfylleri") || t.contains("motorfordon")) return "🚗";
        return "🚓";
    }

    /** GET med tidsgränser; svaret som text. Körs på en bakgrundstråd. */
    static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", "hammarbynext/1.0");
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }
}
