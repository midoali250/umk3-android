package com.midoali.atari;

import android.content.Context;
import android.content.SharedPreferences;
import com.midoali.atari.core.Cheats;
import com.midoali.atari.core.ControlLayout;

final class SettingsStore {
    private static final int LAYOUT_VERSION = 2;
    private final SharedPreferences prefs;
    SettingsStore(Context context) {
        prefs = context.getSharedPreferences("sega-settings", Context.MODE_PRIVATE);
    }
    ControlLayout load() {
        ControlLayout value = new ControlLayout();
        boolean migrated = prefs.getInt("layoutVersion", 1) >= LAYOUT_VERSION;
        if (migrated) {
            value.padX = prefs.getFloat("padX", value.padX);
            value.padY = prefs.getFloat("padY", value.padY);
            value.faceX = prefs.getFloat("faceX", value.faceX);
            value.faceY = prefs.getFloat("faceY", value.faceY);
            value.startX = prefs.getFloat("startX", value.startX);
            value.startY = prefs.getFloat("startY", value.startY);
            value.finishX = prefs.getFloat("finishX", value.finishX);
            value.finishY = prefs.getFloat("finishY", value.finishY);
            value.setScale(prefs.getFloat("scale", value.scale));
            value.sixButton = prefs.getBoolean("sixButton", true);
            value.showBorders = prefs.getBoolean("showBorders", true);
            value.autoFrameSkip = prefs.getBoolean("autoFrameSkip", false);
            value.setVideoMode(prefs.getInt("videoMode", ControlLayout.VIDEO_ENHANCED));
            value.setDisplayMode(prefs.getInt("displayMode", ControlLayout.DISPLAY_FIT));
        } else {
            // The old three-button cluster is too narrow for six buttons; start from safe defaults.
            value.reset();
        }
        value.setOpacity(prefs.getFloat("opacity", value.opacity));
        value.haptics = prefs.getBoolean("haptics", true);
        value.move(0, value.padX, value.padY);
        value.move(1, value.faceX, value.faceY);
        value.move(2, value.startX, value.startY);
        value.move(3, value.finishX, value.finishY);
        if (!migrated) save(value);
        return value;
    }
    void save(ControlLayout value) {
        prefs.edit().putInt("layoutVersion", LAYOUT_VERSION)
                .putFloat("padX", value.padX).putFloat("padY", value.padY)
                .putFloat("faceX", value.faceX).putFloat("faceY", value.faceY)
                .putFloat("startX", value.startX).putFloat("startY", value.startY)
                .putFloat("finishX", value.finishX).putFloat("finishY", value.finishY)
                .putFloat("scale", value.scale).putFloat("opacity", value.opacity)
                .putBoolean("bilinear", value.bilinear).putBoolean("haptics", value.haptics)
                .putBoolean("sixButton", value.sixButton).putBoolean("showBorders", value.showBorders)
                .putBoolean("autoFrameSkip", value.autoFrameSkip)
                .putInt("videoMode", value.videoMode).putInt("displayMode", value.displayMode).apply();
    }
    /** Cheat switches survive restarts and game reloads. */
    int loadCheats() { return Cheats.clamp(prefs.getInt("cheatMask", Cheats.NONE)); }
    void saveCheats(int mask) { prefs.edit().putInt("cheatMask", Cheats.clamp(mask)).apply(); }
    SharedPreferences preferences() { return prefs; }
}
