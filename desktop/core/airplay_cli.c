/*
 * Desktop AirPlay mirroring helper. Wraps the UxPlay protocol library (GPL-3.0) in a small process:
 * decrypted audio/video frames are written to stdout as [type u8][length u32 LE][payload], the Electron app
 * decodes and shows them. Commands come in on stdin ("disconnect", "quit"); logs go to stderr.
 *
 * Message payloads (all integers little-endian):
 *   1 VIDEO    h265 u8, ntp u64, data...
 *   2 VSIZE    width u32, height u32, h265 u8
 *   3 AFORMAT  ct u8
 *   4 AFRAME   ct u8, ntp u64, data...
 *   5 FLUSH    video u8
 *   6 CONN     open u8
 *   7 VOLUME   dB float32
 *   8 RESET    reason i32
 *   9 SERVICES port u16, then raopName, raopTxt, airplayName, airplayTxt, each NUL-terminated
 */

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "netutils.h"

const char *dnssd_text_name(dnssd_t *dnssd_public, int service);
const char *dnssd_text_text(dnssd_t *dnssd_public, int service);

static pthread_mutex_t out_lock = PTHREAD_MUTEX_INITIALIZER;
static raop_t *g_raop;
static dnssd_t *g_dnssd;

static void put_u32(unsigned char *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xff; }
static void put_u64(unsigned char *p, uint64_t v) { for (int i = 0; i < 8; i++) p[i] = (v >> (8 * i)) & 0xff; }

static void send_msg(uint8_t type, const void *head, uint32_t head_len, const void *body, uint32_t body_len)
{
    unsigned char hdr[5];
    hdr[0] = type;
    put_u32(hdr + 1, head_len + body_len);
    pthread_mutex_lock(&out_lock);
    fwrite(hdr, 1, 5, stdout);
    if (head_len) fwrite(head, 1, head_len, stdout);
    if (body_len) fwrite(body, 1, body_len, stdout);
    fflush(stdout);
    pthread_mutex_unlock(&out_lock);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *d)
{
    (void) cls; (void) ntp;
    unsigned char head[9];
    head[0] = d->ct;
    put_u64(head + 1, d->ntp_time_remote);
    send_msg(4, head, sizeof(head), d->data, (uint32_t) d->data_len);
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *d)
{
    (void) cls; (void) ntp;
    unsigned char head[9];
    head[0] = d->is_h265 ? 1 : 0;
    put_u64(head + 1, d->ntp_time_remote);
    send_msg(1, head, sizeof(head), d->data, (uint32_t) d->data_len);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short ws, unsigned short hs,
                                       unsigned short xo, unsigned short yo, unsigned short w, unsigned short h)
{
    (void) cls; (void) xo; (void) yo; (void) w; (void) h;
    unsigned char p[9];
    put_u32(p, ws);
    put_u32(p + 4, hs);
    p[8] = codec == VIDEO_CODEC_H265;
    send_msg(2, p, sizeof(p), NULL, 0);
    return 0;
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *screen, bool *media,
                                uint64_t *fmt)
{
    (void) cls; (void) spf; (void) screen; (void) media; (void) fmt;
    send_msg(3, ct, 1, NULL, 0);
}

static void flush_msg(unsigned char video) { send_msg(5, &video, 1, NULL, 0); }
static void cb_audio_flush(void *cls) { (void) cls; flush_msg(0); }
static void cb_video_flush(void *cls) { (void) cls; flush_msg(1); }
static void cb_video_reset(void *cls, reset_type_t t) { (void) cls; (void) t; flush_msg(1); }

static void conn_msg(unsigned char open) { send_msg(6, &open, 1, NULL, 0); }
static void cb_conn_init(void *cls) { (void) cls; conn_msg(1); }
static void cb_conn_destroy(void *cls) { (void) cls; conn_msg(0); }

static void cb_conn_reset(void *cls, int reason)
{
    (void) cls;
    unsigned char p[4];
    put_u32(p, (uint32_t) reason);
    send_msg(8, p, 4, NULL, 0);
}

static void cb_audio_set_volume(void *cls, float v)
{
    (void) cls;
    unsigned char p[4];
    memcpy(p, &v, 4);
    send_msg(7, p, 4, NULL, 0);
}

static double cb_audio_set_client_volume(void *cls) { (void) cls; return 0.0; }
static void cb_noop(void *cls) { (void) cls; }
static void cb_report_client_request(void *cls, char *id, char *model, char *name, bool *admit)
{
    (void) cls; (void) id; (void) model; (void) name;
    *admit = true;
}
static bool cb_check_register(void *cls, const char *pk) { (void) cls; (void) pk; return true; }
static void cb_register_client(void *cls, const char *a, const char *b, const char *c)
{
    (void) cls; (void) a; (void) b; (void) c;
}
static void cb_display_pin(void *cls, char *pin) { (void) cls; (void) pin; }
static void cb_export_dacp(void *cls, const char *a, const char *b) { (void) cls; (void) a; (void) b; }
static void cb_meta(void *cls, const void *b, int l) { (void) cls; (void) b; (void) l; }
static void cb_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) { (void) cls; (void) s; (void) c; (void) e; }
static void cb_play(void *cls, const char *l, const float p) { (void) cls; (void) l; (void) p; }
static void cb_scrub(void *cls, const float p) { (void) cls; (void) p; }
static void cb_rate(void *cls, const float r) { (void) cls; (void) r; }
static float cb_playlist_remove(void *cls) { (void) cls; return 0.0f; }
static void cb_playback_info(void *cls, playback_info_t *i) { (void) cls; (void) i; }

static void cb_log(void *cls, int level, const char *msg)
{
    (void) cls;
    if (level <= LOGGER_INFO) fprintf(stderr, "[airplay %d] %s\n", level, msg);
}

static void *stdin_thread(void *arg)
{
    (void) arg;
    char line[128];
    while (fgets(line, sizeof(line), stdin)) {
        if (!strncmp(line, "disconnect", 10)) {
            if (g_raop) raop_remove_known_connections(g_raop);
        } else if (!strncmp(line, "quit", 4)) {
            break;
        }
    }
    /* stdin closed (parent exited) or quit requested */
    exit(0);
    return NULL;
}

int main(int argc, char **argv)
{
    if (argc < 4) {
        fprintf(stderr, "usage: airplay-core <name> <aa:bb:cc:dd:ee:ff> <keyfile>\n");
        return 2;
    }
    const char *name = argv[1];
    const char *mac = argv[2];
    const char *keyfile = argv[3];

#ifdef _WIN32
    _setmode(_fileno(stdout), _O_BINARY);
    _setmode(_fileno(stdin), _O_BINARY);
#endif
    netutils_init();

    char hw[6];
    unsigned v[6];
    if (sscanf(mac, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) {
        fprintf(stderr, "bad mac\n");
        return 2;
    }
    for (int i = 0; i < 6; i++) hw[i] = (char) v[i];

    raop_callbacks_t cbs;
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
    cbs.audio_set_metadata = cb_meta;
    cbs.audio_set_coverart = cb_meta;
    cbs.audio_stop_coverart_rendering = cb_noop;
    cbs.audio_set_progress = cb_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.on_video_play = cb_play;
    cbs.on_video_scrub = cb_scrub;
    cbs.on_video_rate = cb_rate;
    cbs.on_video_stop = cb_noop;
    cbs.on_video_playlist_remove = cb_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_playback_info;

    g_raop = raop_init(&cbs);
    if (!g_raop) return 3;
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1, mac, keyfile)) return 4;
    raop_set_plist(g_raop, "width", 1920);
    raop_set_plist(g_raop, "height", 1080);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", 30);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    unsigned short port = raop_get_port(g_raop);
    raop_start_httpd(g_raop, &port);
    raop_set_port(g_raop, port);

    int err = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw, 6, 0, &err);
    if (!g_dnssd || err) return 5;
    /* mirroring + audio only; see airplay_jni.c */
    dnssd_set_airplay_features(g_dnssd, 0, 0);
    dnssd_set_airplay_features(g_dnssd, 4, 0);
    dnssd_set_airplay_features(g_dnssd, 42, 0);
    dnssd_set_airplay_features(g_dnssd, 27, 0);
    raop_set_dnssd(g_raop, g_dnssd);
    if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) return 6;

    const char *s0 = dnssd_text_name(g_dnssd, DNSSD_SERVICE_RAOP);
    const char *s1 = dnssd_text_text(g_dnssd, DNSSD_SERVICE_RAOP);
    const char *s2 = dnssd_text_name(g_dnssd, DNSSD_SERVICE_AIRPLAY);
    const char *s3 = dnssd_text_text(g_dnssd, DNSSD_SERVICE_AIRPLAY);
    size_t total = 2 + strlen(s0) + strlen(s1) + strlen(s2) + strlen(s3) + 4;
    unsigned char *buf = malloc(total);
    buf[0] = port & 0xff;
    buf[1] = (port >> 8) & 0xff;
    size_t off = 2;
    const char *parts[4] = {s0, s1, s2, s3};
    for (int i = 0; i < 4; i++) {
        size_t n = strlen(parts[i]) + 1;
        memcpy(buf + off, parts[i], n);
        off += n;
    }
    send_msg(9, buf, (uint32_t) off, NULL, 0);
    free(buf);

    pthread_t t;
    pthread_create(&t, NULL, stdin_thread, NULL);
    pthread_join(t, NULL);
    return 0;
}
