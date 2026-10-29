package se.hammarbynext.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * Notisen ovanpå det som visas, Netflix också: ett eget fönster (TYPE_APPLICATION_OVERLAY) som aldrig tar fokus
 * eller tryck, så fjärrkontrollen styr appen under som vanligt. Kräver "visa ovanpå andra appar", som på TV ges
 * med adb: appops set se.hammarbynext.tv SYSTEM_ALERT_WINDOW allow.
 *
 * VMA: stor röd ruta överst, 60 s, går före allt. Polisen: liten mörk ruta nere till höger, 10 s, en i taget.
 */
final class OverlayNotice {

    private static final long VMA_MS = 60_000L, POLISEN_MS = 10_000L, FADE_MS = 400L;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Alerts.Notice> queue = new ArrayDeque<>();
    private View shown;
    private boolean shownIsVma;

    OverlayNotice(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    static boolean allowed(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** Från vilken tråd som helst. */
    void show(Alerts.Notice n) {
        main.post(() -> {
            if (n.vma) {
                queue.addFirst(n);
                if (shown != null && !shownIsVma) hide(shown);   // VMA går före Polisen
            } else {
                queue.addLast(n);
            }
            if (shown == null) next();
        });
    }

    void clear() {
        main.post(() -> {
            queue.clear();
            main.removeCallbacksAndMessages(null);
            if (shown != null) remove(shown);
        });
    }

    private void next() {
        Alerts.Notice n = queue.pollFirst();
        if (n == null || !allowed(ctx)) {
            queue.clear();
            return;
        }
        View v = build(n);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                n.vma ? (int) (screenWidth() * 0.62f) : (int) (screenWidth() * 0.30f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        int margin = dp(32);
        lp.gravity = n.vma ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.BOTTOM | Gravity.END;
        lp.x = n.vma ? 0 : margin;
        lp.y = margin;
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            return;   // rättigheten togs bort, eller fönstret nekades
        }
        shown = v;
        shownIsVma = n.vma;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(FADE_MS).start();
        main.postDelayed(() -> hide(v), n.vma ? VMA_MS : POLISEN_MS);
    }

    private void hide(View v) {
        if (v != shown) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            remove(v);
            next();
        }).start();
    }

    private void remove(View v) {
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
            // redan borta
        }
        if (v == shown) shown = null;
    }

    private View build(Alerts.Notice n) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(n.vma ? 22 : 16);
        box.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(n.vma ? 0xF2B3161C : 0xE6141A1F);
        bg.setStroke(dp(n.vma ? 3 : 1), n.vma ? 0xFFFFFFFF : 0x40FFFFFF);
        box.setBackground(bg);

        box.addView(text(n.vma ? "⚠️  " + n.title.toUpperCase(java.util.Locale.ROOT) : n.title,
                n.vma ? 26 : 17, Typeface.BOLD, 0xFFFFFFFF, 1));
        if (!n.meta.isEmpty()) box.addView(text(n.meta, n.vma ? 17 : 13, Typeface.NORMAL, 0xCCFFFFFF, 1));
        if (!n.text.isEmpty()) {
            TextView body = text(n.text, n.vma ? 20 : 15, Typeface.NORMAL, 0xFFFFFFFF, n.vma ? 6 : 2);
            ((LinearLayout.LayoutParams) body.getLayoutParams()).topMargin = dp(n.vma ? 10 : 6);
            box.addView(body);
        }
        return box;
    }

    private TextView text(String s, int sp, int style, int color, int lines) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setTextColor(color);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int screenWidth() {
        return ctx.getResources().getDisplayMetrics().widthPixels;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
