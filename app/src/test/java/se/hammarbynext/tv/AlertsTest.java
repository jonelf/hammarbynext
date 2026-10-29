package se.hammarbynext.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AlertsTest {

    private static String resource(String name) throws Exception {
        try (InputStream in = AlertsTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Stockholms kommun och länet visas; Smedjebacken, upphävanden och tester inte. */
    @Test
    public void vmaForStockholmOnly() throws Exception {
        List<Alerts.Notice> list = Alerts.vma(resource("vma.json"), false);
        assertEquals(2, list.size());
        assertEquals("SRCAP2", list.get(0).id);
        assertEquals("Stockholms kommun (Stockholms län)", list.get(0).meta);
        assertTrue(list.get(0).text.startsWith("Brand på Södermalm"));
        assertTrue(list.get(0).vma);
        assertEquals("SRCAP3", list.get(1).id);
    }

    /** Testläget visar allt med text, oavsett område och status. */
    @Test
    public void vmaTestModeShowsEverythingWithText() throws Exception {
        List<Alerts.Notice> list = Alerts.vma(resource("vma.json"), true);
        assertEquals(4, list.size());   // allt utom upphävandet, som saknar text
        assertEquals("SRCAP1", list.get(0).id);
    }

    @Test
    public void emptyVmaFeed() throws Exception {
        assertTrue(Alerts.vma("{\"timestamp\":\"x\",\"alerts\":[]}", false).isEmpty());
    }

    /** "29 oktober 00.21, Mordbrand, Stockholm": tiden och platsen ur namnet, typen ur fältet. */
    @Test
    public void polisenEvents() throws Exception {
        List<Alerts.Notice> list = Alerts.polisen(resource("polisen.json"));
        assertEquals(3, list.size());
        Alerts.Notice n = list.get(0);
        assertEquals("polisen-657737", n.id);
        assertEquals("🔥 Mordbrand · Stockholm", n.title);
        assertEquals("29 oktober 00.21", n.meta);
        assertEquals("Larm om brand på Södermalm.", n.text);
        assertFalse(n.vma);
        assertEquals(java.time.OffsetDateTime.parse("2026-10-29T08:04:15+01:00").toInstant().toEpochMilli(),
                n.publishedMs);
        // En typ med kommatecken: "Mord/dråp, försök".
        assertEquals("🚓 Mord/dråp, försök · Stockholm", list.get(2).title);
    }
}
