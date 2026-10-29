package se.hammarbynext.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Arrays;

/**
 * En av skärmsläckarens tre rutor: en flagga (eller domarens randiga tröja) med text på en mörk panel.
 * Ritar bara om när texten ändras; rörelse och lutning sköts med translation och rotation på ett hårdvarulager,
 * så en bildruta kostar nästan inget.
 */
final class CardView extends View {

    /** Radtyper: storlek i andel av rutans bredd och luft före raden. */
    static final int LABEL = 0, TITLE = 1, BODY = 2, INFO = 3, ACCENT = 4, CLOCK = 5, DATE = 6;
    private static final float[] SIZE = {0.045f, 0.085f, 0.062f, 0.050f, 0.070f, 0.27f, 0.070f};
    private static final float[] GAP = {0f, 0.025f, 0.020f, 0.012f, 0.030f, 0f, 0.010f};

    /** Rutans bredd i andel av skärmbredden (4:3). Ungefär hälften så bred som den gamla flaggan. */
    static final float WIDTH = 0.24f;

    private final int flagRes;   // 0: domarens randiga tröja
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] paints = new Paint[SIZE.length];
    private final RectF rect = new RectF();
    private Bitmap bg;
    private String[] texts = new String[0];
    private int[] roles = new int[0];

    CardView(Context ctx, int flagRes, int panelColor, int accent) {
        super(ctx);
        this.flagRes = flagRes;
        panelPaint.setColor(panelColor);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0x59FFFFFF);
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        paints[LABEL] = text(medium, accent);
        paints[LABEL].setLetterSpacing(0.25f);
        paints[TITLE] = text(medium, Color.WHITE);
        paints[BODY] = text(regular, Color.WHITE);
        paints[INFO] = text(regular, 0xB3FFFFFF);
        paints[ACCENT] = text(medium, accent);
        paints[CLOCK] = text(light, Color.WHITE);
        paints[DATE] = text(regular, 0xD9FFFFFF);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private static Paint text(Typeface face, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    /** Raderna, uppifrån; tomma rader tar ingen plats. Ritar om bara om något ändrats. */
    void setContent(String[] texts, int[] roles) {
        if (Arrays.equals(texts, this.texts) && Arrays.equals(roles, this.roles)) return;
        this.texts = texts.clone();
        this.roles = roles.clone();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = MeasureSpec.getSize(widthSpec), maxH = MeasureSpec.getSize(heightSpec);
        int w = (int) Math.min(maxW * WIDTH, maxH * 0.40f * 4 / 3);
        setMeasuredDimension(w, w * 3 / 4);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        if (bg != null) bg.recycle();
        bg = flagRes != 0 ? loadFlag(w, h) : stripes(w, h);
        bgPaint.setShader(bg == null ? null : new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        borderPaint.setStrokeWidth(Math.max(1.5f, w * 0.004f));
    }

    /** Flaggan nedsamplad vid avkodningen och beskuren till rutans form (centerCrop), i RGB_565. */
    private Bitmap loadFlag(int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), flagRes, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap src = BitmapFactory.decodeResource(getResources(), flagRes, o);
        if (src == null) return null;
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    /** Domartröjan förr: lodräta svartvita ränder och en svart krage upptill. */
    private static Bitmap stripes(int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(0xFFF4F4F4);
        Paint black = new Paint();
        black.setColor(0xFF111111);
        float stripe = w / 15f;
        for (int i = 1; i < 15; i += 2) c.drawRect(i * stripe, 0, (i + 1) * stripe, h, black);
        c.drawRect(0, 0, w, h * 0.07f, black);
        return out;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        bgPaint.setShader(null);
        if (bg != null) {
            bg.recycle();
            bg = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), corner = w * 0.035f;
        rect.set(0, 0, w, h);
        if (bg != null) c.drawRoundRect(rect, corner, corner, bgPaint);

        float maxText = w * 0.80f, content = 0;
        float[] heights = new float[texts.length];
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] == null || texts[i].isEmpty()) continue;
            Paint p = paints[roles[i]];
            p.setTextSize(SIZE[roles[i]] * w);
            float measured = p.measureText(texts[i]);
            if (measured > maxText) p.setTextSize(p.getTextSize() * maxText / measured);
            Paint.FontMetrics fm = p.getFontMetrics();
            heights[i] = fm.descent - fm.ascent;
            content += (content == 0 ? 0 : GAP[roles[i]] * w) + heights[i];
        }
        if (content > 0) {
            float pad = w * 0.05f, panelH = content + pad * 2, top = (h - panelH) / 2;
            rect.set(w * 0.06f, top, w * 0.94f, top + panelH);
            c.drawRoundRect(rect, corner, corner, panelPaint);
            float y = top + pad;
            boolean first = true;
            for (int i = 0; i < texts.length; i++) {
                if (heights[i] == 0) continue;
                if (!first) y += GAP[roles[i]] * w;
                first = false;
                Paint p = paints[roles[i]];
                c.drawText(texts[i], w / 2, y - p.getFontMetrics().ascent, p);
                y += heights[i];
            }
        }
        float half = borderPaint.getStrokeWidth() / 2;
        rect.set(half, half, w - half, h - half);
        c.drawRoundRect(rect, corner, corner, borderPaint);
    }
}
