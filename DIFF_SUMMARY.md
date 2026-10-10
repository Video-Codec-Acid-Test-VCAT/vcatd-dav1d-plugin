# DIFF_SUMMARY

Running summary of notable changes to `vcatd-dav1d-plugin`, newest first.

---

## 2026-10-10 — Port the three dav2d decode-path fixes; a constant 12.7% of frames were being dropped

A 21 h dav1d run on `f1080p-p7-crf48-av1-fd2.mp4` dropped **12.7% of frames in every single
telemetry row** — min 12.5%, max 12.8%, standard deviation **0.03** across 437 rows — at 15%
CPU, with zero skip-to-keyframe events and the device cool. Load varies, scene complexity
varies, the device heats and cools, and the number did not move. That is a fixed ratio, which
means a mechanism, not a decoder struggling.

The accounting ruled out miscounting: 209 iterations x 10,790 frames = 2,255,110 expected
against 2,265,900 counted (the excess being the in-progress iteration), at 99.4% of realtime.
The frames existed and were genuinely discarded.

The cause was that this plugin never received the fixes `vcatd-dav2d-plugin` got during the AV2
alt-ref investigation. All three applied here too.

### One picture per `decode()`, with the surplus stranded

`nativeDequeueFrame` called `dav1d_get_picture()` exactly once. `SimpleDecoder`'s contract is one
output per `decode()` call, but dav1d can have several pictures ready at once, and the surplus
stayed inside the decoder to be handed out on later calls. Output therefore ran permanently
behind input, and the renderer charged the lag as dropped frames — a fixed fraction, independent
of load, with the CPU barely busy, which is exactly the signature observed.

Now drains to `EAGAIN` into a `std::deque<PictureHolder*> ready` on the context and hands out one
per call. Parked pictures are released on flush and close.

### Frame-parallel decoding added output latency

`max_frame_delay` was left at dav1d's default, so with `n_threads = 2` the decoder kept several
frames in flight. Each frame of delay is a frame interval of output latency that the renderer
sees as lateness. Now forced to `1`, using the thread pool for tile parallelism within a frame
instead — matching `vcatd-dav2d-plugin`.

### Declined input was discarded, not retried

A 16-packet cap made `nativeQueueInput` return `-EAGAIN`, and the Java side then drained one
frame and returned **without re-enqueueing the input**. `SimpleDecoder` removes the buffer from
its queue before calling `decode()`, so a declined packet is lost. That is silent bitstream loss,
not back-pressure — the same defect as dav2d's `605640e`. Input is now never refused; the backlog
is unbounded and only logged past a soft threshold of 64 packets.

**Unverified.** This is the diagnosis the evidence points to, built and confirmed present in both
ABIs, but not yet run. If correct, the next dav1d run should show the constant 12.7% collapse to
something small and load-dependent. If it does not, the pipeline theory is wrong and the
`earlyUs` diagnostic already in the vvdec renderer should be added here to measure the lateness
directly.

---

## 2026-10-04 — Hold the native frame rate: stop force-rendering late frames

**Theme:** A benchmark that lets playback slow down rewards the decoder that cannot keep up. This
removes the override that made that possible.

### The defect

```java
protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
    return earlyUs <= 0;          // "render when due or slightly late"
}
```

`DecoderVideoRenderer.processOutputBuffer` runs the force-render check **before** the drop
checks, so any frame that was due *or late* was rendered unconditionally and the
`shouldDropOutputBuffer` override below it (−50 ms) was unreachable dead code. No frame could
ever be dropped: when the decoder fell behind, the timeline stretched instead.

Removed rather than retuned, so ExoPlayer's default applies — force-render only when late **and**
more than 100 ms since the last render, which keeps the stall guard and leaves
`shouldDropOutputBuffer` to decide, with `shouldDropBuffersToKeyframe` (−500 ms) for falling far
behind.

### Why it matters, measured elsewhere

The clearest evidence came from the vvdec plugin, which carried an identical override. On
CPH2625 against a 604.8 s / 29.97 fps 1080p VVC stream, where realtime is 5.95 loops/hour:

```
vvdec 3.0.0   3.8 loops/hour = 0.64x realtime, frames_dropped = 0, 8.00 h battery
vvdec 3.2.0   5.8 loops/hour = 0.97x realtime, frames_dropped = 0, 6.57 h battery
```

3.0.0 was playing in slow motion while reporting no drops, so it looked like the better battery
result despite never delivering the content at its frame rate. That is the failure mode this
removes: with the fix, a decoder that cannot sustain the content reports it as dropped frames at
native rate rather than as a stretched timeline.

The same override was present in all four software decoder plugins, so `video.frames_dropped` had
been structurally zero for every one of them — which is why historical telemetry shows a maximum
of 0 or 1 dropped frames across every run.

### Effect on dav1d specifically — not yet established

The two CPH2625 dav1d runs available both sustained **exactly 10.0 loops/hour** across runs of
very different length (17.72 h and 11.02 h). A decoder-limited rate would be expected to vary
with thermal state, so that stability suggests dav1d was already being paced by renderer
back-pressure on this content and the override was never reached. If so, this change is a
correctness fix for content dav1d *cannot* sustain rather than something that alters the 1080p
AV1 results.

That is inference, not measurement: it depends on the nominal duration of
`av1-1920X1080p29.97fps-fd2`, which was not recorded in telemetry and could not be checked when
this was written. 10.0 loops/hour implies a 360 s clip if the runs were at realtime. Worth
confirming before comparing any dav1d result across this change.

---

## 2026-08-30 — Build dav1d from source; drop the committed prebuilt `.a` libs

**Theme:** Stop carrying a native dependency as a binary in git. With this change all VCAT decoder
plugins (vvdec, dav2d, dav1d) build their native dependency locally.

- **Removed `app/third_party/dav1d/`** — `lib/arm64-v8a/libdav1d.a` (1,141,330 bytes) and
  `lib/armeabi-v7a/libdav1d.a` (979,608 bytes) plus the vendored public headers. A prebuilt in git
  is permanent and adds another copy on every dependency bump.
- **`fetchDav1d` + `buildDav1d_<abi>`** clone dav1d at a pinned release into `app/build/dav1d/src`
  and meson-install a static lib into `app/build/dav1d/install-<abi>`, wired into `preBuild`.
  Overridable with `-Pdav1dRef`, `-Pdav1dRemote`, or `-PDAV1D_SRC` / `DAV1D_SRC` for a local
  checkout.
- dav1d builds with **meson**, not CMake, so the task generates an NDK cross file per ABI
  (`aarch64-linux-android` and `armv7a-linux-androideabi` at minApi 29). meson and ninja join the
  NDK as host prerequisites. `CMakeLists` consumes `DAV1D_INSTALL_<ABI>` instead of the
  `third_party` path, and its missing-library error now names the task that produces it.
- `.gitignore` ignores `*.a` / `*.so` so `git add -A` cannot reintroduce a prebuilt.

### Pinned to 1.5.3, not 1.5.4

1.5.3 is the release whose public headers match the prebuilt this plugin previously shipped, so
moving to a source build did not change decoder behavior. VCAT measures decode performance, so a
dav1d bump should be a deliberate act (`-Pdav1dRef=1.5.4`) rather than a side effect of changing
how the library is obtained. The only public-header delta between the two is an OS/2
`dllexport` branch in `dav1d/common.h`, irrelevant to Android; the bundled copy also carried an
internal `common/attributes.h` differing by an x86-64 `mcmodel` attribute, which the JNI never
includes.

### Verification

- Clean build produces `libdav1d.a` for both ABIs — 1,135,946 and 975,904 bytes vs the old
  prebuilts' 1,141,330 and 979,608 — links each statically into `libvcat_dav1d_jni.so` with 11 JNI
  entry points exported and **no unresolved `dav1d_` symbols**, and emits the AAR with
  `classes.dex` injected. 9 unit tests pass.
- **Not verified on-device:** the AAR built from source has not been exercised against real AV1
  content in the host.

### Outstanding

The prebuilts are gone from the working tree but **remain in the five earlier commits**, where they
were added by the initial commit. Removing them from history requires rewriting those commits
(`git filter-branch --index-filter` over the two `.a` paths) followed by a force-push; until then
the blobs are still reachable in the published history.

---

## 2026-08-15 — AV1-from-IVF confirmed operational end-to-end

- **IVF playback now works end-to-end on-device.** Up to and including the 2026-08-14 work below,
  IVF was **not operational** — only the container framing and AV1 sequence-header parsing were
  implemented and unit-validated, and playback was expected to need further renderer wiring.
- On 2026-08-15, once the host side (`VcatIvfExtractor` + IVF-selectable playlist picker) was in
  place, AV1-from-IVF played back end-to-end through the existing `Dav1dVideoRenderer` with **no
  plugin/renderer changes** — the dav1d renderer accepts IVF-delivered frames as-is.
- Doc-only entry; supersedes the "parsing only / playback deferred" note on the 2026-08-14 entry.

---

## 2026-08-14 — AV1 IVF parsing (`Av1IvfParser`)

- **`Av1IvfParser`** implements **`IvfParserExtension`** (api `1.0.4`): `ivfFourCc()` = `"AV01"`;
  `parseHeader()` peeks the first frame, skips the OBU temporal delimiter, and parses the AV1
  **sequence-header OBU** (from-scratch OBU walker + MSB-first bit-reader + LEB128) for
  width/height/color; sets `VideoConfiguration` + `initializationData` (the seq-header OBU).
  Peek-only (does not consume past the sequence header).
- `VcatDav1dPlugin.getSupportedContainerParsers()` → `[ivfParser]` (stays on `VcatDecoder`; no
  MP4 parser — AV1-in-MP4 uses the standard `av01` path).
- api dep `1.0.2 → 1.0.4`; added test deps (`exoplayer-testutils`).
- **Validated against a real AV1 IVF asset** (`av1_360x640_5s.ivf`, in `src/test/resources`):
  width 360 / height 640, BT.709 limited-range SDR, init data = seq-header OBU, peek-only read.
  **`Av1IvfParserTest` (6) + `Av1IvfIntegrationTest` (3) = 9/9 tests.** The integration test
  reproduces the IVF framing/timebase math (`VcatIvfExtractor` lives in the host repo and isn't
  importable here) and asserts the full field table against the fixture: `DKIF`/`AV01`, timebase
  15360/1, 150 frames, frame 0 @44 (pts 0), frame 1 payload @6868 (pts 33333µs @ 30fps), and the
  parsed `VideoConfiguration` (360×640, BT.709 limited, seq-header OBU init data at offset 2).
- **As of this entry: parsing only** — IVF was not yet operational; end-to-end *playback* was
  expected to need further renderer wiring. _(Resolved 2026-08-15: playback works end-to-end with
  no renderer changes — see the entry above.)_

---

## 2026-08-04 — Migrate to the `VcatDecoder` SPI

- `VcatDav1dPlugin` now implements the non-deprecated **`VcatDecoder`** SPI (was
  `VcatDecoderPlugin`, now deprecated).
- Dropped `getSupportedProfiles()` (not part of `VcatDecoder`). Added
  `getSupportedContainerParsers()` returning empty — AV1-in-MP4 uses the host's standard `av01`
  sample entry / MP4 extractor, so no custom container parser is advertised (IVF support is a
  later step).
- Reports **plugin API `0.1.0`** via the inherited `VcatDecoder.getPluginApiVersion()` default
  (legacy `VcatDecoderPlugin` decoders report `0.0.1`), so the host overlay can distinguish new
  vs. legacy decoders.
- Builds against `com.roncatech.vcat:vcatd-decoder-plugin-api:1.0.2` (`compileOnly`), published
  to Maven Local.
- Verified on-device: `vcat.dav1d` registers and decodes AV1 with no `UnsatisfiedLinkError`.

## Initial — external AV1 (dav1d) decoder plugin

- Extracted dav1d out of `libvcat` into this standalone plugin `.aar`. Package
  `com.roncatech.vcatd_dav1d_decoder`, native lib `libvcat_dav1d_jni.so`, MIME `video/av01`,
  plugin id `vcat.dav1d`.
- dav1d linked from bundled prebuilt static libs (`arm64-v8a`, `armeabi-v7a`) under
  `app/third_party/dav1d`. The `dist` task injects `classes.dex` (D8) into the AAR for the host's
  `DexClassLoader`.
- Dropped into vcat-d's `decoder-plugins/`; bundled by the host's `copyDecoderPluginsToAssets`.
- GPL-3.0-or-later; dav1d is BSD-2-Clause (see `LICENSE` third-party notice).
