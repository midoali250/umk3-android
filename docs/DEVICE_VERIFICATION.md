# التحقق على Android فعليًا (محاكي)

الاختبار يعمل داخل Android على GitHub Actions، وليس على صورة نواة كمبيوتر فقط.

## بوابات الاختبار

- `VideoInstrumentedTest`: يشغّل ROM اختبار أصلية مولّدة في `tests/fixtures/make_video_test_rom.py`، ثم يفحص لقطة **Android compositor** عبر `UiAutomation.takeScreenshot()`، ووجود 6 أزرار، وفتح الترس، وتغيّر بكسلات اللعبة عند START وA.
- `ControlsInstrumentedTest`: يشغّل ملف UMK3 الحقيقي المدخل إلى CI، ويصل إلى المباراة، ويفحص استجابة اللعبة وRAM لـSTART والاتجاهات وA/B/C وX/Y/Z واللمس المتعدد، وأوضاع العرض، ثم سحب D-pad في محرر الأماكن وحفظ الإعدادات بعد إعادة إنشاء Activity.
- تُرفع اللقطات وlogcat وتقرير XML في `device-video-proof-x86_64` حتى عند فشل اختبار.

## بيئة CI الحالية

`.github/workflows/device-verification.yml` يشغّل:

1. محاكي **API 35 / google_apis / x86_64 / Pixel 6** على Linux مع KVM وSwiftShader.
2. اختبارَي Android أعلاه عبر `tests/ci/device-video.sh x86_64`.
3. بناء النواة على مضيف **ARM64 فعلي** وفحص إطارات ملف UMK3 حتى الإطار 900؛ هذه وظيفة منفصلة اسمها `arm64-core-pixels`، وليست محاكي ARM64 على Android.

تُجلب اللعبة من أصل GitHub خاص أثناء CI ولا تُضاف إلى Git. لا توجد نتيجة اختبار على هاتف المستخدم فعليًا.

## إعادة إنتاج صورة النواة على المضيف

```sh
python3 tests/fixtures/make_video_test_rom.py android/src/debug/assets/video-test.gen
cmake -S core/src/main/cpp -B host-build -DCMAKE_BUILD_TYPE=Release -DSEGA_TEST_API=OFF
cmake --build host-build -j2
python3 tests/verify_video_pixels.py host-build/libgenesis_host.so /path/to/owned/game.md host-proof 900
```

فحص المضيف مفيد لتشخيص النواة وJNI، لكنه **ليس بديلًا** عن اختبار الشاشة واللمس داخل Android.
