/*
 * vcat_plugin_dav1d.c -- implements the VCAT decoder plugin C ABI (vcat_decoder_plugin.h) over
 * dav1d. Built into the iOS plugin's dynamic library by ios/build_xcframework.sh; the Android
 * build still goes through app/src/main/cpp/dav1d_jni.cc.
 *
 * Contains no plugin name. The identity comes from plugin.json through vcat_plugin_manifest.h,
 * which the build generates:
 *   VCAT_PLUGIN_ID            e.g. "vcat.dav1d"
 *   VCAT_PLUGIN_MIME          e.g. "video/av01"
 *   VCAT_DECODER_NAME_PREFIX  e.g. "vcat-dav1d-"
 *   vcat_plugin_manifest[]    the manifest JSON, placed in the __TEXT,__vcat_manifest section
 * so a plugin wrapping a fork of dav1d can reuse this file unchanged with its own plugin.json.
 *
 * Behavior matches dav1d_jni.cc where the two overlap: dav1d's default settings,
 * max_frame_delay left at its default (see the comment there for the measurements behind that),
 * and the version string truncated at the first '-'.
 */
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <dav1d/dav1d.h>

#include "vcat_decoder_plugin.h"
#include "vcat_plugin_manifest.h"

#if !defined(VCAT_PLUGIN_ID) || !defined(VCAT_PLUGIN_MIME) || !defined(VCAT_DECODER_NAME_PREFIX)
#error "vcat_plugin_manifest.h is generated from plugin.json by the build"
#endif

typedef struct {
    Dav1dContext *c;
    Dav1dData pending; /* input not yet accepted by dav1d (see send_data's AGAIN contract) */
} shim_ctx;

static pthread_once_t g_strings_once = PTHREAD_ONCE_INIT;
static char g_version[32];
static char g_decoder_name[96];

static void init_strings(void) {
    /* Same as dav1d_jni.cc's dav1dGetVersion: "1.5.3-0-gabcdef" -> "1.5.3". */
    snprintf(g_version, sizeof(g_version), "%s", dav1d_version());
    char *dash = strchr(g_version, '-');
    if (dash) *dash = '\0';
    snprintf(g_decoder_name, sizeof(g_decoder_name), "%s%s", VCAT_DECODER_NAME_PREFIX, g_version);
}

static const char *plugin_version(void) {
    pthread_once(&g_strings_once, init_strings);
    return g_version;
}

static const char *plugin_decoder_name(void) {
    pthread_once(&g_strings_once, init_strings);
    return g_decoder_name;
}

static const char *plugin_manifest_json(void) {
    return vcat_plugin_manifest;
}

static int plugin_open(const vcat_decoder_config *config, void **out_ctx) {
    if (!out_ctx) return VCAT_PLUGIN_ERROR;
    *out_ctx = NULL;

    shim_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return VCAT_PLUGIN_ERROR;

    Dav1dSettings s;
    dav1d_default_settings(&s);
    if (config && config->threads > 0) s.n_threads = config->threads;

    int rc = dav1d_open(&ctx->c, &s);
    if (rc < 0) {
        free(ctx);
        return rc;
    }
    *out_ctx = ctx;
    return VCAT_PLUGIN_OK;
}

static int plugin_send_data(void *opaque, const uint8_t *data, size_t size, int64_t pts) {
    shim_ctx *ctx = opaque;
    if (!ctx) return VCAT_PLUGIN_ERROR;

    if (data && size > 0) {
        /* New input while old input is still pending breaks the AGAIN contract. */
        if (ctx->pending.sz > 0) return VCAT_PLUGIN_ERROR;
        uint8_t *buf = dav1d_data_create(&ctx->pending, size);
        if (!buf) return VCAT_PLUGIN_ERROR;
        memcpy(buf, data, size);
        ctx->pending.m.timestamp = pts;
    }
    if (ctx->pending.sz == 0) return VCAT_PLUGIN_OK;

    int rc = dav1d_send_data(ctx->c, &ctx->pending);
    if (rc < 0 && rc != DAV1D_ERR(EAGAIN)) {
        dav1d_data_unref(&ctx->pending);
        return rc;
    }
    return ctx->pending.sz > 0 ? VCAT_PLUGIN_AGAIN : VCAT_PLUGIN_OK;
}

static int32_t map_layout(enum Dav1dPixelLayout layout) {
    switch (layout) {
        case DAV1D_PIXEL_LAYOUT_I400: return VCAT_PIXEL_LAYOUT_I400;
        case DAV1D_PIXEL_LAYOUT_I420: return VCAT_PIXEL_LAYOUT_I420;
        case DAV1D_PIXEL_LAYOUT_I422: return VCAT_PIXEL_LAYOUT_I422;
        case DAV1D_PIXEL_LAYOUT_I444: return VCAT_PIXEL_LAYOUT_I444;
    }
    return VCAT_PIXEL_LAYOUT_I420;
}

static int plugin_get_picture(void *opaque, vcat_picture *out) {
    shim_ctx *ctx = opaque;
    if (!ctx || !out) return VCAT_PLUGIN_ERROR;

    Dav1dPicture *pic = calloc(1, sizeof(*pic));
    if (!pic) return VCAT_PLUGIN_ERROR;

    int rc = dav1d_get_picture(ctx->c, pic);
    if (rc < 0) {
        free(pic);
        return rc == DAV1D_ERR(EAGAIN) ? VCAT_PLUGIN_AGAIN : rc;
    }

    memset(out, 0, sizeof(*out));
    out->width = pic->p.w;
    out->height = pic->p.h;
    out->bit_depth = pic->p.bpc;
    out->layout = map_layout(pic->p.layout);
    out->planes[0] = pic->data[0];
    out->strides[0] = pic->stride[0];
    if (pic->p.layout != DAV1D_PIXEL_LAYOUT_I400) {
        /* dav1d guarantees U and V share one stride. */
        out->planes[1] = pic->data[1];
        out->planes[2] = pic->data[2];
        out->strides[1] = pic->stride[1];
        out->strides[2] = pic->stride[1];
    }
    out->pts = pic->m.timestamp;
    out->opaque = pic;
    return VCAT_PLUGIN_OK;
}

static void plugin_release_picture(void *opaque, vcat_picture *picture) {
    (void)opaque;
    if (!picture || !picture->opaque) return;
    Dav1dPicture *pic = picture->opaque;
    dav1d_picture_unref(pic);
    free(pic);
    picture->opaque = NULL;
}

/* dav1d needs no explicit signal: with no more input, dav1d_get_picture keeps returning the
 * pictures it still holds until EAGAIN. */
static void plugin_end_of_stream(void *opaque) {
    (void)opaque;
}

static void plugin_reset(void *opaque) {
    shim_ctx *ctx = opaque;
    if (!ctx) return;
    dav1d_data_unref(&ctx->pending);
    dav1d_flush(ctx->c);
}

static void plugin_close(void *opaque) {
    shim_ctx *ctx = opaque;
    if (!ctx) return;
    dav1d_data_unref(&ctx->pending);
    dav1d_close(&ctx->c);
    free(ctx);
}

static const vcat_decoder_plugin g_descriptor = {
    .api_version = VCAT_PLUGIN_API_VERSION,
    .id = VCAT_PLUGIN_ID,
    .mime_type = VCAT_PLUGIN_MIME,
    .decoder_name = plugin_decoder_name,
    .version = plugin_version,
    .manifest_json = plugin_manifest_json,
    .open = plugin_open,
    .send_data = plugin_send_data,
    .get_picture = plugin_get_picture,
    .release_picture = plugin_release_picture,
    .end_of_stream = plugin_end_of_stream,
    .reset = plugin_reset,
    .close = plugin_close,
};

/* The plugin's only exported symbol -- the same name in every plugin. */
__attribute__((visibility("default")))
const vcat_decoder_plugin *vcat_plugin_entry(void) {
    return &g_descriptor;
}
