package com.midoali.atari;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.midoali.atari.core.MacroPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a {@link MacroPlan} by writing the pad mask for every step.
 *
 * <p>Runs on the main thread so it interleaves with the frame pipeline exactly like a real
 * finger sequence. The executed masks are kept for the on-device verification test.
 */
final class MacroRunner {
    interface Sink { void send(int mask); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private final List<Integer> history = new ArrayList<>();
    private boolean running;
    private int generation;
    private long startedAt;

    MacroRunner(Sink sink) { this.sink = sink; }

    boolean isRunning() { return running; }

    /** Masks written since the last {@link #start(MacroPlan)}, plus their time offsets. */
    int[] history() {
        int[] result = new int[history.size()];
        for (int i = 0; i < result.length; ++i) result[i] = history.get(i);
        return result;
    }

    long lastStartMillis() { return startedAt; }

    void start(MacroPlan plan) {
        cancel();
        running = true;
        startedAt = SystemClock.uptimeMillis();
        history.clear();
        final int token = ++generation;
        int delay = 0;
        for (int i = 0; i < plan.masks.length; ++i) {
            final int mask = plan.maskAt(i);
            handler.postDelayed(() -> { if (token == generation) write(mask); }, delay);
            delay += MacroPlan.PRESS_MILLIS;
            final int held = plan.holdMask;
            handler.postDelayed(() -> { if (token == generation) write(held); }, delay);
            delay += MacroPlan.GAP_MILLIS;
        }
        delay += MacroPlan.SETTLE_MILLIS;
        handler.postDelayed(() -> {
            if (token != generation) return;
            running = false;
            write(0);
        }, delay);
    }

    void cancel() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (running) { running = false; write(0); }
    }

    private void write(int mask) {
        if (history.size() < 64) history.add(mask);
        sink.send(mask);
    }
}
