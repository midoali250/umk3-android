package com.midoali.atari;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.util.Log;
import com.midoali.atari.core.SegaButtons;
import com.midoali.atari.core.SegaCore;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Audio-clocked emulation with pooled frames, off-thread saves and low-overhead JNI copies. */
final class EmulatorEngine {
    static final class Frame {
        final byte[] pixels = new byte[1024 * 576 * 2];
        final AtomicInteger state = new AtomicInteger(); // 0 free, 1 ready, 2 being uploaded
        volatile int width, height, serial;
        volatile float aspect;
        boolean acquire() { return state.compareAndSet(1, 2); }
        void release() { state.set(0); }
    }
    static final class Stats {
        final double fps, coreMillis;
        final int width, height, underruns;
        Stats(double fps, double coreMillis, int width, int height, int underruns) {
            this.fps = fps; this.coreMillis = coreMillis; this.width = width;
            this.height = height; this.underruns = underruns;
        }
    }
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final AtomicInteger input = new AtomicInteger();
    private final short[] audio = new short[32768];
    private final ByteBuffer video = ByteBuffer.allocateDirect(1024 * 576 * 2).order(ByteOrder.nativeOrder());
    private final int[] metadata = new int[7];
    private final Frame[] pool = {new Frame(), new Frame(), new Frame()};
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicInteger serial = new AtomicInteger();
    private final Object saveMonitor = new Object();
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private volatile boolean running, paused, muted, closed, disposed, saveRequested;
    private volatile boolean sixButton = true, showBorders = true, autoFrameSkip;
    private volatile String error;
    private volatile Runnable frameListener;
    private volatile Stats stats = new Stats(0, 0, 0, 0, 0);
    private Thread thread, saveThread;
    private AudioTrack track;
    private File saveFile;
    private long writtenFrames;
    private int audioBufferFrames, lastUnderruns, lastPublishedSerial = -1;

    void load(File rom, File save) throws IOException {
        lifecycle.lock();
        try {
            if (disposed) throw new IOException("Emulator engine was destroyed");
            closeLocked();
            closed = false; error = null; latest.set(null); saveFile = save;
            lastPublishedSerial = -1; writtenFrames = 0; lastUnderruns = 0;
            SegaCore.nativeSetPerformanceOptions(showBorders, autoFrameSkip);
            SegaCore.nativeLoad(rom.getAbsolutePath(), sixButton);
            if (save.isFile() && SegaCore.nativeSaveSize() == (int)save.length()) {
                if (!SegaCore.nativePersistSave(save.getAbsolutePath(), true))
                    Log.w("SegaEngine", "Could not restore battery save");
            }
            int rate = SegaCore.nativeSampleRate();
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("Audio output is unavailable");
            int bufferBytes = Math.max(min * 3, rate * 4 * 128 / 1000);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio initialization failed");
            audioBufferFrames = track.getBufferSizeInFrames();
            track.setVolume(muted ? 0f : 1f);
            track.play();
            running = true; paused = false;
            saveThread = new Thread(this::saveLoop, "SegaSaveWriter");
            saveThread.setDaemon(true); saveThread.start();
            thread = new Thread(this::runLoop, "SegaEmulation");
            thread.start();
        } catch (IOException | RuntimeException exception) {
            closeLocked();
            throw exception;
        } finally { lifecycle.unlock(); }
    }
    private void runLoop() {
        double frameNanos = 1_000_000_000.0 / SegaCore.nativeFps();
        long nextFrame = System.nanoTime();
        long windowStart = nextFrame, windowCoreMicros = 0;
        int windowFrames = 0;
        while (running) {
            if (paused || closed) {
                sleepQuietly(10); nextFrame = System.nanoTime();
                windowStart = nextFrame; windowFrames = 0; windowCoreMicros = 0;
                continue;
            }
            try {
                updateAudioStatus();
                int count = SegaCore.nativeRun(audio, video, metadata);
                if (count < 0) throw new IllegalStateException("Emulator closed");
                int width = metadata[0], height = metadata[1], coreSerial = metadata[2];
                windowCoreMicros += Math.max(0, metadata[6]);
                if (coreSerial != lastPublishedSerial && width > 0 && height > 0
                        && publishFrame(width, height, Float.intBitsToFloat(metadata[5]))) {
                    lastPublishedSerial = coreSerial;
                }
                if (count > 0 && track.write(audio, 0, count * 2, AudioTrack.WRITE_BLOCKING) < 0)
                    throw new IllegalStateException("Audio write failed");
                writtenFrames += count;
                if (++windowFrames >= 120) {
                    long now = System.nanoTime();
                    double seconds = (now - windowStart) / 1_000_000_000.0;
                    stats = new Stats(seconds > 0 ? windowFrames / seconds : 0,
                            windowCoreMicros / 1000.0 / windowFrames, width, height,
                            track.getUnderrunCount());
                    windowStart = now; windowFrames = 0; windowCoreMicros = 0;
                }
                if (count == 0) {
                    nextFrame += (long)frameNanos;
                    long delay = nextFrame - System.nanoTime();
                    if (delay > 0) sleepQuietly(delay / 1_000_000);
                    else nextFrame = System.nanoTime();
                }
            } catch (RuntimeException exception) {
                if (running && !closed) {
                    error = exception.getMessage();
                    Log.e("SegaEngine", "Emulation stopped", exception);
                }
                running = false;
            }
        }
    }
    private boolean publishFrame(int width, int height, float aspect) {
        Frame current = latest.get();
        for (Frame candidate : pool) {
            if (candidate == current) continue;
            // A previous READY frame that the renderer never acquired is safe to recycle.
            // Only state 2 (actively being uploaded by the renderer) is pinned.
            int state = candidate.state.get();
            if (state == 2 || state == 3 || !candidate.state.compareAndSet(state, 3)) continue;
            video.position(0);
            video.get(candidate.pixels, 0, width * height * 2);
            video.clear();
            candidate.width = width; candidate.height = height;
            candidate.aspect = aspect; candidate.serial = serial.incrementAndGet();
            candidate.state.set(1);
            latest.set(candidate);
            Runnable listener = frameListener;
            if (listener != null) listener.run();
            return true;
        }
        // All buffers are busy: keep the previous image and retry on the next core frame.
        return false;
    }
    private void updateAudioStatus() {
        if (track == null) return;
        int underruns = track.getUnderrunCount();
        boolean underrun = underruns > lastUnderruns;
        lastUnderruns = underruns;
        int occupancy = 100;
        if (track.getTimestamp(timestamp) && audioBufferFrames > 0) {
            long played = timestamp.framePosition & 0xffffffffL;
            long queued = Math.max(0, writtenFrames - played);
            occupancy = (int)Math.min(100, queued * 100 / audioBufferFrames);
        }
        SegaCore.nativeSetAudioStatus(occupancy, true, underrun);
    }
    private void saveLoop() {
        while (running && !closed) {
            synchronized (saveMonitor) {
                if (!saveRequested && running && !closed) {
                    try { saveMonitor.wait(5000); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                }
                saveRequested = false;
            }
            if (running && !closed) writeSave();
        }
    }
    private synchronized void writeSave() {
        File destination = saveFile;
        if (destination == null || SegaCore.nativeSaveSize() <= 0) return;
        File temp = new File(destination.getAbsolutePath() + ".tmp");
        if (SegaCore.nativePersistSave(temp.getAbsolutePath(), false) && !temp.renameTo(destination))
            Log.w("SegaEngine", "Could not commit battery save");
    }
    Frame frame() { return latest.get(); }
    Stats stats() { return stats; }
    String error() { return error; }
    boolean isLoaded() { return running && !closed; }
    boolean isPaused() { return paused; }
    int inputMask() { return input.get(); }
    void setFrameListener(Runnable listener) { frameListener = listener; }
    void setSixButton(boolean value) {
        sixButton = value;
        lifecycle.lock();
        try { if (!closed) SegaCore.nativeSetSixButton(value); }
        finally { lifecycle.unlock(); }
    }
    void setPerformanceOptions(boolean borders, boolean skip) {
        showBorders = borders; autoFrameSkip = skip;
        SegaCore.nativeSetPerformanceOptions(borders, skip);
    }
    void setInput(int mask) {
        int sanitized = SegaButtons.sanitize(mask);
        input.set(sanitized); SegaCore.nativeSetInput(sanitized);
    }
    void setPaused(boolean value) {
        lifecycle.lock();
        try {
            if (closed || !running) return;
            paused = value;
            if (value) {
                setInput(0); track.pause(); track.flush(); writtenFrames = 0;
                requestSave();
            } else track.play();
        } finally { lifecycle.unlock(); }
    }
    void setMuted(boolean value) {
        muted = value;
        lifecycle.lock();
        try { if (track != null) track.setVolume(value ? 0f : 1f); }
        finally { lifecycle.unlock(); }
    }
    void persistSave() { requestSave(); }
    private void requestSave() {
        saveRequested = true;
        synchronized (saveMonitor) { saveMonitor.notifyAll(); }
    }
    void close() { lifecycle.lock(); try { disposed = true; closeLocked(); } finally { lifecycle.unlock(); } }
    private void closeLocked() {
        running = false; closed = true; input.set(0); SegaCore.nativeSetInput(0);
        synchronized (saveMonitor) { saveMonitor.notifyAll(); }
        if (track != null) {
            try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
        }
        if (thread != null && Thread.currentThread() != thread) {
            try { thread.join(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        if (saveThread != null && Thread.currentThread() != saveThread) {
            try { saveThread.join(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
        saveThread = null;
        if (track != null) { track.release(); track = null; }
        writeSave();
        SegaCore.nativeClose(); saveFile = null;
    }
    private static void sleepQuietly(long millis) {
        try { Thread.sleep(Math.max(1, millis)); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
