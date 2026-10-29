package se.hammarbynext.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Hammarbyflaggan med klockan, datumet och nästa match på en mörk panel ovanpå.
 * Ritar bara om när texten ändras (en gång i minuten); rörelsen sköts av NextMatchScene med translation,
 * så med ett hårdvarulager kostar varje bildruta nästan inget.
 */
final class FlagCardView extends View {

    private static final int GOLD = 0xFFFFCC00;
    private static final int PANEL = 0xD0002A15;   // mörkgrön, ~80 %, så emblemet anas bakom

    private final Paint flagPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint = text(Typeface.create("sans-serif-light", Typeface.NORMAL), Color.WHITE);
    private final Paint datePaint = text(Typeface.create("sans-serif", Typeface.NORMAL), 0xD9FFFFFF);
    private final Paint labelPaint = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), GOLD);
    private final Paint teamsPaint = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
    private final Paint kickoffPaint = text(Typeface.create("sans-serif", Typeface.NORMAL), Color.WHITE);
    private final Paint infoPaint = text(Typeface.create("sans-serif", Typeface.NORMAL), 0xB3FFFFFF);
    private final Paint countdownPaint = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), GOLD);
    private final RectF rect = new RectF();

    private Bitmap flag;
    private String clock = "", date = "", teams = "", kickoff = "", info = "", countdown = "";

    FlagCardView(Context ctx) {
        super(ctx);
        panelPaint.setColor(PANEL);
        linePaint.setColor(GOLD);
        labelPaint.setLetterSpacing(0.25f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private static Paint text(Typeface face, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    /** Sätter all text på en gång; ritar om bara om något ändrats. */
    void setText(String clock, String date, String teams, String kickoff, String info, String countdown) {
        if (clock.equals(this.clock) && date.equals(this.date) && teams.equals(this.teams)
                && kickoff.equals(this.kickoff) && info.equals(this.info) && countdown.equals(this.countdown)) {
            return;
        }
        this.clock = clock;
        this.date = date;
        this.teams = teams;
        this.kickoff = kickoff;
        this.info = info;
        this.countdown = countdown;
        invalidate();
    }

    /** Halva skärmbredden, men aldrig högre än 80 % av skärmen. Flaggan är 4:3. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = MeasureSpec.getSize(widthSpec);
        int maxH = MeasureSpec.getSize(heightSpec);
        int w = Math.min(maxW / 2, maxH * 8 / 10 * 4 / 3);
        setMeasuredDimension(w, w * 3 / 4);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        if (flag != null) flag.recycle();
        flag = loadFlag(w, h);
        flagPaint.setShader(flag == null ? null
                : new BitmapShader(flag, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    /** Avkodar 1440×1080-flaggan nedsamplad och i RGB_565, sedan skalad till exakt storlek: under 1 MB vid 1080p. */
    private Bitmap loadFlag(int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), R.drawable.flag, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap src = BitmapFactory.decodeResource(getResources(), R.drawable.flag, o);
        if (src == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        flagPaint.setShader(null);
        if (flag != null) {
            flag.recycle();
            flag = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float corner = w * 0.025f;

        rect.set(0, 0, w, h);
        if (flag != null) c.drawRoundRect(rect, corner, corner, flagPaint);

        // Textraderna: storlek i andel av flaggans bredd, och luft före raden.
        float maxText = w * 0.78f;
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(clock, clockPaint, w * 0.15f, 0, maxText));
        lines.add(new Line(date, datePaint, w * 0.040f, w * 0.005f, maxText));
        lines.add(new Line(null, linePaint, Math.max(2f, w * 0.003f), w * 0.035f, maxText));   // guldstreck
        lines.add(new Line("NÄSTA MATCH", labelPaint, w * 0.030f, w * 0.035f, maxText));
        lines.add(new Line(teams, teamsPaint, w * 0.060f, w * 0.015f, maxText));
        lines.add(new Line(kickoff, kickoffPaint, w * 0.042f, w * 0.012f, maxText));
        lines.add(new Line(info, infoPaint, w * 0.034f, w * 0.008f, maxText));
        lines.add(new Line(countdown, countdownPaint, w * 0.046f, w * 0.020f, maxText));

        float content = 0;
        for (Line l : lines) content += l.gap + l.height();
        float pad = w * 0.045f;
        float panelH = content + pad * 2;
        float top = (h - panelH) / 2;
        rect.set(w * 0.07f, top, w * 0.93f, top + panelH);
        c.drawRoundRect(rect, corner, corner, panelPaint);

        float y = top + pad;
        for (Line l : lines) {
            y += l.gap;
            if (l.text == null) {
                float half = w * 0.12f;
                c.drawRect(w / 2 - half, y, w / 2 + half, y + l.height(), linePaint);
                y += l.height();
            } else if (!l.text.isEmpty()) {
                Paint.FontMetrics fm = l.paint.getFontMetrics();
                c.drawText(l.text, w / 2, y - fm.ascent, l.paint);
                y += l.height();
            }
        }
    }

    /** En rad (text == null är guldstrecket): sätter textstorleken och krymper den om texten inte ryms. Tomma rader tar ingen plats. */
    private static final class Line {
        final String text;
        final Paint paint;
        final float gap;
        /** Strecket: dess tjocklek. */
        private final float rule;

        Line(String text, Paint paint, float size, float gap, float maxWidth) {
            this.text = text;
            this.paint = paint;
            this.gap = (text != null && text.isEmpty()) ? 0 : gap;
            this.rule = text == null ? size : 0;
            if (text != null && !text.isEmpty()) {
                paint.setTextSize(size);
                float measured = paint.measureText(text);
                if (measured > maxWidth) paint.setTextSize(size * maxWidth / measured);
            }
        }

        float height() {
            if (text == null) return rule;
            if (text.isEmpty()) return 0;
            Paint.FontMetrics fm = paint.getFontMetrics();
            return fm.descent - fm.ascent;
        }
    }
}
