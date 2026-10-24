/*
 * JNI bridge between the UxPlay protocol library (GPL-3.0, see uxplay/) and the Kotlin app.
 * Replaces the GStreamer renderers of uxplay.cpp: decrypted H.264 / audio frames are handed to Kotlin,
 * which feeds them to MediaCodec.
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "raop.h"
#include "dnssd.h"
#include "logger.h"

const char *dnssd_android_name(dnssd_t *dnssd_public, int service);
const char *dnssd_android_text(dnssd_t *dnssd_public, int service);

#define TAG "TVCast.Native"

static JavaVM *g_vm;
static pthread_key_t g_key;
static jobject g_listener;
static raop_t *g_raop;
static dnssd_t *g_dnssd;
static unsigned short g_port;

static jmethodID m_video_size, m_video_frame, m_audio_format, m_audio_frame, m_flush, m_connection, m_volume, m_reset;

static void detach_thread(void *unused)
{
    (void) unused;
    (*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *get_env(void)
{
    JNIEnv *env = NULL;
    jint r = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
        pthread_setspecific(g_key, (void *) 1);
    }
    return env;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    g_vm = vm;
    pthread_key_create(&g_key, detach_thread);
    return JNI_VERSION_1_6;
}

static jbyteArray to_java_bytes(JNIEnv *env, const unsigned char *data, int len)
{
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    }
    return arr;
}

/* ---- raop callbacks ---------------------------------------------------- */

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data)
{
    (void) cls; (void) ntp;
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jbyteArray arr = to_java_bytes(env, data->data, data->data_len);
    if (!arr) return;
    (*env)->CallVoidMethod(env, g_listener, m_audio_frame, arr, (jint) data->ct, (jlong) data->ntp_time_remote);
    (*env)->DeleteLocalRef(env, arr);
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data)
{
    (void) cls; (void) ntp;
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jbyteArray arr = to_java_bytes(env, data->data, data->data_len);
    if (!arr) return;
    (*env)->CallVoidMethod(env, g_listener, m_video_frame, arr, (jboolean) data->is_h265, (jlong) data->ntp_time_remote);
    (*env)->DeleteLocalRef(env, arr);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset,
                                       unsigned short y_offset, unsigned short width, unsigned short height)
{
    (void) cls; (void) x_offset; (void) y_offset; (void) width; (void) height;
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_video_size, (jint) width_source, (jint) height_source,
                               (jboolean) (codec == VIDEO_CODEC_H265));
    }
    return 0;
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen,
                                bool *is_media, uint64_t *audio_format)
{
    (void) cls; (void) spf; (void) using_screen; (void) is_media; (void) audio_format;
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_audio_format, (jint) *ct);
    }
}

static void notify_flush(int video)
{
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_flush, (jboolean) video);
    }
}

static void cb_audio_flush(void *cls) { (void) cls; notify_flush(0); }
static void cb_video_flush(void *cls) { (void) cls; notify_flush(1); }

static void notify_connection(int open)
{
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_connection, (jboolean) open);
    }
}

static void cb_conn_init(void *cls) { (void) cls; notify_connection(1); }
static void cb_conn_destroy(void *cls) { (void) cls; notify_connection(0); }

static void cb_conn_reset(void *cls, int reason)
{
    (void) cls;
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_reset, (jint) reason);
    }
}

static void cb_video_reset(void *cls, reset_type_t type)
{
    (void) cls; (void) type;
    notify_flush(1);
}

static void cb_audio_set_volume(void *cls, float volume)
{
    (void) cls;
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_volume, (jfloat) volume);
    }
}

static double cb_audio_set_client_volume(void *cls) { (void) cls; return 0.0; }
static void cb_noop(void *cls) { (void) cls; }
static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit)
{
    (void) cls; (void) deviceid; (void) model; (void) name;
    *admit = true;
}
static bool cb_check_register(void *cls, const char *pk) { (void) cls; (void) pk; return true; }
static void cb_register_client(void *cls, const char *id, const char *pk, const char *name)
{
    (void) cls; (void) id; (void) pk; (void) name;
}
static void cb_display_pin(void *cls, char *pin) { (void) cls; (void) pin; }
static void cb_export_dacp(void *cls, const char *a, const char *b) { (void) cls; (void) a; (void) b; }
static void cb_audio_set_metadata(void *cls, const void *b, int l) { (void) cls; (void) b; (void) l; }
static void cb_audio_set_coverart(void *cls, const void *b, int l) { (void) cls; (void) b; (void) l; }
static void cb_audio_set_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e)
{
    (void) cls; (void) s; (void) c; (void) e;
}
static void cb_on_video_play(void *cls, const char *l, const float p) { (void) cls; (void) l; (void) p; }
static void cb_on_video_scrub(void *cls, const float p) { (void) cls; (void) p; }
static void cb_on_video_rate(void *cls, const float r) { (void) cls; (void) r; }
static float cb_on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *i) { (void) cls; (void) i; }

static void cb_log(void *cls, int level, const char *msg)
{
    (void) cls;
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level == LOGGER_WARNING ? ANDROID_LOG_WARN
             : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_print(prio, TAG, "%s", msg);
}

/* ---- JNI entry points ---------------------------------------------------- */

static void destroy_all(JNIEnv *env)
{
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    if (g_listener) {
        (*env)->DeleteGlobalRef(env, g_listener);
        g_listener = NULL;
    }
}

/* Returns the raop/airplay port (>0) or a negative error code. */
JNIEXPORT jint JNICALL
Java_io_github_wamior_tvcast_mirror_AirPlayNative_start(JNIEnv *env, jobject thiz, jobject listener, jstring jname,
                                                         jbyteArray jhw, jstring jmac, jstring jkeyfile)
{
    (void) thiz;
    if (g_raop) return -10;

    jclass cls = (*env)->GetObjectClass(env, listener);
    m_video_size = (*env)->GetMethodID(env, cls, "onVideoSize", "(IIZ)V");
    m_video_frame = (*env)->GetMethodID(env, cls, "onVideoFrame", "([BZJ)V");
    m_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(I)V");
    m_audio_frame = (*env)->GetMethodID(env, cls, "onAudioFrame", "([BIJ)V");
    m_flush = (*env)->GetMethodID(env, cls, "onFlush", "(Z)V");
    m_connection = (*env)->GetMethodID(env, cls, "onConnection", "(Z)V");
    m_volume = (*env)->GetMethodID(env, cls, "onVolume", "(F)V");
    m_reset = (*env)->GetMethodID(env, cls, "onReset", "(I)V");
    if (!m_video_size || !m_video_frame || !m_audio_format || !m_audio_frame || !m_flush || !m_connection
        || !m_volume || !m_reset) {
        return -11;
    }
    g_listener = (*env)->NewGlobalRef(env, listener);

    static raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_noop;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_noop;
    cbs.video_resume = cb_noop;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_noop;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_noop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, jmac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    jsize hw_len = (*env)->GetArrayLength(env, jhw);
    jbyte *hw = (*env)->GetByteArrayElements(env, jhw, NULL);

    int result = 0;
    g_raop = raop_init(&cbs);
    if (!g_raop) {
        result = -1;
        goto done;
    }
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1 /* nohold */, mac, keyfile)) {
        free(g_raop);
        g_raop = NULL;
        result = -2;
        goto done;
    }
    raop_set_plist(g_raop, "width", 1920);
    raop_set_plist(g_raop, "height", 1080);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", 30);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    g_port = raop_get_port(g_raop);
    raop_start_httpd(g_raop, &g_port);
    raop_set_port(g_raop, g_port);

    int dnssd_error = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), (const char *) hw, hw_len, 0, &dnssd_error);
    if (!g_dnssd || dnssd_error) {
        result = -3;
        goto done;
    }
    /* Mirroring + audio only: no AirPlay video (bit 0), no HLS (bit 4), no HEVC (bit 42), no legacy pairing (27).
     * With bit 0 off, apps such as TikTok fall back to mirroring the screen instead of handing over a video URL. */
    dnssd_set_airplay_features(g_dnssd, 0, 0);
    dnssd_set_airplay_features(g_dnssd, 4, 0);
    dnssd_set_airplay_features(g_dnssd, 42, 0);
    dnssd_set_airplay_features(g_dnssd, 27, 0);
    raop_set_dnssd(g_raop, g_dnssd);
    if (dnssd_register_raop(g_dnssd, g_port) || dnssd_register_airplay(g_dnssd, g_port)) {
        result = -4;
        goto done;
    }
    result = (int) g_port;

done:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jmac, mac);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    (*env)->ReleaseByteArrayElements(env, jhw, hw, JNI_ABORT);
    if (result < 0) {
        destroy_all(env);
    }
    return result;
}

/* [raopName, raopTxt, airplayName, airplayTxt]; txt is "key=value\n" lines. */
JNIEXPORT jobjectArray JNICALL
Java_io_github_wamior_tvcast_mirror_AirPlayNative_services(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, 4, string_class, NULL);
    if (!g_dnssd) return out;
    (*env)->SetObjectArrayElement(env, out, 0, (*env)->NewStringUTF(env, dnssd_android_name(g_dnssd, DNSSD_SERVICE_RAOP)));
    (*env)->SetObjectArrayElement(env, out, 1, (*env)->NewStringUTF(env, dnssd_android_text(g_dnssd, DNSSD_SERVICE_RAOP)));
    (*env)->SetObjectArrayElement(env, out, 2, (*env)->NewStringUTF(env, dnssd_android_name(g_dnssd, DNSSD_SERVICE_AIRPLAY)));
    (*env)->SetObjectArrayElement(env, out, 3, (*env)->NewStringUTF(env, dnssd_android_text(g_dnssd, DNSSD_SERVICE_AIRPLAY)));
    return out;
}

JNIEXPORT void JNICALL
Java_io_github_wamior_tvcast_mirror_AirPlayNative_stop(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    destroy_all(env);
}

JNIEXPORT void JNICALL
Java_io_github_wamior_tvcast_mirror_AirPlayNative_disconnect(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    if (g_raop) {
        raop_remove_known_connections(g_raop);
    }
}
