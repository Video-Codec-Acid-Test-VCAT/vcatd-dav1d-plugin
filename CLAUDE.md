# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

An Android Library (AAR) implementing an **AV1 software decoder plugin** for VCAT (Video Codec
Acid Test). It wraps the [dav1d](https://code.videolan.org/videolan/dav1d) decoder via JNI and
integrates with the host app through the ExoPlayer 2.x based plugin SPI. Declares MIME
`video/av01`.

## Build

```bash
./gradlew :vcatd-dav1d-plugin:dist   # → app/build/outputs/dist/vcatd-dav1d-plugin.aar
```

dav1d is linked from prebuilt static libs in `app/third_party/dav1d/lib/<abi>/libdav1d.a`.
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

## Key Design Decisions

- ExoPlayer 2.x (2.19.1); `decoder-plugin-api` and `exoplayer-core` are `compileOnly`.
- Package is `com.roncatech.vcatd_dav1d_decoder` (moved out of the dead `com.roncatech.libvcat.*`
  namespace). The JNI symbols in `dav1d_jni.cc` are mangled to match
  (`Java_com_roncatech_vcatd_1dav1d_1decoder_NativeDav1d_*`; underscores in the package escape to
  `_1`), and the plugin-manifest `pluginClass` + Android `namespace` use the same package.
- Native lib renamed to `vcat_dav1d_jni` (was `vcat_jni` in libvcat) to avoid colliding with
  libvcat's own `libvcat_jni.so` in the host.
- dav1d is statically linked from bundled prebuilt `.a` libs (no build-from-source step).
