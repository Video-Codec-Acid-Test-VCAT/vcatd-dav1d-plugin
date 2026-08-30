# vcatd-dav1d-plugin

AV1 software decoder plugin for [VCAT (Video Codec Acid Test)](https://github.com/Video-Codec-Acid-Test-VCAT/vcat-d).

Wraps [dav1d](https://code.videolan.org/videolan/dav1d) (VideoLAN AV1 decoder) as a VCAT decoder
plugin, loaded dynamically by the VCAT app at runtime. Declares MIME type `video/av01`.

## Prerequisites

- JDK 17+
- Android SDK with NDK **27.0.12077973** and CMake 3.22+
- **meson** and **ninja** (dav1d's build system)

dav1d is **built from source**, not bundled: the Gradle build clones it at a pinned release into
`app/build/dav1d/src` and meson-installs a static `libdav1d.a` per ABI into
`app/build/dav1d/install-<abi>/`. Nothing prebuilt is carried in the repo.

The pin is **1.5.3** — the release matching the prebuilt this plugin previously shipped, so
switching to a source build did not change decoder behavior. Since VCAT measures decoder
performance, moving it is a deliberate act:

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

## Dependencies

| Dependency | License | Source |
|---|---|---|
| dav1d | BSD-2-Clause | [videolan/dav1d](https://code.videolan.org/videolan/dav1d) |
| vcatd-decoder-plugin-api | GPL-3.0-or-later | Maven Central |

## License

GPL-3.0-or-later. For commercial licensing contact: legal@roncatech.com
