# vcatd-dav1d-plugin

AV1 software decoder plugin for [VCAT (Video Codec Acid Test)](https://github.com/Video-Codec-Acid-Test-VCAT/vcat-d).

Wraps [dav1d](https://code.videolan.org/videolan/dav1d) (VideoLAN AV1 decoder) as a VCAT decoder
plugin. Declares MIME type `video/av01`.

One project, two outputs: an **Android `.aar`** for vcat (Gradle, `app/`) and an **iOS
`.xcframework`** for vcat-d-ios (`ios/` + `native/`). Both are built from the same
`plugin.json` (id, MIME type, pinned dav1d source), and each build ignores the other platform's
directories. Build instructions: [Android](#android), [iOS](#ios).

| | Android | iOS |
|---|---|---|
| Build | `./gradlew :vcatd-dav1d-plugin:dist` | `ios/build_xcframework.sh` |
| Output | `app/build/outputs/dist/vcatd-dav1d-plugin.aar` | `build/dist/vcatd-dav1d-plugin.xcframework` |
| Drop into | `vcat/decoder-plugins/` | `vcat-d-ios/decoder-plugins/` |
| Decoder glue | JNI (`app/src/main/cpp/dav1d_jni.cc`) | C ABI shim (`native/vcat_plugin_dav1d.c`) |

## Pinned dav1d source

dav1d is **built from source**, not bundled. Both builds clone it at the release pinned in
`plugin.json` and build a static `libdav1d.a` per target. Nothing prebuilt is carried in the
repo.

The pin is **1.5.3**, the release matching the prebuilt this plugin previously shipped, so
switching to a source build did not change decoder behavior. Since VCAT measures decoder
performance, moving it is a deliberate act: edit `source.ref` in `plugin.json`, which changes
both platforms. Each build also takes a one-off override (below).

## Android

### Prerequisites

- JDK 17+
- Android SDK with NDK **27.0.12077973** and CMake 3.22+
- **meson** and **ninja** (dav1d's build system)

### Build

```bash
./gradlew :vcatd-dav1d-plugin:dist
```

Output: `app/build/outputs/dist/vcatd-dav1d-plugin.aar`

dav1d is cloned into `app/build/dav1d/src` and installed per ABI into
`app/build/dav1d/install-<abi>/`. The `dist` task post-processes the AAR: it extracts
`classes.jar`, runs D8 to produce `classes.dex` (minApi 29), then repacks the AAR with the DEX
injected, which the host app needs to load the plugin.

One-off overrides:

```bash
./gradlew :vcatd-dav1d-plugin:dist -Pdav1dRef=1.5.4
./gradlew :vcatd-dav1d-plugin:dist -PDAV1D_SRC=/path/to/dav1d
```

### Using it with VCAT

Copy the AAR into VCAT's `decoder-plugins/` folder before building the app:

```bash
cp app/build/outputs/dist/vcatd-dav1d-plugin.aar ../vcat-d/decoder-plugins/
```

## iOS

The iOS build is a shell script. It doesn't use Gradle and needs no Android SDK or NDK, and
Gradle in turn ignores `ios/` and `native/`.

### Prerequisites

- **macOS with full Xcode**, including the iOS and iOS Simulator SDKs. The Command Line Tools
  alone are not enough. `xcode-select` must point at Xcode:
  ```bash
  xcode-select -p          # should print .../Xcode*.app/Contents/Developer
  sudo xcode-select -s /Applications/Xcode.app/Contents/Developer   # adjust to your Xcode's name
  ```
  Verified with Xcode 27.2.
- **meson** (1.2 or newer) and **ninja**:
  ```bash
  brew install meson ninja
  ```
- **vcat-d-ios checked out next to this repo.** The build compiles against the plugin API header
  in vcat-d-ios's `plugin-api/vcat_decoder_plugin.h`:
  ```
  Projects/
    vcatd-dav1d-plugin/    <- this repo
    vcat-d-ios/            <- provides plugin-api/vcat_decoder_plugin.h
  ```
  If it lives elsewhere, set `VCAT_PLUGIN_API_DIR=/path/to/vcat-d-ios/plugin-api`.
- **Network access** on the first build, to clone dav1d.

### Build

From the repo root:

```bash
ios/build_xcframework.sh
```

The first build takes a few minutes, because it builds dav1d twice: once for the device and
once for the simulator. Later runs reuse those builds unless the pinned ref changes. A
successful run ends with:

```
== vcat.dav1d  namespace=vcatd_dav1d_  dav1d=1.5.3  api=v1
== iphoneos: exports _vcatd_dav1d_plugin_entry
== iphonesimulator: exports _vcatd_dav1d_plugin_entry
== built .../build/dist/vcatd-dav1d-plugin.xcframework (manifest: vcat-plugin.json inside)
```

**Output:** `build/dist/vcatd-dav1d-plugin.xcframework`, a single drop-in:

```
vcatd-dav1d-plugin.xcframework/
  Info.plist
  vcat-plugin.json                      manifest (id, namespace, entry symbol, API version)
  ios-arm64/                            device slice
    libvcatd_dav1d_plugin.a
    Headers/vcatd_dav1d_plugin.h        declares the entry point only
  ios-arm64-simulator/                  Apple Silicon simulator slice
    libvcatd_dav1d_plugin.a
    Headers/vcatd_dav1d_plugin.h
```

**For each slice, the script:**
1. Builds dav1d at the pin with meson, as a static library built with `-fno-common`.
2. Compiles `native/vcat_plugin_dav1d.c`, the C ABI shim, with the namespace derived from the
   id in `plugin.json` (`vcat.dav1d` → `vcatd_dav1d_`).
3. Prelinks shim and dav1d into one object (`ld -r -exported_symbols_list`), so that **only
   `_vcatd_dav1d_plugin_entry` is exported**. Every dav1d symbol is hidden, so this plugin can
   share one app binary with any other plugin, even one wrapping another dav1d.
4. Fails the build if anything else is exported.

**Optional overrides,** as environment variables:

| Variable | Effect |
|---|---|
| `DAV1D_REF=<tag/sha>` | build a different dav1d than `plugin.json` pins |
| `DAV1D_REMOTE=<url>` | clone from a different dav1d repository |
| `DAV1D_SRC=<path>` | build from a local dav1d checkout |
| `VCAT_PLUGIN_API_DIR=<dir>` | location of `vcat_decoder_plugin.h` |
| `IPHONEOS_DEPLOYMENT_TARGET=<ver>` | minimum iOS version (default 17.0, matching vcat-d-ios) |

For a clean rebuild, remove the cached work: `rm -rf build/ios build/dist`.

### Verify (optional)

```bash
X=build/dist/vcatd-dav1d-plugin.xcframework
cat $X/vcat-plugin.json                                  # id, namespace, apiVersion
nm -gUj $X/ios-arm64/libvcatd_dav1d_plugin.a             # exactly one: _vcatd_dav1d_plugin_entry
```

### Using it with vcat-d-ios

The plugin is linked into the app when the app is built; iOS can't load plugins at runtime.
Adding or updating it is: drop it in, regenerate the project, build.

```bash
rm -rf ../vcat-d-ios/decoder-plugins/vcatd-dav1d-plugin.xcframework   # remove any older copy first
mkdir -p ../vcat-d-ios/decoder-plugins
cp -R build/dist/vcatd-dav1d-plugin.xcframework ../vcat-d-ios/decoder-plugins/
cd ../vcat-d-ios && xcodegen generate
```

Then build vcat-d-ios as usual. Remove the old copy first because `cp -R` merges into an
existing directory, which can leave stale files behind. `xcodegen generate` runs vcat-d-ios's
plugin pickup, which validates the plugin (namespace, API version, exported symbols) and links
it. If you skip it, the next build stops with a message telling you to run it. Conditions shows
AV1 as `vcat.dav1d`, and telemetry rows record `vcat-dav1d-1.5.3`, the same string as on
Android.

### Troubleshooting

| Symptom | Fix |
|---|---|
| `xcrun: error: SDK "iphoneos" cannot be located` | `xcode-select` points at the Command Line Tools. Select full Xcode (see Prerequisites) |
| `xcode-select: error: invalid developer directory` | The path doesn't match your Xcode's actual name, e.g. `/Applications/Xcode-27.2.0.app` |
| `meson not found` / `ninja not found` | `brew install meson ninja` |
| `.../vcat_decoder_plugin.h not found` | Check out vcat-d-ios next to this repo, or set `VCAT_PLUGIN_API_DIR` |
| `slice exports symbols outside _vcatd_dav1d_*` | A change exposed a non-namespaced symbol. Keep shim helpers `static`; the build refuses to package a plugin that could collide with another |
| vcat-d-ios: `built against plugin API vN, this app is vM` | The plugin API changed. Pull vcat-d-ios and rebuild the plugin |

## Dependencies

| Dependency | License | Source |
|---|---|---|
| dav1d | BSD-2-Clause | [videolan/dav1d](https://code.videolan.org/videolan/dav1d) |
| vcatd-decoder-plugin-api | GPL-3.0-or-later | Maven Central |

## License

GPL-3.0-or-later. For commercial licensing contact: legal@roncatech.com
