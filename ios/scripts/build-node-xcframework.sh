#!/usr/bin/env bash
# 在 macOS 上把 apps/api/cmd/vantaloom-ios（操控端节点的 C ABI）编成 iOS 静态库并
# 打成 xcframework。
#
#   apps/ios/scripts/build-node-xcframework.sh            # 设备 arm64
#   MIN_IOS=17.0 apps/ios/scripts/build-node-xcframework.sh
#
# 产物：apps/ios/node/VantaloomNode.xcframework（gitignore）。
# 只能在 macOS + Xcode 上跑：GOOS=ios 的链接一律要求 cgo（iPhoneOS SDK 的 clang）。
#
# 本脚本没有在任何机器上跑过（写它的机器是 Windows）；旗标照 golang.org/x/mobile
# cmd/gomobile/env.go 的 ios 目标抄的（-isysroot / -arch arm64 /
# -miphoneos-version-min），去掉了 -fembed-bitcode（Xcode 14 起弃用）。
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "error: 只能在 macOS 上构建（需要 Xcode 的 iPhoneOS SDK 与 clang）" >&2
  exit 1
fi
command -v go >/dev/null || { echo "error: 找不到 go" >&2; exit 1; }
command -v xcrun >/dev/null || { echo "error: 找不到 xcrun（装 Xcode 并 xcode-select）" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$IOS_DIR/../.." && pwd)"
API_DIR="$REPO_ROOT/apps/api"
NODE_DIR="$IOS_DIR/node"
BUILD_DIR="$NODE_DIR/build"
MIN_IOS="${MIN_IOS:-17.0}"
OUT="$NODE_DIR/VantaloomNode.xcframework"

SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
CLANG="$(xcrun --sdk iphoneos -f clang)"
SLICE="$BUILD_DIR/ios-arm64"
mkdir -p "$SLICE"

echo "· SDK:   $SDK_PATH"
echo "· clang: $CLANG"
echo "· go:    $(go version)"

export CGO_ENABLED=1 GOOS=ios GOARCH=arm64
export CC="$CLANG" CXX="$CLANG++"
export CGO_CFLAGS="-isysroot $SDK_PATH -arch arm64 -miphoneos-version-min=$MIN_IOS"
export CGO_CXXFLAGS="$CGO_CFLAGS"
export CGO_LDFLAGS="$CGO_CFLAGS"

echo "· go build -buildmode=c-archive ./cmd/vantaloom-ios"
# -w 去 DWARF；**不要 -s**（c-archive 的符号表是 Xcode 链接用的）。
( cd "$API_DIR" && go build -trimpath -ldflags="-w" -buildmode=c-archive \
    -o "$SLICE/libvantaloomnode.a" ./cmd/vantaloom-ios )

[[ -f "$SLICE/libvantaloomnode.a" && -f "$SLICE/libvantaloomnode.h" ]] || {
  echo "error: go build 没有产出 libvantaloomnode.a / libvantaloomnode.h" >&2; exit 1; }

INCLUDE="$SLICE/include"
rm -rf "$INCLUDE"; mkdir -p "$INCLUDE"
cp "$SLICE/libvantaloomnode.h" "$INCLUDE/"
cp "$NODE_DIR/module.modulemap" "$INCLUDE/"

echo "· xcodebuild -create-xcframework"
rm -rf "$OUT"
xcodebuild -create-xcframework \
  -library "$SLICE/libvantaloomnode.a" -headers "$INCLUDE" \
  -output "$OUT"

echo
echo "产物：$OUT"
du -sh "$OUT" "$SLICE/libvantaloomnode.a"
echo
echo "下一步："
echo "  cd $IOS_DIR && VANTALOOM_IOS_NODE=true xcodegen generate && open Vantaloom.xcodeproj"
echo "  发布给公开仓 CI：zip -r VantaloomNode.xcframework.zip VantaloomNode.xcframework && shasum -a 256 …（见 node/README.md）"
