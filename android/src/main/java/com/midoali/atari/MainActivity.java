package com.midoali.atari;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.midoali.atari.core.ControlLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int OPEN_ROM = 41;
    static final String EXTRA_TEST_ROM = "com.midoali.atari.TEST_ROM";
    static final String EXTRA_TEST_NO_WATCHDOG = "com.midoali.atari.NO_WATCHDOG";
    private final EmulatorEngine engine = new EmulatorEngine();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SettingsStore settingsStore;
    private ControlLayout layout;
    private TouchControlsView controls;
    private GameSurfaceView video;
    private SoftwareVideoView fallbackVideo;
    private boolean usingFallback, pixelCopyPending;
    private TextView status, perf;
    private Button resumeButton, editDone;
    private ImageButton gear;
    private LinearLayout sidePanel;
    private boolean foreground, userPaused, loading, destroyed, panelOpen, resumeAfterPanel, testNoWatchdog;
    private String bundledAsset;
    private final Runnable videoWatchdog = new Runnable() {
        @Override public void run() {
            if (destroyed || usingFallback || !engine.isLoaded()) return;
            // If the native surface never presented frames, switch to the Canvas fallback.
            if (video.presentedFrames() < 10) {
                switchToFallback();
                return;
            }
            // Some devices present a surface but composite it black. Sample the actual
            // surface once; if it is fully black while the core has video, fall back.
            if (!pixelCopyPending && video.nonBlackFrameSeen()) {
                pixelCopyPending = true;
                Bitmap sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                try {
                    PixelCopy.request(video, sample, result -> {
                        pixelCopyPending = false;
                        if (destroyed || usingFallback) { sample.recycle(); return; }
                        boolean black = true;
                        if (result == PixelCopy.SUCCESS) {
                            for (int y = 0; y < 64 && black; y += 4)
                                for (int x = 0; x < 64; x += 4)
                                    if ((sample.getPixel(x, y) & 0x00ffffff) != 0) { black = false; break; }
                        } else black = false;
                        sample.recycle();
                        if (black) switchToFallback();
                    }, handler);
                } catch (RuntimeException e) {
                    pixelCopyPending = false;
                    sample.recycle();
                }
            }
            handler.postDelayed(this, 4000);
        }
    };
    private void switchToFallback() {
        if (usingFallback || destroyed) return;
        usingFallback = true;
        video.setVisibility(View.GONE);
        fallbackVideo.setVisibility(View.VISIBLE);
        engine.setFrameListener(fallbackVideo::requestFrame);
        fallbackVideo.requestFrame();
        Toast.makeText(this, R.string.video_fallback, Toast.LENGTH_SHORT).show();
    }
    private final Runnable updatePerf = new Runnable() {
        @Override public void run() {
            if (!panelOpen || perf == null) return;
            EmulatorEngine.Stats s = engine.stats();
            perf.setText(getString(R.string.perf_readout, s.fps, s.coreMillis, s.width, s.height, s.underruns));
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        settingsStore = new SettingsStore(this); layout = settingsStore.load();
        bundledAsset = findBundledRomAsset();
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);

        video = new GameSurfaceView(this, engine);
        video.setVideoMode(layout.videoMode); video.setDisplayMode(layout.displayMode);
        fallbackVideo = new SoftwareVideoView(this, engine);
        fallbackVideo.setVideoMode(layout.videoMode); fallbackVideo.setDisplayMode(layout.displayMode);
        fallbackVideo.setVisibility(View.GONE);
        engine.setFrameListener(video::requestFrame);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        root.addView(fallbackVideo, new FrameLayout.LayoutParams(-1, -1));

        controls = new TouchControlsView(this);
        controls.setLayout(layout); controls.setInputSink(engine::setInput);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_gear);
        gear.setColorFilter(Color.WHITE);
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable gearBg = new GradientDrawable();
        gearBg.setShape(GradientDrawable.OVAL); gearBg.setColor(0xb0101820);
        gearBg.setStroke(dp(1), 0x60ffffff);
        gear.setBackground(gearBg);
        gear.setContentDescription(getString(R.string.menu_open));
        gear.setElevation(dp(8));
        gear.setOnClickListener(v -> togglePanel());
        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
        gearParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(gear, gearParams);

        buildSidePanel(root);

        editDone = actionButton(R.string.finish_layout, v -> finishEditing());
        editDone.setVisibility(View.GONE);
        root.addView(editDone, new FrameLayout.LayoutParams(dp(150), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(17); status.setGravity(Gravity.CENTER);
        status.setPadding(dp(28), dp(70), dp(28), dp(28)); status.setText(R.string.import_hint);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        hideSystemBars();

        String testPath = BuildConfig.DEBUG ? getIntent().getStringExtra(EXTRA_TEST_ROM) : null;
        testNoWatchdog = BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_TEST_NO_WATCHDOG, false);
        if (testPath != null) loadRom(new File(testPath));
        else if (bundledAsset != null) loadBundledRom(bundledAsset);
        else {
            String saved = settingsStore.preferences().getString("lastRom", null);
            if (saved != null && new File(saved).isFile()) loadRom(new File(saved));
        }
    }

    private void buildSidePanel(FrameLayout root) {
        int panelWidth = Math.min(dp(320), (int)(getResources().getDisplayMetrics().widthPixels * 0.85f));
        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.setBackgroundColor(0xf2101820);
        sidePanel.setElevation(dp(16));
        sidePanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        sidePanel.setVisibility(View.GONE);
        sidePanel.setClickable(true); // block gameplay touches while open

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText(getString(R.string.settings) + "  ·  v" + BuildConfig.VERSION_NAME); title.setTextColor(Color.WHITE); title.setTextSize(16);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = new ImageButton(this); close.setImageResource(R.drawable.ic_close); close.setColorFilter(Color.WHITE);
        close.setBackgroundColor(0x00000000); close.setContentDescription(getString(R.string.menu_close));
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        sidePanel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        resumeButton = actionButton(R.string.resume, v -> { closePanel(); userPaused = false; syncPause(); });
        sidePanel.addView(resumeButton, new LinearLayout.LayoutParams(-1, dp(44)));
        Button reset = actionButton(R.string.reset_game, v -> confirmReset());
        sidePanel.addView(reset, new LinearLayout.LayoutParams(-1, dp(44)));
        if (bundledAsset == null) {
            Button importRom = actionButton(R.string.import_rom, v -> chooseRom());
            sidePanel.addView(importRom, new LinearLayout.LayoutParams(-1, dp(44)));
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(content);
        buildSettingsContent(content);
        sidePanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(panelWidth, -1, Gravity.END);
        root.addView(sidePanel, params);
    }

    private void buildSettingsContent(LinearLayout content) {
        CheckBox six = new CheckBox(this); six.setText(R.string.six_button); six.setChecked(layout.sixButton); six.setTextColor(Color.WHITE); content.addView(six);
        six.setOnCheckedChangeListener((v, checked) -> { layout.sixButton = checked; engine.setSixButton(checked); controls.setLayout(layout); settingsStore.save(layout); });
        spinner(content, R.string.video_quality, R.array.video_quality_modes, layout.videoMode, new SimpleSpinner() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                layout.setVideoMode(position); video.setVideoMode(position); fallbackVideo.setVideoMode(position); settingsStore.save(layout);
            }
        });
        spinner(content, R.string.display_mode, R.array.display_modes, layout.displayMode, new SimpleSpinner() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                layout.setDisplayMode(position); video.setDisplayMode(position); fallbackVideo.setDisplayMode(position); settingsStore.save(layout);
            }
        });
        CheckBox borders = new CheckBox(this); borders.setText(R.string.show_borders); borders.setChecked(layout.showBorders); borders.setTextColor(Color.WHITE); content.addView(borders);
        borders.setOnCheckedChangeListener((v, checked) -> { layout.showBorders = checked; engine.setPerformanceOptions(checked, layout.autoFrameSkip); settingsStore.save(layout); });
        CheckBox skip = new CheckBox(this); skip.setText(R.string.auto_frame_skip); skip.setChecked(layout.autoFrameSkip); skip.setTextColor(Color.WHITE); content.addView(skip);
        skip.setOnCheckedChangeListener((v, checked) -> { layout.autoFrameSkip = checked; engine.setPerformanceOptions(layout.showBorders, checked); settingsStore.save(layout); });
        CheckBox haptics = new CheckBox(this); haptics.setText(R.string.haptics); haptics.setChecked(layout.haptics); haptics.setTextColor(Color.WHITE); content.addView(haptics);
        haptics.setOnCheckedChangeListener((v, checked) -> { layout.haptics = checked; controls.setLayout(layout); settingsStore.save(layout); });
        CheckBox mute = new CheckBox(this); mute.setText(R.string.mute); mute.setTextColor(Color.WHITE); content.addView(mute);
        mute.setOnCheckedChangeListener((v, checked) -> engine.setMuted(checked));
        TextView sizeLabel = new TextView(this); sizeLabel.setText(R.string.control_size); sizeLabel.setTextColor(Color.WHITE); content.addView(sizeLabel);
        SeekBar size = new SeekBar(this); size.setMax(80); size.setProgress(Math.round((layout.scale - .7f) * 100)); content.addView(size);
        size.setOnSeekBarChangeListener(new SimpleSeek() { @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { layout.setScale(.7f + value / 100f); controls.setLayout(layout); settingsStore.save(layout); } });
        TextView opacityLabel = new TextView(this); opacityLabel.setText(R.string.control_opacity); opacityLabel.setTextColor(Color.WHITE); content.addView(opacityLabel);
        SeekBar opacity = new SeekBar(this); opacity.setMax(65); opacity.setProgress(Math.round((layout.opacity - .3f) * 100)); content.addView(opacity);
        opacity.setOnSeekBarChangeListener(new SimpleSeek() { @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { layout.setOpacity(.3f + value / 100f); controls.setLayout(layout); settingsStore.save(layout); } });
        Button edit = actionButton(R.string.edit_layout, v -> { closePanel(); controls.setEditing(true); editDone.setVisibility(View.VISIBLE); syncPause(); });
        content.addView(edit, new LinearLayout.LayoutParams(-1, dp(44)));
        Button resetLayout = actionButton(R.string.reset_layout, v -> { layout.reset(); controls.setLayout(layout); size.setProgress(30); opacity.setProgress(42); settingsStore.save(layout); });
        content.addView(resetLayout, new LinearLayout.LayoutParams(-1, dp(44)));
        perf = new TextView(this); perf.setTextColor(0xffb0c4d8); perf.setPadding(0, dp(10), 0, dp(4)); content.addView(perf);
        TextView explanation = new TextView(this); explanation.setText(R.string.settings_explanation); explanation.setTextColor(0xff90a4b8); explanation.setTextSize(12); content.addView(explanation);
        Button notices = actionButton(R.string.third_party_notices, v -> showNotices());
        content.addView(notices, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void togglePanel() { if (panelOpen) closePanel(); else openPanel(); }
    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true; resumeAfterPanel = userPaused; userPaused = true; syncPause();
        gear.setContentDescription(getString(R.string.menu_close));
        sidePanel.setVisibility(View.VISIBLE);
        sidePanel.setTranslationX(sidePanel.getWidth() > 0 ? sidePanel.getWidth() : dp(320));
        sidePanel.animate().translationX(0).setDuration(180).start();
        handler.removeCallbacks(updatePerf); handler.post(updatePerf);
    }
    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        gear.setContentDescription(getString(R.string.menu_open));
        sidePanel.animate().translationX(sidePanel.getWidth()).setDuration(160)
                .withEndAction(() -> sidePanel.setVisibility(View.GONE)).start();
        handler.removeCallbacks(updatePerf);
        userPaused = resumeAfterPanel; syncPause();
    }

    private Button actionButton(int text, View.OnClickListener listener) {
        Button button = new Button(this); button.setAllCaps(false); button.setText(text); button.setTextSize(13);
        button.setTextColor(Color.WHITE); button.setBackgroundColor(0x30203040); button.setOnClickListener(listener);
        return button;
    }
    private void showNotices() {
        try {
            String text;
            try (InputStream in = getAssets().open("THIRD_PARTY_NOTICES.txt"); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); text = out.toString("UTF-8");
            }
            TextView view = new TextView(this); view.setText(text); view.setPadding(dp(16), dp(16), dp(16), dp(16));
            ScrollView box = new ScrollView(this); box.addView(view);
            new AlertDialog.Builder(this).setTitle(R.string.third_party_notices).setView(box).setPositiveButton(R.string.done, null).show();
        } catch (Exception e) { showError(e.getMessage()); }
    }
    private Spinner spinner(LinearLayout content, int label, int array, int selection, android.widget.AdapterView.OnItemSelectedListener listener) {
        TextView view = new TextView(this); view.setText(label); view.setTextColor(Color.WHITE); view.setPadding(0, dp(10), 0, 0); content.addView(view);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selection); spinner.setOnItemSelectedListener(listener);
        content.addView(spinner); return spinner;
    }

    private String findBundledRomAsset() {
        try {
            String[] names = getAssets().list("roms");
            if (names == null) return null;
            java.util.Arrays.sort(names);
            for (String name : names) {
                if (name.equalsIgnoreCase("README.md")) continue;
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".md") || lower.endsWith(".gen") || lower.endsWith(".bin") || lower.endsWith(".smd")) return "roms/" + name;
            }
        } catch (Exception ignored) { }
        return null;
    }
    private void loadBundledRom(String asset) {
        loading = true; status.setVisibility(View.VISIBLE); status.setText(R.string.loading_rom); controls.setInteractive(false);
        io.execute(() -> {
            File temporary = new File(getFilesDir(), "bundled.tmp");
            try (InputStream input = getAssets().open(asset); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) { total += count; if (total > 16 * 1024 * 1024) throw new IllegalStateException(getString(R.string.rom_too_large)); output.write(buffer, 0, count); }
                output.getFD().sync();
                if (destroyed) return;
                if (!RomValidator.isGenesis(temporary)) throw new IllegalStateException(getString(R.string.invalid_genesis_rom));
                File destination = new File(getFilesDir(), "current.gen");
                if (temporary.length() != destination.length() || !RomValidator.sameBytes(temporary, destination)) {
                    if (destination.exists() && !destination.delete()) throw new IllegalStateException("Could not replace ROM");
                    if (!temporary.renameTo(destination)) throw new IllegalStateException("Could not save ROM");
                }
                runOnUiThread(() -> { if (!destroyed) { loading = false; loadRom(destination); } });
            } catch (Exception exception) { runOnUiThread(() -> { if (!destroyed) { loading = false; showError(exception.getMessage()); syncPause(); } }); }
            finally { temporary.delete(); }
        });
    }
    private void chooseRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, OPEN_ROM);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN_ROM && result == RESULT_OK && data != null && data.getData() != null) importRom(data.getData());
    }
    private void importRom(Uri uri) {
        if (loading) return;
        loading = true; engine.setPaused(true); controls.clearInput(); status.setVisibility(View.VISIBLE); status.setText(R.string.importing);
        io.execute(() -> {
            File temporary = new File(getFilesDir(), "import.tmp");
            try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IllegalStateException(getString(R.string.rom_read_error));
                byte[] buffer = new byte[65536]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) { total += count; if (total > 16 * 1024 * 1024) throw new IllegalStateException(getString(R.string.rom_too_large)); output.write(buffer, 0, count); }
                output.getFD().sync();
                if (!RomValidator.isGenesis(temporary)) throw new IllegalStateException(getString(R.string.invalid_genesis_rom));
                File destination = new File(getFilesDir(), "current.gen");
                if (temporary.length() != destination.length() || !RomValidator.sameBytes(temporary, destination)) {
                    if (destination.exists() && !destination.delete()) throw new IllegalStateException("Could not replace ROM");
                    if (!temporary.renameTo(destination)) throw new IllegalStateException("Could not save ROM");
                }
                runOnUiThread(() -> { loading = false; loadRom(destination); });
            } catch (Exception exception) { runOnUiThread(() -> { loading = false; showError(exception.getMessage()); syncPause(); }); }
            finally { temporary.delete(); }
        });
    }
    void loadRom(File rom) {
        if (loading || destroyed) return;
        loading = true; status.setVisibility(View.VISIBLE); status.setText(R.string.loading_rom); controls.setInteractive(false);
        io.execute(() -> {
            try {
                if (destroyed) return;
                if (!RomValidator.isGenesis(rom)) throw new IllegalStateException(getString(R.string.invalid_genesis_rom));
                engine.setSixButton(layout.sixButton);
                engine.setPerformanceOptions(layout.showBorders, layout.autoFrameSkip);
                engine.load(rom, saveFile(rom));
                runOnUiThread(() -> {
                    if (destroyed) return;
                    loading = false; userPaused = false; settingsStore.preferences().edit().putString("lastRom", rom.getAbsolutePath()).apply();
                    status.setVisibility(View.GONE); controls.setInteractive(true); syncPause(); hideSystemBars();
                    video.requestFrame();
                    if (!testNoWatchdog) handler.postDelayed(videoWatchdog, 4000);
                    if (!settingsStore.preferences().getBoolean("gearHintShown", false)) {
                        Toast.makeText(this, getString(R.string.native_ready) + " v" + BuildConfig.VERSION_NAME + "\n" + getString(R.string.gear_hint), Toast.LENGTH_LONG).show();
                        settingsStore.preferences().edit().putBoolean("gearHintShown", true).apply();
                    }
                });
            } catch (Exception exception) { runOnUiThread(() -> { if (!destroyed) { loading = false; showError(exception.getMessage()); } }); }
        });
    }
    private File saveFile(File rom) throws Exception {
        String hash = RomValidator.sha256(rom); File directory = new File(getFilesDir(), "saves"); directory.mkdirs();
        return new File(directory, hash + ".srm");
    }
    private void showError(String message) { status.setVisibility(View.VISIBLE); status.setText(getString(R.string.emulator_error, message == null ? "Unknown error" : message)); }
    private void confirmReset() { new AlertDialog.Builder(this).setTitle(R.string.reset_game).setMessage(R.string.reset_warning).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.reset, (d, w) -> { String path = settingsStore.preferences().getString("lastRom", null); if (path != null) loadRom(new File(path)); }).show(); }
    private void syncPause() {
        boolean paused = userPaused || panelOpen || !foreground || controls.isEditing(); engine.setPaused(paused);
        if (paused) controls.clearInput(); controls.setInteractive(engine.isLoaded() && !paused);
        if (resumeButton != null) resumeButton.setText(userPaused || panelOpen ? R.string.resume : R.string.pause);
    }
    private void finishEditing() { layout = controls.layout(); settingsStore.save(layout); controls.setEditing(false); editDone.setVisibility(View.GONE); syncPause(); }
    @Override public void onBackPressed() {
        if (panelOpen) closePanel();
        else if (controls.isEditing()) finishEditing();
        else super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); foreground = true; syncPause(); hideSystemBars(); video.requestFrame(); fallbackVideo.requestFrame(); }
    @Override protected void onPause() { foreground = false; controls.clearInput(); engine.setPaused(true); engine.persistSave(); handler.removeCallbacks(updatePerf); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; handler.removeCallbacksAndMessages(null); engine.close(); io.shutdown(); video.release(); fallbackVideo.release(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) hideSystemBars(); }
    private void hideSystemBars() { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    EmulatorEngine engineForTest() { return engine; }
    TouchControlsView controlsForTest() { return controls; }
    GameSurfaceView videoForTest() { return video; }
    SoftwareVideoView fallbackVideoForTest() { return fallbackVideo; }
    ControlLayout layoutForTest() { return layout; }
    ImageButton gearForTest() { return gear; }
    boolean isSidePanelOpen() { return panelOpen; }
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) { }
        @Override public void onStopTrackingTouch(SeekBar bar) { }
    }
    private abstract static class SimpleSpinner implements android.widget.AdapterView.OnItemSelectedListener {
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }
}
