package se.hammarbynext.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/** Rutan och kortet: samma scen som skärmsläckaren, direkt, tills Back. */
public class NextMatchActivity extends Activity {

    private NextMatchScene scene;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scene = new NextMatchScene(this);
        WatchService.ensure(this);
        setContentView(scene.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
    }

    @Override
    protected void onPause() {
        scene.stop();
        super.onPause();
    }
}
