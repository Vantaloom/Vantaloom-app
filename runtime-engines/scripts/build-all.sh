#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="${LOCK_FILE:-$RUNTIME_ROOT/sources.lock.json}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
CACHE_DIR="${CACHE_DIR:-$RUNTIME_ROOT/.cache}"
OUT_DIR="${OUT_DIR:-$RUNTIME_ROOT/out}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

case "$(uname -s)" in
  Linux)
    NDK_HOST_TAG="linux-x86_64"
    GYP_HOST_OS="linux"
    ;;
  Darwin)
    NDK_HOST_TAG="darwin-x86_64"
    GYP_HOST_OS="darwin"
    ;;
  *)
    echo "ERROR: Node's Android build is supported here only on Linux or macOS." >&2
    exit 1
    ;;
esac

for tool in "$PYTHON_BIN" make patch; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required build tool is missing: $tool" >&2
    exit 1
  fi
done

read -r NDK_VERSION MIN_API SOURCE_DATE_EPOCH < <(
  "$PYTHON_BIN" - "$LOCK_FILE" <<'PY'
import json
import sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
target = lock["target"]
print(target["ndkVersion"], target["minApi"], target["sourceDateEpoch"])
PY
)

NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "ERROR: ANDROID_NDK_HOME must point to NDK $NDK_VERSION." >&2
  exit 1
fi
NDK_ROOT="$(cd -- "$NDK_ROOT" && pwd)"
NDK_PROPERTIES="$NDK_ROOT/source.properties"
if [[ ! -f "$NDK_PROPERTIES" ]]; then
  echo "ERROR: NDK source.properties is missing: $NDK_PROPERTIES" >&2
  exit 1
fi
ACTUAL_NDK_VERSION="$(awk -F= '/^Pkg.Revision/{gsub(/[[:space:]]/, "", $2); print $2}' "$NDK_PROPERTIES")"
if [[ "$ACTUAL_NDK_VERSION" != "$NDK_VERSION" ]]; then
  echo "ERROR: locked NDK is $NDK_VERSION, found $ACTUAL_NDK_VERSION." >&2
  exit 1
fi
NDK_NOTICE="$NDK_ROOT/NOTICE"
if [[ ! -f "$NDK_NOTICE" ]]; then
  echo "ERROR: Android NDK NOTICE is missing: $NDK_NOTICE" >&2
  exit 1
fi

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
TOOLCHAIN_BIN="$TOOLCHAIN/bin"
CC="$TOOLCHAIN_BIN/aarch64-linux-android${MIN_API}-clang"
CXX="$TOOLCHAIN_BIN/aarch64-linux-android${MIN_API}-clang++"
AR="$TOOLCHAIN_BIN/llvm-ar"
RANLIB="$TOOLCHAIN_BIN/llvm-ranlib"
NM="$TOOLCHAIN_BIN/llvm-nm"
READELF="$TOOLCHAIN_BIN/llvm-readelf"
STRIP="$TOOLCHAIN_BIN/llvm-strip"
for tool in "$CC" "$CXX" "$AR" "$RANLIB" "$NM" "$READELF" "$STRIP"; do
  if [[ ! -x "$tool" ]]; then
    echo "ERROR: locked NDK tool is missing: $tool" >&2
    exit 1
  fi
done

CC_host="${CC_host:-cc}"
CXX_host="${CXX_host:-c++}"
AR_host="${AR_host:-ar}"
LINK_host="${LINK_host:-$CXX_host}"
CC_target="$CC"
CXX_target="$CXX"
AR_target="$AR"
LINK_target="$CXX"
for tool in "$CC_host" "$CXX_host" "$AR_host" "$LINK_host"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required host build tool is missing: $tool" >&2
    exit 1
  fi
done
case "$(uname -m)" in
  x86_64) EXPECTED_NODE_HOST_ARCH="x64" ;;
  arm64|aarch64) EXPECTED_NODE_HOST_ARCH="arm64" ;;
  *)
    echo "ERROR: unsupported Node build host architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

WORK_PARENT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
WORK_DIR="$(mktemp -d "$WORK_PARENT/vantaloom-runtime.XXXXXX")"
cleanup() {
  case "$WORK_DIR" in
    "$WORK_PARENT"/vantaloom-runtime.*) rm -rf -- "$WORK_DIR" ;;
    *) echo "WARNING: refusing to remove unexpected work directory $WORK_DIR" >&2 ;;
  esac
}
trap cleanup EXIT

export LC_ALL=C
export LANG=C
export TZ=UTC
export SOURCE_DATE_EPOCH
export ZERO_AR_DATE=1

"$PYTHON_BIN" "$SCRIPT_DIR/package_runtime.py" --lock "$LOCK_FILE" prepare \
  --cache "$CACHE_DIR" \
  --work "$WORK_DIR/work" \
  --stage "$OUT_DIR"

NODE_SOURCE="$($PYTHON_BIN - "$WORK_DIR/work/prepared.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["nodeSource"])
PY
)"
PYTHON_INCLUDE="$WORK_DIR/work/python-prefix/include/python3.14"
GENERATED_INCLUDE="$WORK_DIR/work/generated"
NATIVE_STAGE="$OUT_DIR/jniLibs/arm64-v8a"
GO_RUNNER_ROOT="$RUNTIME_ROOT/go-runner"

pushd "$NODE_SOURCE" >/dev/null
patch --batch --forward -F 0 -p1 \
  < "$RUNTIME_ROOT/src/patches/node-disable-v8-trap-handler.patch"
patch --batch --forward -F 0 -p1 \
  < "$RUNTIME_ROOT/src/patches/node-loopback-bind.patch"
TRAP_HANDLER_HEADER="deps/v8/src/trap-handler/trap-handler.h"
TRAP_HANDLER_DEFINITION_COUNT="$(
  grep -Ec '^#define V8_TRAP_HANDLER_SUPPORTED (true|false)$' \
    "$TRAP_HANDLER_HEADER" || true
)"
if [[ "$TRAP_HANDLER_DEFINITION_COUNT" != "1" ]] \
  || ! grep -qx '^#define V8_TRAP_HANDLER_SUPPORTED false$' \
    "$TRAP_HANDLER_HEADER" \
  || grep -q '^#define V8_TRAP_HANDLER_VIA_SIMULATOR$' \
    "$TRAP_HANDLER_HEADER"; then
  echo "ERROR: V8 trap handling is not unconditionally disabled." >&2
  exit 1
fi
grep -q 'IsVantaloomLoopbackAddress' src/tcp_wrap.cc
grep -q 'IsVantaloomLoopbackAddress' src/udp_wrap.cc
grep -q 'Native addons are disabled in the Vantaloom Android runtime' \
  src/node_binding.cc

export PATH="$TOOLCHAIN_BIN:$PATH"
export CC CXX AR RANLIB NM
export CC_host CXX_host AR_host LINK_host
export CC_target CXX_target AR_target LINK_target
export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=$GYP_HOST_OS OS=android android_ndk_path=$NDK_ROOT"
export CFLAGS="${CFLAGS:-} -ffile-prefix-map=$NODE_SOURCE=/usr/src/node -fdebug-prefix-map=$NODE_SOURCE=/usr/src/node"
export CXXFLAGS="${CXXFLAGS:-} -ffile-prefix-map=$NODE_SOURCE=/usr/src/node -fdebug-prefix-map=$NODE_SOURCE=/usr/src/node"
export LDFLAGS="${LDFLAGS:-} -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1"

./configure \
  --dest-cpu=arm64 \
  --dest-os=android \
  --openssl-no-asm \
  --cross-compiling \
  --with-intl=small-icu \
  --without-npm \
  --without-corepack
"$PYTHON_BIN" - config.gypi out/Makefile "$EXPECTED_NODE_HOST_ARCH" <<'PY'
import json
import pathlib
import sys

config_path = pathlib.Path(sys.argv[1])
makefile_path = pathlib.Path(sys.argv[2])
expected_host_arch = sys.argv[3]
config_text = config_path.read_text(encoding="utf-8")
config = json.loads(config_text[config_text.index("{") :])
variables = config["variables"]
expected = {
    "host_arch": expected_host_arch,
    "target_arch": "arm64",
    "want_separate_host_toolset": 1,
}
actual = {key: variables.get(key) for key in expected}
if actual != expected:
    raise SystemExit(
        f"Node cross-build configuration mismatch: expected {expected}, got {actual}"
    )

host_keys = {"CC.host", "CXX.host", "AR.host", "LINK.host"}
host_lines = {}
for line in makefile_path.read_text(encoding="utf-8").splitlines():
    key, separator, value = line.partition(" ?= ")
    if separator and key in host_keys:
        host_lines[key] = value
if set(host_lines) != host_keys:
    raise SystemExit(f"Node Makefile host toolchain is incomplete: {host_lines}")
if any("aarch64-linux-android" in value for value in host_lines.values()):
    raise SystemExit(f"Node host tools use the Android cross-toolchain: {host_lines}")
PY
make -s -j "$JOBS"
NODE_BINARY="$NODE_SOURCE/out/Release/node"
if [[ ! -f "$NODE_BINARY" ]]; then
  echo "ERROR: Node build did not produce $NODE_BINARY" >&2
  exit 1
fi
"$STRIP" --strip-unneeded "$NODE_BINARY"
popd >/dev/null

LAUNCHER_BINARY="$WORK_DIR/libvantaloom_python.so"
"$CC" \
  -std=c11 \
  -O2 \
  -D_XOPEN_SOURCE=700 \
  -fPIE \
  -pie \
  -ffile-prefix-map="$RUNTIME_ROOT=/usr/src/vantaloom-runtime-engines" \
  -fdebug-prefix-map="$RUNTIME_ROOT=/usr/src/vantaloom-runtime-engines" \
  -I"$PYTHON_INCLUDE" \
  -I"$GENERATED_INCLUDE" \
  "$RUNTIME_ROOT/src/launcher.c" \
  -L"$NATIVE_STAGE" \
  -lpython3.14 \
  -llog \
  -ldl \
  -lm \
  -Wl,-rpath,'$ORIGIN' \
  -Wl,-z,origin \
  -Wl,-z,max-page-size=16384 \
  -Wl,--build-id=sha1 \
  -o "$LAUNCHER_BINARY"
"$STRIP" --strip-unneeded "$LAUNCHER_BINARY"

pushd "$GO_RUNNER_ROOT" >/dev/null
go test ./...
YAEGI_DOWNLOAD_JSON="$(go mod download -json github.com/traefik/yaegi@v0.16.1)"
eval "$(
  "$PYTHON_BIN" -c '
import json, shlex, sys
value = json.load(sys.stdin)
for shell_name, json_name in (
    ("YAEGI_ZIP", "Zip"),
    ("YAEGI_DIR", "Dir"),
    ("YAEGI_SUM", "Sum"),
    ("YAEGI_MOD_SUM", "GoModSum"),
):
    print(f"{shell_name}={shlex.quote(value[json_name])}")
' <<<"$YAEGI_DOWNLOAD_JSON"
)"
"$PYTHON_BIN" - "$LOCK_FILE" "$YAEGI_ZIP" "$YAEGI_SUM" "$YAEGI_MOD_SUM" <<'PY'
import hashlib
import json
import pathlib
import sys

lock = json.load(open(sys.argv[1], encoding="utf-8"))["goRunner"]["yaegi"]
archive = pathlib.Path(sys.argv[2])
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
if archive.stat().st_size != lock["size"]:
    raise SystemExit("Yaegi module zip size does not match sources.lock.json")
if digest != lock["sha256"]:
    raise SystemExit("Yaegi module zip SHA256 does not match sources.lock.json")
if sys.argv[3] != lock["goModuleSum"] or sys.argv[4] != lock["goModSum"]:
    raise SystemExit("Yaegi Go module sums do not match sources.lock.json")
PY
GO_BINARY="$WORK_DIR/libvantaloom_go.so"
GOWORK=off GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build \
    -buildmode=pie \
    -trimpath \
    -buildvcs=false \
    -ldflags="-s -w -buildid= -X main.buildVersion=0.1.0" \
    -o "$GO_BINARY" \
    .
YAEGI_LICENSE="$YAEGI_DIR/LICENSE"
GO_LICENSE="$(go env GOROOT)/LICENSE"
popd >/dev/null

assert_pie() {
  local binary="$1"
  "$READELF" -h "$binary" | grep -Eq 'Type:[[:space:]]+DYN'
  "$READELF" -h "$binary" | grep -Eq 'Machine:[[:space:]]+AArch64'
  "$READELF" -l "$binary" | grep -q '/system/bin/linker64'
}
assert_pie "$NODE_BINARY"
assert_pie "$LAUNCHER_BINARY"
assert_pie "$GO_BINARY"

LIBCXX_ARGUMENTS=()
if "$READELF" -d "$NODE_BINARY" | grep -q 'Shared library: \[libc++_shared.so\]'; then
  LIBCXX="$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
  if [[ ! -f "$LIBCXX" ]]; then
    echo "ERROR: Node requires libc++_shared.so but the NDK copy is missing." >&2
    exit 1
  fi
  LIBCXX_ARGUMENTS+=(--libcxx "$LIBCXX")
fi

"$PYTHON_BIN" "$SCRIPT_DIR/package_runtime.py" --lock "$LOCK_FILE" finalize \
  --stage "$OUT_DIR" \
  --node-binary "$NODE_BINARY" \
  --launcher-binary "$LAUNCHER_BINARY" \
  --go-binary "$GO_BINARY" \
  --yaegi-license "$YAEGI_LICENSE" \
  --go-license "$GO_LICENSE" \
  --ndk-notice "$NDK_NOTICE" \
  "${LIBCXX_ARGUMENTS[@]}"

echo "Runtime engines staged at: $OUT_DIR"
echo "  JNI libraries: $OUT_DIR/jniLibs/arm64-v8a"
echo "  APK assets:    $OUT_DIR/assets/runtime-engines"
echo "  Manifest:      $OUT_DIR/runtime-engines.manifest.json"
