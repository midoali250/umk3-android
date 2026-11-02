package com.midoali.atari;

import android.os.SystemClock;
import com.midoali.atari.core.MacroPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a {@link MacroPlan} with pad presses paced by emulated frames.
 *
 * <p>Every press is held for {@link #PRESS_FRAMES} emulated frames and released for
 * {@link #GAP_FRAMES}, so the game sees each key change even when the UI thread is busy.
 * Pacing on the core's own frame counter (instead of wall-clock timers posted on the main
 * thread) is what made the one-tap fatalities register reliably on the emulator.
 *
 * <p>The runner owns the pad for the whole sequence: manual stick input is ignored while it
 * runs, and the pad is released afterwards.
 */
final class MacroRunner {
    interface Sink { void send(int mask); }
    interface FrameSource { int frames(); }

    /** Emulated frames each synthetic press stays down. About 83 ms at 60 Hz. */
    static final int PRESS_FRAMES = 5;
    /**
     * Emulated frames between two presses. The game must poll the button released between
     * two taps of the same direction (DOWN, DOWN), so the gap is deliberately as long as
     * the press itself instead of one or two frames.
     */
    static final int GAP_FRAMES = 5;
    /** Safety net: never wait longer than this for a frame (paused or stalled core). */
    static final long FRAME_TIMEOUT_MILLIS = 200;

    private final Sink sink;
    private final FrameSource frameSource;
    private final List<Integer> history = new ArrayList<>();
    private volatile boolean running;
    private volatile long startedAt;
    private volatile int generation;
    private Thread thread;

    MacroRunner(Sink sink, FrameSource frameSource) {
        this.sink = sink;
        this.frameSource = frameSource;
    }

    boolean isRunning() { return running; }
    long lastStartMillis() { return startedAt; }
    /** Mask values written since the last {@link #start(MacroPlan)}, including releases. */
    synchronized int[] history() {
        int[] result = new int[history.size()];
        for (int i = 0; i < result.length; ++i) result[i] = history.get(i);
        return result;
    }

    void start(MacroPlan plan) {
        cancel();
        history.clear();
        final int token = ++generation;
        running = true;
        startedAt = SystemClock.uptimeMillis();
        thread = new Thread(() -> play(plan, token), "SegaMacro");
        thread.setDaemon(true);
        thread.start();
    }

    void cancel() {
        generation++;
        boolean wasRunning = running;
        running = false;
        Thread current = thread;
        thread = null;
        if (current != null) current.interrupt();
        if (wasRunning) write(0);
    }

    private void play(MacroPlan plan, int token) {
        try {
            for (int i = 0; i < plan.masks.length && token == generation; ++i) {
                write(plan.maskAt(i));
                awaitFrames(PRESS_FRAMES);
                if (token != generation) return;
                write(plan.holdMask);
                awaitFrames(GAP_FRAMES);
            }
        } finally {
            if (token == generation) {
                write(0);
                running = false;
            }
        }
    }

    private void awaitFrames(int frames) {
        int start = frameSource.frames();
        long deadline = SystemClock.uptimeMillis() + FRAME_TIMEOUT_MILLIS * frames;
        while (frameSource.frames() - start < frames) {
            if (Thread.currentThread().isInterrupted()) return;
            if (SystemClock.uptimeMillis() > deadline) return;
            SystemClock.sleep(2);
        }
    }

    private synchronized void write(int mask) {
        if (history.size() < 64) history.add(mask);
        sink.send(mask);
    }
}
