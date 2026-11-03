#!/bin/bash
#
# Builds the iOS output of this plugin: build/dist/<artifact>.xcframework, a single drop-in for
# vcat-d-ios's decoder-plugins/ folder. The plugin manifest travels inside it, as
# <artifact>.xcframework/vcat-plugin.json (the counterpart of the .aar's
# assets/plugin-manifest.json). The Android output is built by Gradle
# (`./gradlew dist`) and ignores this directory; this script ignores app/ and needs no Android
# SDK or NDK.
#
# Everything plugin-specific comes from ../plugin.json, the same file Gradle reads for the pinned
# dav1d ref. Steps, per platform slice (iphoneos, iphonesimulator; arm64):
#   1. build dav1d at the pinned ref as a static library (meson + ninja)
#   2. compile native/vcat_plugin_dav1d.c with the namespace derived from plugin.json's id
#   3. prelink shim + libdav1d.a into one object that exports only _<namespace>* (ld -r), so
#      none of dav1d's own symbols are visible to the app or to other plugins
#   4. verify with nm that nothing outside the namespace is exported
# then wrap both slices in an XCFramework and write the plugin manifest into it.
#
# Host prerequisites: Xcode, meson, ninja (`brew install meson ninja`).
#
# Overrides:
#   DAV1D_REF=<ref> / DAV1D_REMOTE=<url>   build a different dav1d than plugin.json pins
#   DAV1D_SRC=<path>                       build from a local dav1d checkout
#   VCAT_PLUGIN_API_DIR=<dir>              where vcat_decoder_plugin.h lives
#                                          (default: ../vcat-d-ios/plugin-api next to this repo)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_JSON="$ROOT/plugin.json"

json() { plutil -extract "$1" raw -o - "$PLUGIN_JSON"; }

PLUGIN_ID="$(json id)"
PLUGIN_MIME="$(json mimeType)"
NAME_PREFIX="$(json decoderNamePrefix)"
ARTIFACT="$(json artifact)"
LICENSE_TEXT="$(json license)"
DAV1D_REF="${DAV1D_REF:-$(json source.ref)}"
DAV1D_REMOTE="${DAV1D_REMOTE:-$(json source.remote)}"

# Namespace from the id: "vcat.dav2d.st-arm" -> "vcatd_dav2d_st_arm_".
case "$PLUGIN_ID" in
    vcat.*) ;;
    *) echo "build_xcframework.sh: plugin id '$PLUGIN_ID' must start with 'vcat.'" >&2; exit 1 ;;
esac
NAMESPACE="vcatd_$(printf '%s' "${PLUGIN_ID#vcat.}" | tr '.-' '__')_"
ENTRY_SYMBOL="${NAMESPACE}plugin_entry"
LIB_NAME="lib${NAMESPACE}plugin.a"

API_DIR="${VCAT_PLUGIN_API_DIR:-$ROOT/../vcat-d-ios/plugin-api}"
API_HEADER="$API_DIR/vcat_decoder_plugin.h"
[ -f "$API_HEADER" ] || { echo "build_xcframework.sh: $API_HEADER not found (set VCAT_PLUGIN_API_DIR)" >&2; exit 1; }
API_VERSION="$(sed -n 's/^#define VCAT_PLUGIN_API_VERSION \([0-9]*\)u.*/\1/p' "$API_HEADER")"

command -v meson >/dev/null 2>&1 || { echo "build_xcframework.sh: meson not found -- brew install meson ninja" >&2; exit 1; }
command -v ninja >/dev/null 2>&1 || { echo "build_xcframework.sh: ninja not found -- brew install meson ninja" >&2; exit 1; }

WORK="$ROOT/build/ios"
DIST="$ROOT/build/dist"
SRC_DIR="$WORK/dav1d-src"
DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"
# Rebuild dav1d when the ref or its build flags change.
STAMP_KEY="$DAV1D_REF fno-common"
mkdir -p "$WORK" "$DIST"

echo "== $PLUGIN_ID  namespace=$NAMESPACE  dav1d=$DAV1D_REF  api=v$API_VERSION"

# --- dav1d source at the pinned ref ------------------------------------------------------------
if [ -n "${DAV1D_SRC:-}" ]; then
    rm -rf "$SRC_DIR"
    cp -R "$DAV1D_SRC" "$SRC_DIR"
elif [ ! -d "$SRC_DIR/.git" ]; then
    git clone --depth 1 --branch "$DAV1D_REF" "$DAV1D_REMOTE" "$SRC_DIR"
else
    # A shallow fetch of a tag populates FETCH_HEAD but not the tag itself; check that out.
    git -C "$SRC_DIR" fetch --depth 1 --force origin "$DAV1D_REF"
    git -C "$SRC_DIR" checkout -f --detach FETCH_HEAD
fi

# --- one slice per platform --------------------------------------------------------------------
build_slice() {
    local platform="$1" sdk_path deploy_arg subsystem
    case "$platform" in
        iphoneos)
            sdk_path="$(xcrun --sdk iphoneos --show-sdk-path)"
            deploy_arg="-miphoneos-version-min=$DEPLOYMENT_TARGET"
            subsystem="ios" ;;
        iphonesimulator)
            sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path)"
            deploy_arg="-mios-simulator-version-min=$DEPLOYMENT_TARGET"
            subsystem="ios-simulator" ;;
    esac

    local slice="$WORK/$platform"
    local dav1d_build="$slice/dav1d-build" dav1d_install="$slice/dav1d-install"
    local stamp="$dav1d_install/.dav1d-ref"
    mkdir -p "$slice"

    # 1. dav1d, cached per platform by ref.
    if [ -n "${DAV1D_SRC:-}" ] || [ ! -f "$stamp" ] || [ "$(cat "$stamp")" != "$STAMP_KEY" ] \
            || [ ! -f "$dav1d_install/lib/libdav1d.a" ]; then
        local cross="$slice/cross.meson"
        cat > "$cross" <<EOF
[binaries]
c = ['clang', '-arch', 'arm64', '-isysroot', '$sdk_path']
cpp = ['clang++', '-arch', 'arm64', '-isysroot', '$sdk_path']
ar = 'ar'
strip = 'strip'

[built-in options]
c_args = ['$deploy_arg', '-fno-common']
cpp_args = ['$deploy_arg']
c_link_args = ['$deploy_arg']
cpp_link_args = ['$deploy_arg']

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'darwin'
subsystem = '$subsystem'
kernel = 'xnu'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
        # -fno-common (c_args above): dav1d has a few tentative definitions (dav1d_masks,
        # dav1d_qm_tbl). As common symbols they survive the prelink's export list as globals,
        # and identically named commons in two plugins would silently merge into one table.
        rm -rf "$dav1d_build" "$dav1d_install"
        meson setup "$dav1d_build" "$SRC_DIR" --cross-file="$cross" --buildtype=release \
            --default-library=static --prefix="$dav1d_install" \
            -Denable_tools=false -Denable_tests=false
        ninja -C "$dav1d_build"
        ninja -C "$dav1d_build" install
        echo "$STAMP_KEY" > "$stamp"
    fi

    # 2. The shim, named by the namespace.
    xcrun clang -arch arm64 -isysroot "$sdk_path" "$deploy_arg" -O2 -std=c11 -Wall -Wextra \
        -fvisibility=hidden \
        -I "$dav1d_install/include" -I "$API_DIR" \
        -DVCAT_NS="$NAMESPACE" \
        -DVCAT_PLUGIN_ID="\"$PLUGIN_ID\"" \
        -DVCAT_PLUGIN_MIME="\"$PLUGIN_MIME\"" \
        -DVCAT_DECODER_NAME_PREFIX="\"$NAME_PREFIX\"" \
        -c "$ROOT/native/vcat_plugin_dav1d.c" -o "$slice/shim.o"

    # 3. Prelink behind the namespace.
    echo "_${NAMESPACE}*" > "$slice/exported_symbols.txt"
    xcrun ld -r -arch arm64 "$slice/shim.o" "$dav1d_install/lib/libdav1d.a" \
        -exported_symbols_list "$slice/exported_symbols.txt" -o "$slice/plugin.o"
    rm -f "$slice/$LIB_NAME"
    xcrun libtool -static -o "$slice/$LIB_NAME" "$slice/plugin.o" 2>/dev/null

    # 4. Nothing outside the namespace may be exported.
    local leaked
    leaked="$(nm -gUj "$slice/$LIB_NAME" | grep -v -e '^$' -e ':$' | grep -v "^_${NAMESPACE}" || true)"
    if [ -n "$leaked" ]; then
        echo "build_xcframework.sh: $platform slice exports symbols outside _${NAMESPACE}*:" >&2
        echo "$leaked" >&2
        exit 1
    fi
    echo "== $platform: exports $(nm -gUj "$slice/$LIB_NAME" | grep "^_${NAMESPACE}" | tr '\n' ' ')"

    # Public header: declares the entry point only, so the app needs nothing from dav1d.
    mkdir -p "$slice/Headers"
    cat > "$slice/Headers/${NAMESPACE}plugin.h" <<EOF
/* Generated by ios/build_xcframework.sh for $PLUGIN_ID. See vcat_decoder_plugin.h. */
#pragma once
struct vcat_decoder_plugin;
const struct vcat_decoder_plugin *${ENTRY_SYMBOL}(void);
EOF
}

build_slice iphoneos
build_slice iphonesimulator

# --- package ------------------------------------------------------------------------------------
XCF="$DIST/$ARTIFACT.xcframework"
rm -rf "$XCF"
xcodebuild -create-xcframework \
    -library "$WORK/iphoneos/$LIB_NAME" -headers "$WORK/iphoneos/Headers" \
    -library "$WORK/iphonesimulator/$LIB_NAME" -headers "$WORK/iphonesimulator/Headers" \
    -output "$XCF" >/dev/null

# Manifest, inside the XCFramework: what the app's pickup script needs to link and register
# the plugin. Xcode ignores extra top-level files in an XCFramework.
LINKER_FLAGS="$(plutil -extract linkerFlags json -o - "$PLUGIN_JSON")"
cat > "$XCF/vcat-plugin.json" <<EOF
{
  "id": "$PLUGIN_ID",
  "namespace": "$NAMESPACE",
  "mimeType": "$PLUGIN_MIME",
  "entrySymbol": "$ENTRY_SYMBOL",
  "apiVersion": $API_VERSION,
  "linkerFlags": $LINKER_FLAGS,
  "decoderSource": "dav1d $DAV1D_REF",
  "license": "$LICENSE_TEXT"
}
EOF

echo "== built $XCF (manifest: vcat-plugin.json inside)"
