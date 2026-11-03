# vcatd-dav1d-plugin

AV1 software decoder plugin for [VCAT (Video Codec Acid Test)](https://github.com/Video-Codec-Acid-Test-VCAT/vcat-d).

Wraps [dav1d](https://code.videolan.org/videolan/dav1d) (VideoLAN AV1 decoder) as a VCAT decoder
plugin. Declares MIME type `video/av01`.

One project, two outputs: an **Android `.aar`** for vcat (Gradle, `app/`) and an **iOS
`.xcframework`** for vcat-d-ios (`ios/`). Both are built from the same `plugin.json` (id, MIME
type, pinned dav1d source), and each build ignores the other platform's directory.

| | Android | iOS |
|---|---|---|
| Build | `./gradlew :vcatd-dav1d-plugin:dist` | `ios/build_xcframework.sh` |
| Output | `app/build/outputs/dist/vcatd-dav1d-plugin.aar` | `build/dist/vcatd-dav1d-plugin.xcframework` |
| Drop into | `vcat/decoder-plugins/` | `vcat-d-ios/decoder-plugins/` |
| Decoder glue | JNI (`app/src/main/cpp/dav1d_jni.cc`) | C ABI shim (`native/vcat_plugin_dav1d.c`) |

## Prerequisites

- JDK 17+
- Android SDK with NDK **27.0.12077973** and CMake 3.22+
- **meson** and **ninja** (dav1d's build system)

dav1d is **built from source**, not bundled: the Gradle build clones it at a pinned release into
`app/build/dav1d/src` and meson-installs a static `libdav1d.a` per ABI into
`app/build/dav1d/install-<abi>/`. Nothing prebuilt is carried in the repo.

The pin is **1.5.3**, set in `plugin.json` and read by both the Gradle and the iOS build. It is
the release matching the prebuilt this plugin previously shipped, so switching to a source build
did not change decoder behavior. Since VCAT measures decoder performance, moving it is a
deliberate act: edit `plugin.json`, or for a one-off:

```bash
./gradlew :vcatd-dav1d-plugin:dist -Pdav1dRef=1.5.4
./gradlew :vcatd-dav1d-plugin:dist -PDAV1D_SRC=/path/to/dav1d
```

## Build

```bash
./gradlew :vcatd-dav1d-plugin:dist
```

Output: `app/build/outputs/dist/vcatd-dav1d-plugin.aar`

The `dist` task post-processes the AAR: it extracts `classes.jar`, runs D8 to produce
`classes.dex` (minApi 29), then repacks the AAR with the DEX injected — required for plugin
loading by the host app.

## Using the plugin with VCAT

Copy the AAR into VCAT's `decoder-plugins/` folder before building the app:

```bash
cp app/build/outputs/dist/vcatd-dav1d-plugin.aar ../vcat-d/decoder-plugins/
```

## iOS

Prerequisites: Xcode, meson and ninja, and vcat-d-ios checked out next to this repo (for
`plugin-api/vcat_decoder_plugin.h`; override with `VCAT_PLUGIN_API_DIR`). No Android SDK needed.

```bash
ios/build_xcframework.sh
cp -R build/dist/vcatd-dav1d-plugin.xcframework ../vcat-d-ios/decoder-plugins/
# then, in vcat-d-ios: xcodegen generate
```

The `.xcframework` is the single drop-in, with its manifest inside (`vcat-plugin.json`), as the
`.aar` carries `assets/plugin-manifest.json`. Per slice (device, simulator; arm64) the script
builds dav1d at the pin, compiles `native/vcat_plugin_dav1d.c`, and prelinks both into one
object that **exports only `_vcatd_dav1d_plugin_entry`**. Every dav1d symbol is hidden, so
the plugin can share one app binary with any other plugin, even one wrapping another dav1d.
The namespace (`vcatd_dav1d_`) is derived from the id in `plugin.json`; the build fails if anything
else is exported. Overrides: `DAV1D_REF`, `DAV1D_REMOTE`, `DAV1D_SRC`.

## Dependencies

| Dependency | License | Source |
|---|---|---|
| dav1d | BSD-2-Clause | [videolan/dav1d](https://code.videolan.org/videolan/dav1d) |
| vcatd-decoder-plugin-api | GPL-3.0-or-later | Maven Central |

## License

GPL-3.0-or-later. For commercial licensing contact: legal@roncatech.com
