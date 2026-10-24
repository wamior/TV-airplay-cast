/*
 * Platform-independent replacement for UxPlay's lib/dns_sd/dns_sd.c.
 *
 * Nothing is registered here: the TXT records are only rendered as "key=value\n" text so that the Kotlin side can
 * publish them through NsdManager (Android's own mDNS responder).
 *
 * GPL-3.0-or-later, like the rest of this directory (see LICENSE in the repository root).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "utils.h"

#define TEXT_MAX 1536
#define NAME_MAX_LEN 192

typedef struct dnssd_private_s {
    char raop_text[TEXT_MAX];
    char airplay_text[TEXT_MAX];
    char raop_name[NAME_MAX_LEN];
    char airplay_name[NAME_MAX_LEN];
} dnssd_private_t;

void *dnssd_private_init(dnssd_t *dnssd_public, int *error)
{
    (void) dnssd_public;
    (void) error;
    return calloc(1, sizeof(dnssd_private_t));
}

void dnssd_private_destroy(void *dnssd_private)
{
    free(dnssd_private);
}

void dnssd_error_text(int *error, const char *appname)
{
    (void) error;
    (void) appname;
}

static void put(char *buf, const char *key, const char *value)
{
    size_t used = strlen(buf);
    snprintf(buf + used, TEXT_MAX - used, "%s=%s\n", key, value);
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port)
{
    (void) port;
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    char features[24];
    char servname[NAME_MAX_LEN];
    char *t = p->raop_text;

    t[0] = '\0';
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);
    put(t, "ch", RAOP_CH);
    put(t, "cn", RAOP_CN);
    put(t, "da", RAOP_DA);
    put(t, "et", RAOP_ET);
    put(t, "vv", RAOP_VV);
    put(t, "ft", features);
    put(t, "am", GLOBAL_MODEL);
    put(t, "md", RAOP_MD);
    put(t, "rhd", RAOP_RHD);
    put(t, "pw", "false");
    put(t, "sf", RAOP_SF);
    put(t, "sr", RAOP_SR);
    put(t, "ss", RAOP_SS);
    put(t, "sv", RAOP_SV);
    put(t, "tp", RAOP_TP);
    put(t, "txtvers", RAOP_TXTVERS);
    put(t, "vs", RAOP_VS);
    put(t, "vn", RAOP_VN);
    put(t, "pk", dnssd_public->pk ? dnssd_public->pk : "");

    if (utils_hwaddr_raop(servname, sizeof(servname), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    strncat(servname, "@", sizeof(servname) - strlen(servname) - 1);
    strncat(servname, dnssd_public->name, sizeof(servname) - strlen(servname) - 1);
    snprintf(p->raop_name, sizeof(p->raop_name), "%s", servname);
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port)
{
    (void) port;
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    char features[24];
    char device_id[3 * MAX_HWADDR_LEN];
    char *t = p->airplay_text;

    t[0] = '\0';
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    put(t, "deviceid", device_id);
    put(t, "features", features);
    put(t, "pw", "false");
    put(t, "flags", "0x4");
    put(t, "model", GLOBAL_MODEL);
    put(t, "pk", dnssd_public->pk ? dnssd_public->pk : "");
    put(t, "pi", AIRPLAY_PI);
    put(t, "srcvers", AIRPLAY_SRCVERS);
    put(t, "vv", AIRPLAY_VV);
    snprintf(p->airplay_name, sizeof(p->airplay_name), "%s", dnssd_public->name);
    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd_public) { (void) dnssd_public; }

void dnssd_unregister_airplay(dnssd_t *dnssd_public) { (void) dnssd_public; }

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length)
{
    (void) dnssd_public;
    *length = 0;
    return NULL;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length)
{
    (void) dnssd_public;
    *length = 0;
    return NULL;
}

int dnssd_get_service_fd(dnssd_t *dnssd_public, int service)
{
    (void) dnssd_public;
    (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd_public, int service)
{
    (void) dnssd_public;
    (void) service;
    return 0;
}

/* Accessors for the JNI layer. */
const char *dnssd_text_name(dnssd_t *dnssd_public, int service)
{
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    return service == DNSSD_SERVICE_RAOP ? p->raop_name : p->airplay_name;
}

const char *dnssd_text_text(dnssd_t *dnssd_public, int service)
{
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    return service == DNSSD_SERVICE_RAOP ? p->raop_text : p->airplay_text;
}
