/*
* vcat-d (Video Codec Acid Test)
*
* SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
* SPDX-License-Identifier: GPL-3.0-or-later
*
* This file is part of vcat-d.
*
* vcat-d is free software: you can redistribute it and/or modify it
* under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* vcat-d is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
*
* For proprietary/commercial use cases, a written GPL-3.0 waiver or
* a separate commercial license is required from RoncaTech LLC.
* All vcat-d artwork is owned exclusively by RoncaTech LLC. Use of vcat-d logos and artwork is permitted for the purpose of discussing, documenting, or promoting vcat-d itself. Any other use requires prior written permission from RoncaTech LLC.
* Contact: legal@roncatech.com • https://roncatech.com/legal
*/

// extension-dav1d/src/main/cpp/dav1d_jni.cc
//
// Minimal JNI bridge for dav1d with a scalar I420 -> RGBA converter.
// Writes RGBA (R,G,B,A) into WINDOW_FORMAT_RGBA_8888 buffers and surfaces
// fatal decoder errors to Java to avoid sticky stalls.

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <algorithm>
#include <errno.h>
#include <mutex>

extern "C" {
#include "dav1d/dav1d.h"
#include "dav1d/data.h"
#include "dav1d/picture.h"
}

#define LOG_TAG "dav1d_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Soft threshold for logging only; input is never refused. See nativeQueueInput.
static constexpr size_t kPendingBacklogWarn = 64;

struct InputNode {
    Dav1dData data;        // dav1d takes ownership when dav1d_send_data == 0
    int64_t pts_us = -1;
    InputNode() = default;
    InputNode(const InputNode&) = delete;
    InputNode& operator=(const InputNode&) = delete;
};

struct PictureHolder;   // defined below; NativeCtx only holds pointers

struct NativeCtx {
    Dav1dContext* c = nullptr;
    std::deque<InputNode*> pending;

    /**
     * Pictures dav1d has produced but the caller has not collected yet.
     *
     * SimpleDecoder's contract is one output per decode() call, but dav1d can have several
     * pictures ready at once. Taking only one per call leaves the surplus inside the decoder,
     * so output runs permanently behind input and every late frame is dropped by the renderer --
     * a fixed fraction, independent of load, with the CPU barely busy. nativeDequeueFrame now
     * drains to EAGAIN, parks the surplus here, and hands out one per call.
     */
    std::deque<PictureHolder*> ready;

    // Counters/telemetry
    uint32_t pkts_in_total = 0;
    uint32_t pkts_send_ok = 0;
    uint32_t pkts_send_eagain = 0;
    uint32_t pkts_send_err = 0;
    uint32_t pics_out = 0;
    uint32_t pics_eagain = 0;
    uint32_t dropped_at_flush = 0;

    int64_t last_in_pts  = -1;  // us
    int64_t last_out_pts = -1;  // us

    // Simple stats
    int num_frames_decoded   = 0; // accepted by dav1d_send_data
    int num_frames_displayed = 0; // nativeRenderToSurface posted
    int num_frames_not_decoded = 0; // dropped before send

    // --- Cached window & geometry ---
    ANativeWindow* win = nullptr;     // +1 ref when set, release on unset/destroy
    int win_w = 0;
    int win_h = 0;
    int win_fmt = 0;

    std::mutex win_mtx;
    bool eos = false;
};

struct PictureHolder {
    Dav1dPicture pic; // must be unref'd with dav1d_picture_unref()
};

// ------------------- Pending queue helpers -------------------

/** Unrefs and frees every picture parked in the ready queue. */
static void release_all_ready(NativeCtx* ctx) {
    while (!ctx->ready.empty()) {
        PictureHolder* h = ctx->ready.front();
        ctx->ready.pop_front();
        dav1d_picture_unref(&h->pic);
        delete h;
    }
}

static void release_all_pending(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        ctx->pending.pop_front();
        dav1d_data_unref(&n->data);
        delete n;
        ctx->num_frames_not_decoded++;
    }
}

static void flush_pending_to_decoder(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        int rc = dav1d_send_data(ctx->c, &n->data);
        if (rc == 0) {
            ctx->pkts_send_ok++;
            ctx->num_frames_decoded++;
            ctx->last_in_pts = n->data.m.timestamp;
            ctx->pending.pop_front();     // dav1d now owns the data
            delete n;                     // do not dav1d_data_unref here
        } else if (rc == -EAGAIN) {
            ctx->pkts_send_eagain++;
            break; // need to drain pictures first
        } else {
            ctx->pkts_send_err++;
            LOGE("dav1d_send_data fatal: %d (dropping packet)", rc);
            dav1d_data_unref(&n->data);   // drop & free
            ctx->pending.pop_front();
            delete n;
        }
    }
}

// --------------------------- JNI API ---------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeCreate(
        JNIEnv* /*env*/, jclass /*clazz*/, jint frameThreads, jint /*tileThreads*/) {
    auto* ctx = new NativeCtx();

    Dav1dSettings s;
    dav1d_default_settings(&s);
    s.n_threads = (frameThreads > 0) ? frameThreads : 1;
    // max_frame_delay is deliberately left at dav1d's default. Forcing it to 1 -- as
    // vcatd-dav2d-plugin does -- was tried and measured far worse: 42% dropped against 3% in the
    // same opening 30 s, with CPU at ~13% (about one core). Low delay confines the thread pool to
    // tile parallelism inside a single frame, so on a stream with few tiles the second thread has
    // nothing to do and throughput collapses. dav2d needs that setting for an alt-ref
    // correctness bug that dav1d does not have, so the cost buys nothing here.

    int rc = dav1d_open(&ctx->c, &s);
    if (rc != 0) {
        LOGE("dav1d_open failed: %d", rc);
        delete ctx;
        return 0;
    }
    LOGI("dav1d created (threads=%d)", s.n_threads);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeFlush(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx) return;
ctx->dropped_at_flush += static_cast<uint32_t>(ctx->pending.size());
release_all_pending(ctx);
release_all_ready(ctx);
dav1d_flush(ctx->c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeClose(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx) return;
release_all_pending(ctx);
release_all_ready(ctx);
if (ctx->c) {
dav1d_close(&ctx->c);
ctx->c = nullptr;
}
LOGD("CLOSE stats: decoded=%d displayed=%d not_decoded=%d send_ok=%u eagain=%u err=%u pics_out=%u dropped_at_flush=%u",
     ctx->num_frames_decoded, ctx->num_frames_displayed, ctx->num_frames_not_decoded,
     ctx->pkts_send_ok, ctx->pkts_send_eagain, ctx->pkts_send_err, ctx->pics_out, ctx->dropped_at_flush);
delete ctx;
}

extern "C" JNIEXPORT jint JNICALL
        Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeQueueInput(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
jobject byteBuffer, jint offset, jint size, jlong ptsUs) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx || !ctx->c || !byteBuffer || size <= 0) return -EINVAL;

// Never refuse input. SimpleDecoder removes the buffer from its queue before calling
// decode(), so a declined packet is discarded rather than retried -- silent bitstream loss,
// not back-pressure. The backlog is unbounded and only logged past a soft threshold.
if (ctx->pending.size() == kPendingBacklogWarn) {
LOGW("input backlog at %zu packets; decoder is not keeping up", ctx->pending.size());
}

uint8_t* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
if (!src) {
LOGE("Input buffer is not a direct ByteBuffer");
return -EINVAL;
}
src += offset;

auto* node = new InputNode();
uint8_t* dst = dav1d_data_create(&node->data, static_cast<size_t>(size));
if (!dst) {
LOGE("dav1d_data_create returned null");
delete node;
return -ENOMEM;
}

std::memcpy(dst, src, static_cast<size_t>(size));
node->data.m.timestamp = static_cast<int64_t>(ptsUs);
node->pts_us = static_cast<int64_t>(ptsUs);

ctx->pkts_in_total++;
ctx->pending.push_back(node);

flush_pending_to_decoder(ctx);
return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
        Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeHasCapacity(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx || !ctx->c) return JNI_FALSE;
return JNI_TRUE;   // input is never refused; see nativeQueueInput
}

extern "C" JNIEXPORT jlong JNICALL
        Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeDequeueFrame(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
jintArray outWH, jlongArray outPtsUs) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx || !ctx->c) return 0;

flush_pending_to_decoder(ctx);

// Drain every picture dav1d has ready, not just one. Pictures left inside the decoder are
// handed out on later calls, so output drifts permanently behind input and the renderer
// charges the difference as dropped frames.
while (true) {
    Dav1dPicture pic;
    std::memset(&pic, 0, sizeof(pic));
    int rc = dav1d_get_picture(ctx->c, &pic);
    if (rc == -EAGAIN) {
        ctx->pics_eagain++;
        break;
    }
    if (rc < 0) {
        LOGE("dav1d_get_picture failed: %d", rc);
        // Only surface the error if nothing is parked; a picture already in hand is still good
        // and the error will be reported again on the next call.
        if (ctx->ready.empty() && outWH && env->GetArrayLength(outWH) >= 2) {
            jint wh_err[2] = { -1, rc };
            env->SetIntArrayRegion(outWH, 0, 2, wh_err);
            return 0;
        }
        break;
    }
    auto* h = new PictureHolder();
    h->pic = pic;
    ctx->ready.push_back(h);
}

if (ctx->ready.empty()) return 0;

PictureHolder* hold = ctx->ready.front();
ctx->ready.pop_front();

jint wh[2] = { static_cast<jint>(hold->pic.p.w), static_cast<jint>(hold->pic.p.h) };
env->SetIntArrayRegion(outWH, 0, 2, wh);

jlong pts[1] = { static_cast<jlong>(hold->pic.m.timestamp) };
env->SetLongArrayRegion(outPtsUs, 0, 1, pts);

ctx->pics_out++;
ctx->last_out_pts = hold->pic.m.timestamp;

return reinterpret_cast<jlong>(hold);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeSetSurface(
        JNIEnv* env, jclass, jlong handle, jobject surface) {

auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx) return;
std::lock_guard<std::mutex> lk(ctx->win_mtx);

if (ctx->win) {
ANativeWindow_release(ctx->win);
ctx->win = nullptr;
ctx->win_w = ctx->win_h = ctx->win_fmt = 0;
}
if (surface) {
ctx->win = ANativeWindow_fromSurface(env, surface);
}
}

static inline void ensureWindowConfigured(NativeCtx* ctx, int w, int h, int fmt) {
    if (!ctx->win) return;
    if (ctx->win_w != w || ctx->win_h != h || ctx->win_fmt != fmt) {
        ANativeWindow_setBuffersGeometry(ctx->win, w, h, fmt);
        ctx->win_w = w; ctx->win_h = h; ctx->win_fmt = fmt;
    }
}

extern "C" JNIEXPORT jint JNICALL
        Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeRenderToSurface(
        JNIEnv*, jclass, jlong handle, jlong nativePic, jobject /*unused*/) {

auto* ctx  = reinterpret_cast<NativeCtx*>(handle);
auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
if (!ctx || !ctx->c || !hold) return -EINVAL;

const Dav1dPicture& pic = hold->pic;
if (pic.p.bpc != 8 || pic.p.layout != DAV1D_PIXEL_LAYOUT_I420) return -ENOSYS;

const int w = pic.p.w, h = pic.p.h;
const int YV12 = 0x32315659; // 'YV12'

std::lock_guard<std::mutex> lk(ctx->win_mtx);
if (!ctx->win) return -ENODEV;

ensureWindowConfigured(ctx, w, h, YV12);

ANativeWindow_Buffer buf;
if (ANativeWindow_lock(ctx->win, &buf, nullptr) != 0) return -1;

auto* dstY = static_cast<uint8_t*>(buf.bits);
const int dstYStride = buf.stride;
const int dstUVStride = ((dstYStride >> 1) + 15) & ~15;
const int uvW = (w + 1) / 2, uvH = (h + 1) / 2;
uint8_t* dstV = dstY + dstYStride * h;
uint8_t* dstU = dstV + dstUVStride * uvH;

const uint8_t* srcY = (const uint8_t*)pic.data[0];
const uint8_t* srcU = (const uint8_t*)pic.data[1];
const uint8_t* srcV = (const uint8_t*)pic.data[2];
const int srcYStride  = (int)pic.stride[0];
const int srcUVStride = (int)pic.stride[1];

auto copyPlanePad = [](const uint8_t* s, int ss, uint8_t* d, int ds, int rb, int rows) {
    if (ds == rb) {
        for (int j = 0; j < rows; ++j) { memcpy(d, s, rb); s += ss; d += ds; }
    } else {
        const int pad = ds - rb;
        for (int j = 0; j < rows; ++j) {
            memcpy(d, s, rb);
            memset(d + rb, 0, pad);
            s += ss; d += ds;
        }
    }
};

copyPlanePad(srcY, srcYStride,  dstY, dstYStride,  w,   h);
copyPlanePad(srcV, srcUVStride, dstV, dstUVStride, uvW, uvH); // V first in YV12
copyPlanePad(srcU, srcUVStride, dstU, dstUVStride, uvW, uvH);

ANativeWindow_unlockAndPost(ctx->win);
ctx->num_frames_displayed++;
return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeReleasePicture(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong /*handle*/, jlong nativePic) {
auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
if (!hold) return;
dav1d_picture_unref(&hold->pic);
delete hold;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_dav1dGetVersion(JNIEnv* env, jclass /*clazz*/) {
    const char *src = dav1d_version();
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%s", src);
    char *token = strtok(buffer, "-");
    return env->NewStringUTF(token);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_nativeSignalEof(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx || !ctx->c) return;

dav1d_send_data(ctx->c, nullptr);
ctx->eos = true;
}
