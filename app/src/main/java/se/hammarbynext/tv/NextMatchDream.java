package se.hammarbynext.tv;

import android.service.dreams.DreamService;

/** Skärmsläckaren: flaggan med nästa match tills en knapp väcker TV:n tillbaka dit den var. */
public class NextMatchDream extends DreamService {

    private NextMatchScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // vilken knapp som helst väcker TV:n
        setFullscreen(true);
        setScreenBright(false);
        scene = new NextMatchScene(this);
        WatchService.ensure(this);
        setContentView(scene.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
