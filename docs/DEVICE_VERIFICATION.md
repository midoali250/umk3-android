# Device video verification

The black-screen/audio-only regression is gated by an instrumented test that runs
on a real Android emulator, not by host-only framebuffer hashes.

## Public-domain test ROM

`tests/fixtures/make_video_test_rom.py` generates `android/src/debug/assets/video-test.gen`.
It contains no copyrighted game data. The 68000 program displays a solid backdrop
and copies controller bits into CRAM color 0, so pressing Start/A must change the
pixels that Android actually composites to the screen.

Regenerate with:

```sh
python3 tests/fixtures/make_video_test_rom.py android/src/debug/assets/video-test.gen
```

## GitHub Actions gate

`.github/workflows/device-verification.yml` boots an API 35 `arm64-v8a` emulator on
an Apple Silicon GitHub runner and runs `VideoInstrumentedTest`. The test asserts:

- the emulator produces >120 frames and reports a non-black frame;
- `UiAutomation.takeScreenshot()` contains >=20% lit pixels in the central game area;
- the gear opens/closes settings;
- dispatched touch Start/A changes the on-screen game color;
- six face buttons are present.

Screenshots and logcat are uploaded as `arm64-device-video-proof`.

## Host pixel evidence

`tests/verify_video_pixels.py` decodes real RGB565 libretro callbacks to PNG/JSON.
It is useful for diagnosing the core/JNI boundary, but it is **not** a substitute
for the Android compositor screenshot gate.
