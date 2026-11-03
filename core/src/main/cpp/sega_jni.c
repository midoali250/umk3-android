#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>
#include "cheat_engine.h"
#include "cheat_hook.h"
#include "genesis-plus-gx/libretro/libretro-common/include/libretro.h"

#define MAX_ROM (16u * 1024u * 1024u)
#define MAX_WIDTH 1024
#define MAX_HEIGHT 576
#define AUDIO_CAPACITY 32768
#define LOG_TAG "SegaNative"

void retro_set_environment(retro_environment_t);
void retro_set_video_refresh(retro_video_refresh_t);
void retro_set_audio_sample_batch(retro_audio_sample_batch_t);
void retro_set_audio_sample(retro_audio_sample_t);
void retro_set_input_poll(retro_input_poll_t);
void retro_set_input_state(retro_input_state_t);
void retro_init(void);
void retro_deinit(void);
void retro_get_system_av_info(struct retro_system_av_info *);
bool retro_load_game(const struct retro_game_info *);
void retro_unload_game(void);
void retro_run(void);
void retro_reset(void);
void retro_set_controller_port_device(unsigned, unsigned);
size_t retro_get_memory_size(unsigned);
void *retro_get_memory_data(unsigned);

static pthread_mutex_t core_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_int input_mask;
static atomic_bool show_borders = true, auto_frame_skip = false, options_updated = false;
static bool loaded;
static uint8_t *rom_data;
static size_t rom_size, max_width, max_height, frame_serial;
static unsigned frame_width, frame_height;
static float display_aspect = 4.0f / 3.0f;
static int16_t audio_buffer[AUDIO_CAPACITY];
static size_t audio_count;
static uint16_t *video_target;
static bool video_updated;
static unsigned debug_runs, debug_inputs;
static int debug_last_input = -1;
static retro_audio_buffer_status_callback_t audio_status_cb;
static unsigned audio_occupancy;
static bool audio_active, audio_underrun;

/* Thin bridge: the cheat logic lives in cheat_engine.c so the host build can
 * run the very same code (see tools/verify_cheats.py). */
static void apply_cheats(void) {
    sega_cheats_hook_sync();   /* install/remove the 68K write guard first */
    uint8_t *ram = loaded ? (uint8_t *)retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) : NULL;
    size_t size = loaded ? retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM) : 0;
    if (ram) sega_cheats_apply(ram, size);
}

static void throw_java(JNIEnv *env, const char *type, const char *message) {
    jclass cls = (*env)->FindClass(env, type);
    if (cls) (*env)->ThrowNew(env, cls, message);
}
static void video_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (debug_runs < 8) {
        unsigned first = 0, nonzero = 0;
        if (data && width && height) {
            memcpy(&first, data, sizeof(first));
            for (unsigned x = 0; x < width && x < 256; ++x) {
                uint16_t p; memcpy(&p, (const uint8_t *)data + x * 2, sizeof(p));
                if (p) nonzero++;
            }
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "video_cb data=%p %ux%u pitch=%zu max=%zux%zu first=0x%04x nonzero=%u", data, width, height, pitch, max_width, max_height, first, nonzero);
    }
    if (!data || width == 0 || height == 0 || width > max_width || height > max_height) return;
    uint16_t *destination = video_target;
    if (!destination) return;
    const uint8_t *source = (const uint8_t *)data;
    for (unsigned y = 0; y < height; ++y)
        memcpy(destination + (size_t)y * width, source + y * pitch, (size_t)width * 2);
    frame_width = width; frame_height = height;
    frame_serial++;
    video_updated = true;
}
static size_t audio_callback(const int16_t *data, size_t frames) {
    size_t samples = frames * 2;
    if (samples > AUDIO_CAPACITY) samples = AUDIO_CAPACITY;
    if (data && samples) memcpy(audio_buffer, data, samples * sizeof(int16_t));
    audio_count = samples;
    return frames;
}
static void audio_sample(int16_t left, int16_t right) {
    if (audio_count + 2 <= AUDIO_CAPACITY) {
        audio_buffer[audio_count++] = left; audio_buffer[audio_count++] = right;
    }
}
static void input_poll(void) { }
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || index != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    int mask = atomic_load_explicit(&input_mask, memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)mask;
    return id < 16 ? (int16_t)((mask >> id) & 1) : 0;
}
static const char *option_value(const char *key) {
    if (!strcmp(key, "genesis_plus_gx_overscan"))
        return atomic_load(&show_borders) ? "full" : "disabled";
    if (!strcmp(key, "genesis_plus_gx_aspect_ratio")) return "auto";
    if (!strcmp(key, "genesis_plus_gx_blargg_ntsc_filter")) return "disabled";
    if (!strcmp(key, "genesis_plus_gx_render")) return "single field";
    if (!strcmp(key, "genesis_plus_gx_frameskip"))
        return atomic_load(&auto_frame_skip) ? "auto" : "disabled";
    if (!strcmp(key, "genesis_plus_gx_frameskip_threshold")) return "33";
    return NULL;
}
static bool environment(unsigned command, void *data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return *(const enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_RGB565;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *variable = (struct retro_variable *)data;
            const char *value = variable && variable->key ? option_value(variable->key) : NULL;
            if (variable) variable->value = value;
            return value != NULL;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = atomic_exchange(&options_updated, false); return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const struct retro_system_av_info *info = (const struct retro_system_av_info *)data;
            if (info->geometry.aspect_ratio > 0) display_aspect = info->geometry.aspect_ratio;
            if (info->geometry.max_width > max_width) max_width = info->geometry.max_width;
            if (info->geometry.max_height > max_height) max_height = info->geometry.max_height;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: {
            const struct retro_audio_buffer_status_callback *callback =
                (const struct retro_audio_buffer_status_callback *)data;
            audio_status_cb = callback ? callback->callback : NULL;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY: return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback *log = (struct retro_log_callback *)data;
            log->log = NULL; return false;
        }
        default: return false;
    }
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeSetInput(JNIEnv *env, jclass cls, jint mask) {
    (void)env; (void)cls;
    int sanitized = mask & 0xfff;
    if (sanitized != debug_last_input && debug_inputs < 32) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "input mask=0x%x", sanitized);
        debug_last_input = sanitized; debug_inputs++;
    }
    atomic_store_explicit(&input_mask, sanitized, memory_order_relaxed);
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeSetSixButton(JNIEnv *env, jclass cls, jboolean enabled) {
    (void)env; (void)cls;
    pthread_mutex_lock(&core_mutex);
    if (loaded) retro_set_controller_port_device(0,
        enabled ? RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_JOYPAD, 1) : RETRO_DEVICE_JOYPAD);
    pthread_mutex_unlock(&core_mutex);
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeSetCheat(JNIEnv *env, jclass cls,
        jint id, jboolean enabled) {
    (void)env; (void)cls;
    sega_cheats_set(id, enabled ? 1 : 0);
}
JNIEXPORT jint JNICALL Java_com_midoali_atari_core_SegaCore_nativeCheatMask(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return sega_cheats_mask();
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeSetPerformanceOptions(
        JNIEnv *env, jclass cls, jboolean borders, jboolean skip) {
    (void)env; (void)cls;
    bool changed = atomic_load(&show_borders) != (bool)borders || atomic_load(&auto_frame_skip) != (bool)skip;
    atomic_store(&show_borders, borders); atomic_store(&auto_frame_skip, skip);
    if (changed) atomic_store(&options_updated, true);
}
JNIEXPORT jboolean JNICALL Java_com_midoali_atari_core_SegaCore_nativeLoad(JNIEnv *env, jclass cls, jstring path, jboolean sixButton) {
    (void)cls;
    pthread_mutex_lock(&core_mutex);
    bool success = false;
    const char *utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) goto done;
    FILE *file = fopen(utf, "rb");
    if (!file) { throw_java(env, "java/io/IOException", "Cannot open ROM"); goto release_path; }
    if (fseek(file, 0, SEEK_END) != 0) { fclose(file); throw_java(env, "java/io/IOException", "Cannot size ROM"); goto release_path; }
    long length = ftell(file); rewind(file);
    if (length <= 0 || (unsigned long)length > MAX_ROM) {
        fclose(file); throw_java(env, "java/io/IOException", "ROM must be between 1 byte and 16 MiB"); goto release_path;
    }
    uint8_t *data = malloc((size_t)length);
    if (!data) { fclose(file); throw_java(env, "java/lang/OutOfMemoryError", "ROM allocation failed"); goto release_path; }
    if (fread(data, 1, (size_t)length, file) != (size_t)length) {
        free(data); fclose(file); throw_java(env, "java/io/IOException", "ROM read failed"); goto release_path;
    }
    fclose(file);
    if (loaded) { retro_unload_game(); retro_deinit(); loaded = false; }
    free(rom_data); rom_data = data; rom_size = (size_t)length;
    atomic_store(&input_mask, 0);
    retro_set_environment(environment); retro_set_video_refresh(video_callback);
    retro_set_audio_sample_batch(audio_callback); retro_set_audio_sample(audio_sample);
    retro_set_input_poll(input_poll); retro_set_input_state(input_state);
    retro_init();
    struct retro_game_info game = {utf, rom_data, rom_size, 0};
    if (!retro_load_game(&game)) {
        retro_deinit(); free(rom_data); rom_data = NULL; rom_size = 0;
        throw_java(env, "java/io/IOException", "Unsupported or invalid Genesis ROM"); goto release_path;
    }
    struct retro_system_av_info info;
    retro_get_system_av_info(&info);
    max_width = info.geometry.max_width; max_height = info.geometry.max_height;
    if (info.geometry.aspect_ratio > 0) display_aspect = info.geometry.aspect_ratio;
    if (max_width > MAX_WIDTH || max_height > MAX_HEIGHT) {
        retro_unload_game(); retro_deinit(); free(rom_data); rom_data = NULL; rom_size = 0;
        throw_java(env, "java/io/IOException", "Core framebuffer exceeds frontend limits"); goto release_path;
    }
    retro_set_controller_port_device(0, sixButton ? RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_JOYPAD, 1) : RETRO_DEVICE_JOYPAD);
    frame_width = info.geometry.base_width; frame_height = info.geometry.base_height;
    frame_serial = 0; audio_count = 0; video_updated = false; audio_status_cb = NULL;
    debug_runs = 0; debug_inputs = 0; debug_last_input = -1;
    loaded = true; success = true;
release_path:
    (*env)->ReleaseStringUTFChars(env, path, utf);
done:
    pthread_mutex_unlock(&core_mutex);
    return success ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_com_midoali_atari_core_SegaCore_nativeRun(JNIEnv *env, jclass cls,
        jshortArray audio, jobject video, jintArray metadata) {
    (void)cls;
    pthread_mutex_lock(&core_mutex);
    jint result = -1;
    if (!loaded || !audio || !video || !metadata || (*env)->GetArrayLength(env, audio) < AUDIO_CAPACITY ||
        (*env)->GetArrayLength(env, metadata) < 7 ||
        (*env)->GetDirectBufferCapacity(env, video) < MAX_WIDTH * MAX_HEIGHT * 2) goto done;
    uint16_t *pixels = (uint16_t *)(*env)->GetDirectBufferAddress(env, video);
    if (!pixels) goto done;
    video_target = pixels; video_updated = false; audio_count = 0;
    if (audio_status_cb) audio_status_cb(audio_active, audio_occupancy, audio_underrun);
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    apply_cheats();
    retro_run();
    apply_cheats();
    clock_gettime(CLOCK_MONOTONIC, &end);
    video_target = NULL;
    if (debug_runs < 8) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "run #%u updated=%d serial=%zu %ux%u audio=%zu", debug_runs, video_updated ? 1 : 0, frame_serial, frame_width, frame_height, audio_count);
    debug_runs++;
    long long micros = (long long)(end.tv_sec - start.tv_sec) * 1000000LL + (end.tv_nsec - start.tv_nsec) / 1000;
    // Region copies avoid pinning/copying the entire capacity on every frame.
    if (audio_count) (*env)->SetShortArrayRegion(env, audio, 0, (jsize)audio_count, audio_buffer);
    uint32_t aspect_bits; memcpy(&aspect_bits, &display_aspect, sizeof(aspect_bits));
    jint values[7] = {(jint)frame_width, (jint)frame_height, (jint)frame_serial,
                      (jint)max_width, (jint)max_height, (jint)aspect_bits, (jint)micros};
    (*env)->SetIntArrayRegion(env, metadata, 0, 7, values);
    result = (jint)audio_count / 2;
done:
    pthread_mutex_unlock(&core_mutex);
    return result;
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeSetAudioStatus(JNIEnv *env, jclass cls,
        jint occupancy, jboolean active, jboolean underrun) {
    (void)env; (void)cls;
    audio_occupancy = occupancy < 0 ? 0 : (occupancy > 100 ? 100 : (unsigned)occupancy);
    audio_active = active; audio_underrun = underrun;
}
JNIEXPORT jint JNICALL Java_com_midoali_atari_core_SegaCore_nativeSampleRate(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; pthread_mutex_lock(&core_mutex);
    struct retro_system_av_info info; jint rate = 44100;
    if (loaded) { retro_get_system_av_info(&info); rate = (jint)info.timing.sample_rate; }
    pthread_mutex_unlock(&core_mutex); return rate;
}
JNIEXPORT jdouble JNICALL Java_com_midoali_atari_core_SegaCore_nativeFps(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; pthread_mutex_lock(&core_mutex);
    struct retro_system_av_info info; jdouble fps = 60;
    if (loaded) { retro_get_system_av_info(&info); fps = info.timing.fps; }
    pthread_mutex_unlock(&core_mutex); return fps;
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeReset(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; pthread_mutex_lock(&core_mutex);
    if (loaded) retro_reset(); atomic_store(&input_mask, 0); pthread_mutex_unlock(&core_mutex);
}
JNIEXPORT jint JNICALL Java_com_midoali_atari_core_SegaCore_nativeSaveSize(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; pthread_mutex_lock(&core_mutex);
    jint size = loaded ? (jint)retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0;
    pthread_mutex_unlock(&core_mutex); return size;
}
JNIEXPORT jboolean JNICALL Java_com_midoali_atari_core_SegaCore_nativePersistSave(JNIEnv *env, jclass cls, jstring path, jboolean restore) {
    (void)cls; pthread_mutex_lock(&core_mutex);
    bool success = false;
    size_t size = loaded ? retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0;
    void *memory = loaded ? retro_get_memory_data(RETRO_MEMORY_SAVE_RAM) : NULL;
    if (!size || !memory) goto done;
    const char *utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) goto done;
    FILE *file = fopen(utf, restore ? "rb" : "wb");
    if (file) {
        success = restore ? fread(memory, 1, size, file) == size : fwrite(memory, 1, size, file) == size;
        fclose(file);
    }
    (*env)->ReleaseStringUTFChars(env, path, utf);
done:
    pthread_mutex_unlock(&core_mutex); return success ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_midoali_atari_core_SegaCore_nativeClose(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; pthread_mutex_lock(&core_mutex);
    if (loaded) { retro_unload_game(); retro_deinit(); loaded = false; }
    free(rom_data); rom_data = NULL; rom_size = 0; atomic_store(&input_mask, 0);
    audio_status_cb = NULL; video_target = NULL;
    pthread_mutex_unlock(&core_mutex);
}
#ifdef SEGA_TEST_API
JNIEXPORT jint JNICALL Java_com_midoali_atari_core_SegaCore_nativeTestInput(JNIEnv *env, jclass cls, jint id) {
    (void)env; (void)cls;
    if (id < 0) return atomic_load_explicit(&input_mask, memory_order_relaxed);
    return input_state(0, RETRO_DEVICE_JOYPAD, 0, (unsigned)id);
}
JNIEXPORT jboolean JNICALL Java_com_midoali_atari_core_SegaCore_nativeTestInMatch(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; pthread_mutex_lock(&core_mutex);
    uint8_t *ram = loaded ? (uint8_t *)retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) : NULL;
    jboolean live = (jboolean)sega_cheats_in_match(ram, ram ? retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM) : 0);
    pthread_mutex_unlock(&core_mutex); return live;
}
JNIEXPORT jbyteArray JNICALL Java_com_midoali_atari_core_SegaCore_nativeTestRam(JNIEnv *env, jclass cls, jint offset, jint length) {
    (void)cls; pthread_mutex_lock(&core_mutex);
    jbyteArray result = NULL;
    uint8_t *ram = loaded ? (uint8_t *)retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) : NULL;
    size_t size = loaded ? retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM) : 0;
    if (ram && offset >= 0 && length > 0 && (size_t)offset + (size_t)length <= size) {
        result = (*env)->NewByteArray(env, length);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)(ram + offset));
    }
    pthread_mutex_unlock(&core_mutex); return result;
}
#endif
