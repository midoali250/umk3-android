package com.midoali.atari;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.midoali.atari.core.Cheats;
import com.midoali.atari.core.SegaButtons;
import com.midoali.atari.core.SegaCore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * End-to-end proof for the cheat panel: a switch tapped in the settings drawer
 * has to change the memory of the fight that is already running.
 *
 * <p>Everything is asserted against Mega Drive work RAM read through the test
 * bridge ({@code nativeTestRam}), so a cosmetic toggle that lights up but does
 * nothing cannot pass.</p>
 */
@RunWith(AndroidJUnit4.class)
public class CheatInstrumentedTest {
    // Verified work-RAM addresses, see docs/CHEATS.md.
    private static final int P1_HEALTH = 0xF228;
    private static final int TIME_TENS = 0xEF66;
    private static final int TIME_UNITS = 0xEF68;

    private final int[] location = new int[2];
    private long downTime;

    private MotionEvent event(MainActivity activity, int action, float x, float y) {
        activity.controlsForTest().getLocationOnScreen(location);
        x += location[0];
        y += location[1];
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x; coords[0].y = y; coords[0].pressure = 1; coords[0].size = 1;
        return MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private void inject(MotionEvent event) {
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .injectInputEvent(event, true));
        event.recycle();
    }

    private void tap(MainActivity activity, int button, int holdMillis) {
        TouchControlsView view = activity.controlsForTest();
        float x, y;
        if (button == SegaButtons.START) {
            x = view.centerX(2); y = view.centerY(2);
        } else if (button == SegaButtons.A) {
            x = view.faceX(0); y = view.faceY(0);
        } else {
            x = view.centerX(0); y = view.centerY(0);
        }
        inject(event(activity, MotionEvent.ACTION_DOWN, x, y));
        SystemClock.sleep(holdMillis);
        inject(event(activity, MotionEvent.ACTION_UP, x, y));
        SystemClock.sleep(250);
    }

    private void await(ActivityScenario<MainActivity> scenario, Check check) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 120000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false};
            scenario.onActivity(a -> ready[0] = check.ok(a));
            if (ready[0]) return;
            SystemClock.sleep(150);
        }
        fail("timed out waiting for the emulator");
    }

    private MainActivity activity(ActivityScenario<MainActivity> scenario) {
        final MainActivity[] ref = new MainActivity[1];
        scenario.onActivity(a -> ref[0] = a);
        return ref[0];
    }

    private CheckBox cheatBox(MainActivity activity, int id) {
        return (CheckBox) activity.sidePanelForTest().findViewWithTag("cheat-" + id);
    }

    /** Opens the drawer, flips one switch, closes the drawer again. */
    private void setSwitch(ActivityScenario<MainActivity> scenario, int cheat, boolean on) throws Exception {
        scenario.onActivity(a -> a.gearForTest().performClick());
        await(scenario, MainActivity::isSidePanelOpen);
        scenario.onActivity(a -> {
            CheckBox box = cheatBox(a, cheat);
            assertNotNull("cheat switch " + cheat + " missing from the drawer", box);
            box.setChecked(on);
            assertEquals("switch " + cheat + " did not keep its state", on, box.isChecked());
        });
        assertEquals("switch " + cheat + " never reached the native engine",
                on, (SegaCore.nativeCheatMask() & (1 << cheat)) != 0);
        scenario.onActivity(a -> a.gearForTest().performClick());
        await(scenario, a -> !a.isSidePanelOpen() && !a.engineForTest().isPaused());
    }

    private static int ram(int address) {
        byte[] bytes = SegaCore.nativeTestRam(address, 1);
        assertNotNull("work RAM unreadable at 0x" + Integer.toHexString(address), bytes);
        return bytes[0] & 0xFF;
    }

    @Test
    public void drawerSwitchesChangeTheRunningFight() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("rom");
        assertNotNull("Local ROM required", path);
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("sega-settings", 0).edit().clear().commit();
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_TEST_ROM, path)
                .putExtra(MainActivity.EXTRA_TEST_NO_WATCHDOG, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.engineForTest().frame() != null && a.videoForTest().presentedFrames() > 120);
            await(scenario, a -> !a.engineForTest().isPaused());
            MainActivity activity = activity(scenario);

            // The panel ships with all four switches off.
            scenario.onActivity(a -> a.gearForTest().performClick());
            await(scenario, MainActivity::isSidePanelOpen);
            for (int id = 0; id < Cheats.COUNT; id++) {
                final int cheat = id;
                scenario.onActivity(a -> {
                    CheckBox box = cheatBox(a, cheat);
                    assertNotNull("cheat switch " + cheat + " missing from the drawer", box);
                    assertFalse("cheat " + cheat + " started enabled", box.isChecked());
                });
            }
            scenario.onActivity(a -> a.gearForTest().performClick());
            await(scenario, a -> !a.isSidePanelOpen() && !a.engineForTest().isPaused());

            // Walk the attract screen into a real match.
            tap(activity, SegaButtons.START, 300); SystemClock.sleep(9000);
            tap(activity, SegaButtons.START, 300); SystemClock.sleep(9000);
            tap(activity, SegaButtons.A, 250); SystemClock.sleep(5000);
            tap(activity, SegaButtons.START, 300); SystemClock.sleep(7000);
            for (int attempt = 0; attempt < 8 && !SegaCore.nativeTestInMatch(); attempt++) {
                tap(activity, SegaButtons.A, 200);
                SystemClock.sleep(2000);
            }
            assertTrue("never reached a live match", SegaCore.nativeTestInMatch());

            // 1. The round clock runs on its own.
            int first = ram(TIME_UNITS);
            boolean ticked = false;
            long deadline = SystemClock.uptimeMillis() + 6000;
            while (SystemClock.uptimeMillis() < deadline && !ticked) {
                SystemClock.sleep(300);
                ticked = ram(TIME_UNITS) != first || ram(TIME_TENS) != 0x09;
            }
            assertTrue("the clock never moved before any cheat was enabled", ticked);

            // 2. Freezing it holds both BCD digits at 99.
            setSwitch(scenario, Cheats.FROZEN_CLOCK, true);
            int frozen = 0;
            deadline = SystemClock.uptimeMillis() + 6000;
            while (SystemClock.uptimeMillis() < deadline) {
                assertEquals("clock tens digit moved while frozen", 0x09, ram(TIME_TENS));
                assertEquals("clock units digit moved while frozen", 0x09, ram(TIME_UNITS));
                frozen++;
                SystemClock.sleep(250);
            }
            assertTrue("too few clock samples to trust the result", frozen >= 12);

            // 3. Tearing it off hands the clock back to the game.
            setSwitch(scenario, Cheats.FROZEN_CLOCK, false);
            boolean resumed = false;
            deadline = SystemClock.uptimeMillis() + 8000;
            while (SystemClock.uptimeMillis() < deadline && !resumed) {
                SystemClock.sleep(300);
                resumed = ram(TIME_UNITS) != 0x09 || ram(TIME_TENS) != 0x09;
            }
            assertTrue("the clock stayed frozen after the switch was turned off", resumed);

            // 4. Infinite energy keeps the life byte nailed down while the
            //    opponent is still attacking.
            setSwitch(scenario, Cheats.INFINITE_ENERGY, true);
            int full = ram(P1_HEALTH);
            assertTrue("life byte looked empty when the cheat was armed", full > 0x40);
            int lowest = 0xFF;
            deadline = SystemClock.uptimeMillis() + 9000;
            while (SystemClock.uptimeMillis() < deadline) {
                lowest = Math.min(lowest, ram(P1_HEALTH));
                SystemClock.sleep(120);
            }
            assertEquals("life moved while infinite energy was on", full, lowest);

            // 5. The switches are persisted and advertised on screen.
            scenario.onActivity(a -> {
                assertEquals(1 << Cheats.INFINITE_ENERGY, new SettingsStore(a).loadCheats());
                assertEquals(View.VISIBLE, a.cheatBadgeForTest().getVisibility());
                assertEquals(1 << Cheats.INFINITE_ENERGY, a.nativeCheatMaskForTest());
            });
        }
    }

    interface Check { boolean ok(MainActivity activity); }
}
