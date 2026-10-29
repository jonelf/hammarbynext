package se.hammarbynext.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bevakningen: VMA från Sveriges Radio varannan minut och Polisens händelser i Stockholm var tionde minut,
 * bara medan skärmen är på. Nya händelser visas som en notis ovanpå det som syns (OverlayNotice), en gång var.
 *
 * Appens enda bakgrundstjänst: en förgrundstjänst, så Android inte stänger den. Startar vid uppstart
 * (BootReceiver) och när appen, skärmsläckaren eller Home-panelen används.
 *
 * Prova notisen: adb shell am start-foreground-service -n se.hammarbynext.tv/.WatchService --es test vma
 * (Sveriges Radios testflöde, utan områdesfilter) eller --es test polisen (Polisens senaste händelse).
 */
public class WatchService extends Service {

    private static final long VMA_MS = 2 * 60_000L, POLISEN_MS = 10 * 60_000L;
    /** Polisen publicerar ofta timmar efter händelsen; äldre än så här visas inte efter till exempel en natt. */
    private static final long POLISEN_MAX_AGE_MS = 6 * 60 * 60_000L;
    /** Högst så här många Polisennotiser från samma hämtning. */
    private static final int POLISEN_MAX = 3;
    private static final int SEEN_KEEP = 800;
    private static final String CHANNEL = "watch";

    private HandlerThread thread;
    private Handler worker;
    private OverlayNotice overlay;
    private SharedPreferences prefs;
    private boolean screenOn;

    private final Runnable pollVma = new Runnable() {
        @Override
        public void run() {
            checkVma(false);
            worker.postDelayed(this, VMA_MS);
        }
    };

    private final Runnable pollPolisen = new Runnable() {
        @Override
        public void run() {
            checkPolisen();
            worker.postDelayed(this, POLISEN_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            setScreen(Intent.ACTION_SCREEN_ON.equals(i.getAction()));
        }
    };

    /** Startar bevakningen om den inte redan går. Ofarlig att anropa ofta. */
    static void ensure(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, WatchService.class));
        } catch (RuntimeException e) {
            // Nekad i bakgrunden på nyare Android: uppstart eller nästa gång appen syns startar den.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        prefs = getSharedPreferences("watch", MODE_PRIVATE);
        overlay = new OverlayNotice(this);
        thread = new HandlerThread("watch");
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        setScreen(getSystemService(PowerManager.class).isInteractive());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        String test = intent == null ? null : intent.getStringExtra("test");
        if ("vma".equals(test)) worker.post(() -> checkVma(true));
        if ("polisen".equals(test)) worker.post(this::testPolisen);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screen);
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        overlay.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Bevakning", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_watch)
                .setContentTitle("Bevakar VMA och Polisen")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void setScreen(boolean on) {
        if (worker == null || on == screenOn) return;
        screenOn = on;
        worker.removeCallbacks(pollVma);
        worker.removeCallbacks(pollPolisen);
        if (on) {
            worker.post(pollVma);
            worker.postDelayed(pollPolisen, 5_000L);
        } else {
            overlay.clear();
        }
    }

    // --- VMA --------------------------------------------------------------------------------------

    private void checkVma(boolean test) {
        try {
            List<Alerts.Notice> list = Alerts.vma(Alerts.get(test ? Alerts.VMA_TEST_URL : Alerts.VMA_URL), test);
            if (test) {
                if (list.isEmpty()) {
                    overlay.show(new Alerts.Notice("test", true, "VMA, test",
                            "Sveriges Radios testflöde", "Testflödet har inget meddelande just nu.", 0));
                } else {
                    overlay.show(list.get(0));
                }
                return;
            }
            Set<String> seen = seen("vma");
            for (Alerts.Notice n : list) {
                if (seen.add(n.id)) overlay.show(n);   // en aktiv VMA visas även första gången
            }
            save("vma", seen);
        } catch (Exception e) {
            // inget nät eller oväntat svar: nästa försök om två minuter
        }
    }

    // --- Polisen ----------------------------------------------------------------------------------

    private void checkPolisen() {
        try {
            List<Alerts.Notice> list = Alerts.polisen(Alerts.get(Alerts.POLISEN_URL));
            Set<String> seen = seen("polisen");
            boolean primed = prefs.getBoolean("polisen_primed", false);
            long now = System.currentTimeMillis();
            List<Alerts.Notice> fresh = new ArrayList<>();
            for (Alerts.Notice n : list) {
                if (!seen.add(n.id)) continue;
                boolean recent = n.publishedMs == 0 || now - n.publishedMs < POLISEN_MAX_AGE_MS;
                if (primed && recent && fresh.size() < POLISEN_MAX) fresh.add(n);
            }
            save("polisen", seen);
            prefs.edit().putBoolean("polisen_primed", true).apply();
            // Listan är nyast först: visa i den ordning de hände.
            for (int i = fresh.size() - 1; i >= 0; i--) overlay.show(fresh.get(i));
        } catch (Exception e) {
            // försök igen om tio minuter
        }
    }

    private void testPolisen() {
        try {
            List<Alerts.Notice> list = Alerts.polisen(Alerts.get(Alerts.POLISEN_URL));
            if (!list.isEmpty()) overlay.show(list.get(0));
        } catch (Exception ignored) {
            // inget att visa
        }
    }

    private Set<String> seen(String key) {
        return new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
    }

    /** Sparar de senaste SEEN_KEEP; Polisens id växer, så de högsta behålls. */
    private void save(String key, Set<String> seen) {
        if (seen.size() > SEEN_KEEP) {
            List<String> ids = new ArrayList<>(seen);
            ids.sort((a, b) -> Long.compare(num(b), num(a)));
            seen = new HashSet<>(ids.subList(0, SEEN_KEEP));
        }
        prefs.edit().putStringSet(key, seen).apply();
    }

    private static long num(String id) {
        try {
            return Long.parseLong(id.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
