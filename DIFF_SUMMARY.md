# DIFF_SUMMARY

Running summary of notable changes to `vcatd-dav1d-plugin`, newest first.

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
