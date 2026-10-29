package se.hammarbynext.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startar bevakningen när TV:n startar. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) WatchService.ensure(ctx);
    }
}
