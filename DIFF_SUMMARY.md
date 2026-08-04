# DIFF_SUMMARY

Running summary of notable changes to `vcatd-dav1d-plugin`, newest first.

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
