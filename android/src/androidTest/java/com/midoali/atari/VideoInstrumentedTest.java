package com.midoali.atari;

import static org.junit.Assert.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.midoali.atari.core.SegaButtons;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Device-level regression test for the black-screen/audio-only bug.
 * Uses an original public-domain Genesis ROM (debug asset only) that renders a
 * solid backdrop and changes it when Start/A are pressed. Assertions inspect the
 * actual Android compositor screenshot, not merely the emulator framebuffer.
 */
@RunWith(AndroidJUnit4.class)
public class VideoInstrumentedTest {
    private final int[] location = new int[2];

    private File testRom() throws Exception {
        File out = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "video-test.gen");
        try (InputStream in = ApplicationProvider.getApplicationContext().getAssets().open("video-test.gen");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private MainActivity activity(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<MainActivity> ref = new java.util.concurrent.atomic.AtomicReference<>();
        scenario.onActivity(ref::set); return ref.get();
    }

    private void await(ActivityScenario<MainActivity> scenario, Check check) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 90000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ok = {false};
            scenario.onActivity(a -> { try { ok[0] = check.ok(a); } catch (Exception e) { throw new RuntimeException(e); } });
            if (ok[0]) return;
            SystemClock.sleep(100);
        }
        MainActivity a = activity(scenario);
        EmulatorEngine e = a.engineForTest();
        EmulatorEngine.Frame f = e.frame();
        String diag = "loaded=" + e.isLoaded() + " error=" + e.error()
                + " fps=" + e.stats().fps + " coreMs=" + e.stats().coreMillis
                + " frame=" + (f == null ? "null" : f.serial + ":" + f.width + "x" + f.height + ":state" + f.state.get())
                + " videoSerial=" + a.videoForTest().frameSerial()
                + " presented=" + a.videoForTest().presentedFrames()
                + " nonBlack=" + a.videoForTest().nonBlackFrameSeen()
                + " surface=" + a.videoForTest().getWidth() + "x" + a.videoForTest().getHeight();
        try { Bitmap b = screenshot(a, "timeout"); b.recycle(); } catch (Exception ignored) { }
        fail("Timed out waiting for visible game video; " + diag);
    }
    interface Check { boolean ok(MainActivity a) throws Exception; }

    private Bitmap screenshot(MainActivity a, String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Android compositor screenshot unavailable", bitmap);
        if ("true".equals(InstrumentationRegistry.getArguments().getString("screenshots"))) {
            File dir = new File(a.getFilesDir(), "test-captures"); dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        return bitmap;
    }

    private int[] centralStats(Bitmap b) {
        long sumR=0,sumG=0,sumB=0; int lit=0,total=0;
        int x0=b.getWidth()*35/100,x1=b.getWidth()*65/100,y0=b.getHeight()*25/100,y1=b.getHeight()*65/100;
        for(int y=y0;y<y1;y+=4)for(int x=x0;x<x1;x+=4){
            int c=b.getPixel(x,y);int r=(c>>16)&255,g=(c>>8)&255,bl=c&255;
            sumR+=r;sumG+=g;sumB+=bl;if(r+g+bl>24)lit++;total++;
        }
        return new int[]{(int)(sumR/total),(int)(sumG/total),(int)(sumB/total),lit*100/Math.max(1,total)};
    }

    private MotionEvent event(MainActivity a, int action, float x, float y) {
        a.controlsForTest().getLocationOnScreen(location); x += location[0]; y += location[1];
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN); return e;
    }
    private void inject(MotionEvent e) {
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(e, true)); e.recycle();
    }
    private float[] button(MainActivity a, int button) {
        TouchControlsView v=a.controlsForTest();
        if(button==SegaButtons.START)return new float[]{v.centerX(2),v.centerY(2)};
        return new float[]{v.faceX(0),v.faceY(0)}; // A
    }

    @Test public void realAndroidScreenShowsGameAndTouchChangesPixels() throws Exception {
        ApplicationProvider.getApplicationContext().getSharedPreferences("sega-settings",0).edit().clear().commit();
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_TEST_ROM, testRom().getAbsolutePath())
                .putExtra(MainActivity.EXTRA_TEST_NO_WATCHDOG, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.engineForTest().frame()!=null && a.videoForTest().presentedFrames()>120 && a.videoForTest().nonBlackFrameSeen());
            MainActivity a = activity(scenario);
            assertEquals(6, a.controlsForTest().faceButtonCount());

            Bitmap baseline = screenshot(a, "video-baseline");
            int[] before = centralStats(baseline); baseline.recycle();
            assertTrue("Android screen is black/audio-only: central lit pixels="+before[3]+"%", before[3] >= 20);

            // Gear, not Back, opens settings.
            scenario.onActivity(x -> x.gearForTest().performClick());
            assertTrue(activity(scenario).isSidePanelOpen());
            Bitmap settings = screenshot(a, "settings-open"); settings.recycle();
            scenario.onActivity(x -> x.gearForTest().performClick());
            assertFalse(activity(scenario).isSidePanelOpen());

            for (int button : new int[]{SegaButtons.START, SegaButtons.A}) {
                float[] p = button(a, button);
                int serialBefore = a.engineForTest().frame().serial;
                inject(event(a, MotionEvent.ACTION_DOWN, p[0], p[1]));
                assertEquals("Touch did not reach engine for button "+button, 1 << button, a.engineForTest().inputMask());
                SystemClock.sleep(600);
                Bitmap pressed = screenshot(a, button==SegaButtons.START?"video-start":"video-a");
                int[] after = centralStats(pressed); pressed.recycle();
                inject(event(a, MotionEvent.ACTION_UP, p[0], p[1]));
                assertEquals(0, a.engineForTest().inputMask());
                int serialAfter = a.engineForTest().frame().serial;
                int delta = Math.abs(after[0]-before[0])+Math.abs(after[1]-before[1])+Math.abs(after[2]-before[2]);
                assertTrue("Touch "+button+" did not change on-screen game pixels (delta="+delta
                        +", frameSerial="+serialBefore+"->"+serialAfter+", presented="+a.videoForTest().presentedFrames()+")", delta > 30);
                SystemClock.sleep(300);
            }
        }
    }
}
