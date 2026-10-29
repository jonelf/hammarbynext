package se.hammarbynext.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class PitchTest {

    private static final float W = 1920, H = 1080, CW = 460, CH = 345;

    private static final class Counts implements Pitch.Listener {
        int clashes, yellow, red, goalsHammarby, goalsAik;

        @Override public void onClash(float x, float y) { clashes++; }

        @Override public void onCard(Pitch.Body team, Pitch.Body referee, boolean isRed) {
            if (isRed) red++; else yellow++;
        }

        @Override public void onGoal(Pitch.Body team, float x, float y) {
            if (team.role == Pitch.Role.HAMMARBY) {
                assertEquals(W, x, 0.01f);
                goalsHammarby++;
            } else {
                assertEquals(Pitch.Role.AIK, team.role);
                assertEquals(0, x, 0.01f);
                goalsAik++;
            }
            assertEquals(H / 2, y, 0.01f);
        }
    }

    /** Tre timmar i 60 bilder per sekund: allt händer, inget fastnar, försvinner eller blir NaN. */
    @Test
    public void threeHoursStayOnThePitch() {
        Counts c = new Counts();
        Pitch p = new Pitch(new Random(42), c);
        p.setSize(W, H, CW, CH);
        float dt = 1 / 60f;
        long now = 0;
        long[] outSince = new long[3];
        int frames = 3 * 60 * 60 * 60;
        for (int f = 0; f < frames; f++) {
            now += 16;
            p.step(dt, now);
            for (int i = 0; i < 3; i++) {
                Pitch.Body b = p.bodies[i];
                assertFalse(Float.isNaN(b.x) || Float.isNaN(b.y) || Float.isNaN(b.vx) || Float.isNaN(b.vy));
                if (b.state == Pitch.State.PLAY && !b.ghost) {
                    assertTrue("x " + b.x, b.x >= -2 && b.x + b.w <= W + 2);
                    assertTrue("y " + b.y, b.y >= -2 && b.y + b.h <= H + 2);
                }
                // Ingen ruta är borta eller på väg in längre än utvisningen plus en marginal.
                boolean away = b.state == Pitch.State.OUT || b.state == Pitch.State.EJECTED || b.ghost;
                if (!away) outSince[i] = now;
                assertTrue(b.role + " borta för länge", now - outSince[i] < Pitch.OUT_MS + 20_000);
            }
        }
        System.out.println("3 h: krockar " + c.clashes + ", gula " + c.yellow + ", röda " + c.red + ", mål Hammarby " + c.goalsHammarby + ", mål AIK " + c.goalsAik);
        assertTrue("krockar " + c.clashes, c.clashes > 20);
        assertTrue("gula " + c.yellow, c.yellow > 5);
        assertTrue("röda " + c.red, c.red > 0);
        assertTrue("Hammarbymål " + c.goalsHammarby, c.goalsHammarby > 0);
        assertTrue("AIK-mål " + c.goalsAik, c.goalsAik > 0);
    }

    /** Hammarby rakt mot högra kortsidans mitt: mål. AIK dit: inget mål. */
    @Test
    public void onlyHammarbyScoresOnTheRight() {
        Counts c = new Counts();
        Pitch p = new Pitch(new Random(1), c);
        p.setSize(W, H, CW, CH);
        Pitch.Body ham = p.body(Pitch.Role.HAMMARBY);
        Pitch.Body aik = p.body(Pitch.Role.AIK);
        Pitch.Body ref = p.body(Pitch.Role.REFEREE);
        ref.x = 0; ref.y = 0; ref.vx = -1; ref.vy = -1;
        aik.x = 0; aik.y = H - CH; aik.vx = -1; aik.vy = 1;
        ham.x = W - CW - 1; ham.y = (H - CH) / 2; ham.vx = p.speed(); ham.vy = 0;
        p.step(0.05f, 1000);
        assertEquals(1, c.goalsHammarby);
        assertEquals(0, c.goalsAik);

        aik.x = W - CW - 1; aik.y = (H - CH) / 2; aik.vx = p.speed(); aik.vy = 0;
        ham.x = 0; ham.y = H - CH; ham.vx = -1; ham.vy = 1;
        p.step(0.05f, 2000);
        assertEquals(0, c.goalsAik);
    }

    /** Ett utvisat lag åker ut och är tillbaka på planen efter 30 sekunder. */
    @Test
    public void sentOffTeamComesBack() {
        Pitch p = new Pitch(new Random(3), new Counts());
        p.setSize(W, H, CW, CH);
        Pitch.Body ham = p.body(Pitch.Role.HAMMARBY);
        ham.state = Pitch.State.EJECTED;
        ham.side = 1;
        ham.vx = p.speed();
        long now = 0;
        while (ham.state == Pitch.State.EJECTED && now < 20_000) {
            now += 16;
            p.step(1 / 60f, now);
        }
        assertEquals(Pitch.State.OUT, ham.state);
        long outAt = now;
        while (ham.state == Pitch.State.OUT) {
            now += 16;
            p.step(1 / 60f, now);
        }
        assertEquals(Pitch.OUT_MS, now - outAt, 32);
        assertEquals(Pitch.State.PLAY, ham.state);
        assertTrue(ham.vx < 0);   // in från höger
    }
}
