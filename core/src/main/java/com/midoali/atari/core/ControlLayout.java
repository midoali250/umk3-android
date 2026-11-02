package com.midoali.atari.core;

/** Normalized centers remain valid across screen sizes and orientations. */
public final class ControlLayout {
    public static final int VIDEO_ORIGINAL = 0, VIDEO_SMOOTH = 1, VIDEO_ENHANCED = 2;
    public static final int DISPLAY_FIT = 0, DISPLAY_STRETCH = 1;
    public static final int GROUP_PAD = 0, GROUP_FACE = 1, GROUP_START = 2, GROUP_FINISH = 3;

    public float padX = 0.155f, padY = 0.70f;
    public float faceX = 0.82f, faceY = 0.66f;
    public float startX = 0.5f, startY = 0.90f;
    public float finishX = 0.90f, finishY = 0.28f;
    public float scale = 1f;
    public float opacity = 0.72f;
    public boolean bilinear = true;
    public boolean haptics = true;
    public boolean sixButton = true;
    public boolean showBorders = true;
    public boolean autoFrameSkip = false;
    public int videoMode = VIDEO_ENHANCED;
    public int displayMode = DISPLAY_FIT;

    public void move(int group, float x, float y) {
        x = PadMath.clamp(x, 0.05f, 0.95f);
        y = PadMath.clamp(y, 0.12f, 0.95f);
        if (group == GROUP_PAD) { padX = x; padY = y; }
        else if (group == GROUP_FACE) { faceX = x; faceY = y; }
        else if (group == GROUP_FINISH) { finishX = x; finishY = y; }
        else { startX = x; startY = y; }
    }
    public void setScale(float value) { scale = PadMath.clamp(value, 0.7f, 1.5f); }
    public void setOpacity(float value) { opacity = PadMath.clamp(value, 0.3f, 0.95f); }
    public void setVideoMode(int value) {
        videoMode = Math.max(VIDEO_ORIGINAL, Math.min(VIDEO_ENHANCED, value));
        bilinear = videoMode != VIDEO_ORIGINAL;
    }
    public void setBilinear(boolean value) { setVideoMode(value ? VIDEO_SMOOTH : VIDEO_ORIGINAL); }
    public void setDisplayMode(int value) { displayMode = value == DISPLAY_STRETCH ? DISPLAY_STRETCH : DISPLAY_FIT; }
    public void reset() {
        padX = 0.155f; padY = 0.70f; faceX = 0.82f; faceY = 0.66f;
        startX = 0.5f; startY = 0.90f; finishX = 0.90f; finishY = 0.28f;
        scale = 1f; opacity = 0.72f;
    }
    public ControlLayout copy() {
        ControlLayout result = new ControlLayout();
        result.padX = padX; result.padY = padY; result.faceX = faceX; result.faceY = faceY;
        result.startX = startX; result.startY = startY;
        result.finishX = finishX; result.finishY = finishY;
        result.scale = scale; result.opacity = opacity;
        result.bilinear = bilinear; result.haptics = haptics; result.sixButton = sixButton;
        result.showBorders = showBorders; result.autoFrameSkip = autoFrameSkip;
        result.videoMode = videoMode; result.displayMode = displayMode;
        return result;
    }
}
