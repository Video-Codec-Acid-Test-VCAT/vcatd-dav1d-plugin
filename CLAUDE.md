# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

An Android Library (AAR) implementing an **AV1 software decoder plugin** for VCAT (Video Codec
Acid Test). It wraps the [dav1d](https://code.videolan.org/videolan/dav1d) decoder via JNI and
integrates with the host app through the ExoPlayer 2.x based plugin SPI. Declares MIME
`video/av01`.

## Build

```bash
./gradlew :vcatd-dav1d-plugin:dist   # → app/build/outputs/dist/vcatd-dav1d-plugin.aar (Android)
ios/build_xcframework.sh             # → build/dist/vcatd-dav1d-plugin.xcframework (iOS)
```

This one project builds both platforms. `plugin.json` is the single source of truth for id,
MIME type and the pinned dav1d ref; Gradle reads it, and so does `ios/build_xcframework.sh`.
Gradle only includes `app/`; the iOS script never touches Gradle and needs no Android SDK. Keep
it that way: platform-specific code goes in `app/` (Android) or `ios/` + `native/` (iOS).

dav1d is built from source by the `buildDav1d_<abi>` Gradle task (clone at the pinned release →
meson/ninja → install into `app/build/dav1d/install-<abi>`), so meson and ninja are host
prerequisites alongside the NDK.
The `dist` task injects `classes.dex` into the AAR (required for host DexClassLoader loading).

## Architecture

- **`VcatDav1dPlugin`** — SPI entry (`implements VcatDecoderPlugin`), MIME `video/av01`,
  returns a `Dav1dAv1Provider`.
- **`Dav1dAv1Provider` / `Dav1dAv1RendererProvider`** — availability check + renderer factory.
- **`Dav1dVideoRenderer`** — extends ExoPlayer `DecoderVideoRenderer`.
- **`Dav1dDecoder`** — extends ExoPlayer `SimpleDecoder`; input→native decode→output drain.
- **`Dav1dOutputBuffer`**, **`Dav1dDecoderException`** — buffer/error types.
- **`NativeDav1d`** + **`Dav1dLibrary`** — JNI declarations; loads `libvcat_dav1d_jni.so`.
- **`app/src/main/cpp/dav1d_jni.cc`** — JNI implementation over dav1d.

- **`native/vcat_plugin_dav1d.c`** (iOS) — implements vcat-d-ios's plugin C ABI
  (`plugin-api/vcat_decoder_plugin.h`, API v2) over dav1d, exporting `vcat_plugin_entry`.
  Contains no plugin name: id, MIME type, decoder-name prefix and the manifest come from
  `plugin.json` through the generated `vcat_plugin_manifest.h`, so a fork's plugin can reuse it
  unchanged. Mirrors `dav1d_jni.cc` behavior (default settings, `max_frame_delay` untouched,
  version truncated at '-', `decoder_name` = `vcat-dav1d-<version>`). `end_of_stream` is a no-op:
  dav1d drains via `dav1d_get_picture` on its own.
- **`ios/build_xcframework.sh`** — per slice: dav1d (meson, static), generated identity/manifest
  header, glue, then one dynamic library `vcatd_dav1d.framework` exporting only
  `_vcat_plugin_entry`, with the manifest in `__TEXT,__vcat_manifest` and `VCATDecoderPlugin` in
  its Info.plist; `nm` + `segedit` checks; XCFramework.

## Key Design Decisions

- **iOS plugins are dynamic libraries.** Each has its own symbol namespace, so plugins can't
  collide even when two wrap the same decoder, and nothing needs hiding or renaming. (An earlier
  static-library design had to prelink with an export list and build dav1d with `-fno-common`
  for its common symbols; both are gone.) The one exported name, `vcat_plugin_entry`, is the same
  in every plugin -- the app looks it up per library. The framework name and bundle id come from
  the id (`vcat.dav1d` → `vcatd_dav1d`, `com.roncatech.vcat.dav1d`).
- **Self-describing.** The manifest is compiled in once, from one generated header: returned by
  `manifest_json()` and placed in `__TEXT,__vcat_manifest`. Read raw bytes with
  `segedit -extract`; `otool -s` prints that section as target-endian 32-bit words.

- ExoPlayer 2.x (2.19.1); `decoder-plugin-api` and `exoplayer-core` are `compileOnly`.
- Package is `com.roncatech.vcatd_dav1d_decoder` (moved out of the dead `com.roncatech.libvcat.*`
  namespace). The JNI symbols in `dav1d_jni.cc` are mangled to match
  (`Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_*`; underscores in the package escape to
  `_1`), and the plugin-manifest `pluginClass` + Android `namespace` use the same package.
- Native lib renamed to `vcat_dav1d_jni` (was `vcat_jni` in libvcat) to avoid colliding with
  libvcat's own `libvcat_jni.so` in the host.
- dav1d is **built from source**, not carried as a committed prebuilt `.a`. A binary in git is
  permanent and grows the repo on every dependency bump; the source pin in `app/build.gradle`
  records exactly what shipped instead. All VCAT decoder plugins follow this arrangement (vvdec,
  dav2d, dav1d) — dav1d and dav2d use meson rather than CMake, so their tasks generate an NDK
  cross file per ABI.
- The pin is **1.5.3**, the release whose public headers match the prebuilt previously shipped, so
  the switch to a source build left decoder behavior unchanged. VCAT measures decoder performance,
  so bumping dav1d should be deliberate (`-Pdav1dRef=`), not incidental.
