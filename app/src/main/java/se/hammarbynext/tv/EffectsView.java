package se.hammarbynext.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Lagret över rutorna: blixten när Hammarby och AIK krockar, domarens kort och målfirandet.
 * Ritar bara medan något pågår; annars är det tomt och kostar inget.
 */
final class EffectsView extends View {

    private static final long FLASH_MS = 550, CARD_MS = 2600, GOAL_MS = 4500;

    private abstract static class Effect {
        final long start;

        Effect(long start) {
            this.start = start;
        }

        abstract long duration();

        /** t: 0 till 1 genom effekten. */
        abstract void draw(Canvas c, float t, long now);
    }

    private final List<Effect> effects = new ArrayList<>();
    private final Random rnd = new Random();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    EffectsView(Context ctx) {
        super(ctx);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.MITER);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        big.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        big.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setTextAlign(Paint.Align.CENTER);
        small.setLetterSpacing(0.3f);
    }

    boolean busy() {
        return !effects.isEmpty();
    }

    /** Anropas varje bildruta av scenen: tar bort det som är klart och ritar om medan något pågår. */
    void tick(long now) {
        if (effects.isEmpty()) return;
        Iterator<Effect> it = effects.iterator();
        while (it.hasNext()) {
            Effect e = it.next();
            if (now - e.start > e.duration()) it.remove();
        }
        invalidate();   // också en sista gång när den sista effekten tagits bort, så den försvinner
    }

    void clear() {
        effects.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = android.os.SystemClock.uptimeMillis();
        for (Effect e : effects) {
            float t = Math.min(1f, Math.max(0f, (now - e.start) / (float) e.duration()));
            e.draw(c, t, now);
        }
    }

    // --- Blixten ----------------------------------------------------------------------------------

    /** En liten blixt där Hammarby och AIK krockade. */
    void flash(float x, float y, long now) {
        float size = getHeight() * 0.09f;
        float[] pts = new float[14];
        // En sicksack uppifrån och ner genom krockpunkten, lite snett.
        float lean = (rnd.nextBoolean() ? 1 : -1) * size * 0.25f;
        float[] xs = {0.15f, -0.25f, 0.30f, -0.20f, 0.25f, -0.30f, 0.05f};
        for (int i = 0; i < 7; i++) {
            pts[i * 2] = x + xs[i] * size + lean * (i / 6f - 0.5f);
            pts[i * 2 + 1] = y - size + i * (2 * size / 6);
        }
        effects.add(new Effect(now) {
            @Override long duration() {
                return FLASH_MS;
            }

            @Override void draw(Canvas c, float t, long n) {
                int alpha = (int) (255 * (1 - t));
                fill.setShader(null);
                fill.setColor(Color.argb((int) (alpha * 0.35f), 255, 250, 200));
                c.drawCircle(x, y, size * (0.9f + t * 0.6f), fill);
                path.reset();
                path.moveTo(pts[0], pts[1]);
                for (int i = 1; i < 7; i++) path.lineTo(pts[i * 2], pts[i * 2 + 1]);
                stroke.setStrokeWidth(size * 0.16f);
                stroke.setColor(Color.argb(alpha, 255, 214, 0));
                c.drawPath(path, stroke);
                stroke.setStrokeWidth(size * 0.06f);
                stroke.setColor(Color.argb(alpha, 255, 255, 255));
                c.drawPath(path, stroke);
            }
        });
    }

    // --- Domarens kort ---------------------------------------------------------------------------

    /** Gult eller rött kort som domaren håller upp; följer domarens ruta medan det visas. */
    void card(Pitch.Body referee, boolean red, long now) {
        effects.add(new Effect(now) {
            @Override long duration() {
                return CARD_MS;
            }

            @Override void draw(Canvas c, float t, long n) {
                float ms = t * CARD_MS;
                float pop = ms < 180 ? ms / 180f * 1.2f : ms < 300 ? 1.2f - (ms - 180) / 120f * 0.2f : 1f;
                float fade = t > 0.85f ? (1 - t) / 0.15f : 1f;
                float ch = getHeight() * 0.10f * pop, cw = ch * 0.68f;
                float cx = referee.cx(), cy = referee.y + referee.h * 0.30f;
                c.save();
                c.rotate(-12, cx, cy);
                rect.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
                fill.setColor(Color.argb((int) (90 * fade), 0, 0, 0));
                c.drawRoundRect(rect.left + ch * 0.05f, rect.top + ch * 0.06f, rect.right + ch * 0.05f,
                        rect.bottom + ch * 0.06f, ch * 0.08f, ch * 0.08f, fill);
                fill.setColor(red ? 0xFFE21B23 : 0xFFFFD400);
                fill.setAlpha((int) (255 * fade));
                c.drawRoundRect(rect, ch * 0.08f, ch * 0.08f, fill);
                c.restore();
                small.setTextSize(getHeight() * 0.024f);
                small.setColor(Color.argb((int) (255 * fade), 255, 255, 255));
                small.setShadowLayer(6, 0, 2, Color.argb((int) (200 * fade), 0, 0, 0));
                c.drawText(red ? "UTVISNING" : "GULT KORT", cx, cy + getHeight() * 0.10f, small);
                small.clearShadowLayer();
            }
        });
    }

    // --- Mål --------------------------------------------------------------------------------------

    /** Målfirande: konfetti ur målet i lagets färger, och MÅL! i mitten av skärmen. */
    void goal(Pitch.Body team, float x, float y, long now) {
        boolean ham = team.role == Pitch.Role.HAMMARBY;
        int[] colours = ham ? new int[] {0xFF00A651, 0xFFFFFFFF, 0xFFFFCC00}
                : new int[] {0xFFFFE100, 0xFF111111, 0xFF2D6FD1};
        String name = ham ? "HAMMARBY" : "AIK";
        int inward = x > getWidth() / 2f ? -1 : 1;
        int n = 110;
        float[] vx = new float[n], vy = new float[n], spin = new float[n];
        int[] col = new int[n];
        float h = getHeight();
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(-70 + rnd.nextDouble() * 140);   // en solfjäder in mot planen
            float s = h * (0.6f + rnd.nextFloat() * 0.9f);
            vx[i] = (float) Math.cos(a) * s * inward;
            vy[i] = (float) Math.sin(a) * s - h * 0.5f;
            spin[i] = (rnd.nextFloat() - 0.5f) * 720;
            col[i] = colours[rnd.nextInt(colours.length)];
        }
        effects.add(new Effect(now) {
            @Override long duration() {
                return GOAL_MS;
            }

            @Override void draw(Canvas c, float t, long n2) {
                float sec = t * GOAL_MS / 1000f, g = h * 0.9f;
                float fade = t > 0.8f ? (1 - t) / 0.2f : 1f;
                float piece = h * 0.012f;
                for (int i = 0; i < n; i++) {
                    float px = x + vx[i] * sec, py = y + vy[i] * sec + g * sec * sec / 2;
                    fill.setColor(col[i]);
                    fill.setAlpha((int) (255 * fade));
                    c.save();
                    c.rotate(spin[i] * sec, px, py);
                    c.drawRect(px - piece, py - piece * 0.5f, px + piece, py + piece * 0.5f, fill);
                    c.restore();
                }
                // MÅL! poppar upp, gungar lite och tonar ut.
                float ms = t * GOAL_MS;
                float scale = ms < 250 ? ms / 250f * 1.25f : ms < 450 ? 1.25f - (ms - 250) / 200f * 0.25f
                        : 1f + 0.04f * (float) Math.sin(ms / 140.0);
                float cx = getWidth() / 2f, cy = h * 0.47f;
                big.setTextSize(h * 0.17f * scale);
                big.setShadowLayer(h * 0.02f, 0, h * 0.006f, Color.argb((int) (220 * fade), 0, 0, 0));
                big.setColor(colours[0]);
                big.setAlpha((int) (255 * fade));
                big.setStyle(Paint.Style.FILL);
                c.drawText("MÅL!", cx, cy, big);
                big.clearShadowLayer();
                big.setStyle(Paint.Style.STROKE);
                big.setStrokeWidth(h * 0.006f * scale);
                big.setColor(ham ? 0xFFFFFFFF : 0xFF111111);
                big.setAlpha((int) (255 * fade));
                c.drawText("MÅL!", cx, cy, big);
                big.setStyle(Paint.Style.FILL);
                small.setTextSize(h * 0.04f);
                small.setColor(Color.argb((int) (255 * fade), 255, 255, 255));
                small.setShadowLayer(8, 0, 2, Color.argb((int) (220 * fade), 0, 0, 0));
                c.drawText("⚽  " + name + "  ⚽", cx, cy + h * 0.09f, small);
                small.clearShadowLayer();
            }
        });
    }
}
