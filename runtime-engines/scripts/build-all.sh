#!/usr/bin/env bash
# Build the Android runtime engines (Python embeddable + Node + Yaegi Go runner).
#
# Commands (default: all):
#   prepare         Download/verify sources, stage Python, extract Node.
#   node-configure  Patch + configure Node for Android arm64.
#   node-compile    Run one incremental `make` wave (safe to re-run; continues).
#   package         Build Python launcher + Go runner and finalize the stage.
#   all             prepare → node-configure → node-compile → package.
#
# CI runs prepare/configure/package as short steps and node-compile as several
# timed waves so each GitHub Actions step stays under the step timeout while
# make's incremental state persists across waves via WORK_DIR.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="${LOCK_FILE:-$RUNTIME_ROOT/sources.lock.json}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
CACHE_DIR="${CACHE_DIR:-$RUNTIME_ROOT/.cache}"
OUT_DIR="${OUT_DIR:-$RUNTIME_ROOT/out}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
COMMAND="${1:-all}"

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

# Persistent work directory. CI sets WORK_DIR to a fixed path under the job so
# prepare/configure/compile waves/package share the same tree. Local runs still
# get a private temp dir that is cleaned up on exit of `all`.
WORK_PARENT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
OWN_WORK_DIR=0
if [[ -z "${WORK_DIR:-}" ]]; then
  WORK_DIR="$(mktemp -d "$WORK_PARENT/vantaloom-runtime.XXXXXX")"
  OWN_WORK_DIR=1
else
  mkdir -p -- "$WORK_DIR"
  WORK_DIR="$(cd -- "$WORK_DIR" && pwd)"
fi
STATE_FILE="$WORK_DIR/state.env"
cleanup() {
  if [[ "$OWN_WORK_DIR" -eq 1 ]]; then
    case "$WORK_DIR" in
      "$WORK_PARENT"/vantaloom-runtime.*) rm -rf -- "$WORK_DIR" ;;
      *) echo "WARNING: refusing to remove unexpected work directory $WORK_DIR" >&2 ;;
    esac
  fi
}
trap cleanup EXIT

export LC_ALL=C
export LANG=C
export TZ=UTC
export SOURCE_DATE_EPOCH
export ZERO_AR_DATE=1

write_state() {
  local key="$1"
  local value="$2"
  local tmp
  tmp="$(mktemp "$WORK_DIR/state.XXXXXX")"
  if [[ -f "$STATE_FILE" ]]; then
    grep -v "^${key}=" "$STATE_FILE" >"$tmp" || true
  else
    : >"$tmp"
  fi
  printf '%s=%q\n' "$key" "$value" >>"$tmp"
  mv "$tmp" "$STATE_FILE"
}

load_state() {
  if [[ -f "$STATE_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$STATE_FILE"
  fi
}

require_state() {
  local key
  for key in "$@"; do
    if [[ -z "${!key:-}" ]]; then
      echo "ERROR: state key $key is missing; run earlier build phases first." >&2
      exit 1
    fi
  done
}

node_jobs() {
  local value="${NODE_JOBS:-$JOBS}"
  if ! [[ "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: NODE_JOBS must be a positive integer, got: $value" >&2
    exit 1
  fi
  printf '%s\n' "$value"
}

export_node_env() {
  local node_source="$1"
  export PATH="$TOOLCHAIN_BIN:$PATH"
  export CC CXX AR RANLIB NM
  export CC_host CXX_host AR_host LINK_host
  export CC_target CXX_target AR_target LINK_target
  export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=$GYP_HOST_OS OS=android android_ndk_path=$NDK_ROOT"
  # Drop debug info for the Node/V8 compile. Debug info multiplies peak clang RSS
  # on large V8 translation units and is stripped from the final binary anyway.
  export CFLAGS="${CFLAGS:-} -g0 -ffile-prefix-map=$node_source=/usr/src/node -fdebug-prefix-map=$node_source=/usr/src/node"
  export CXXFLAGS="${CXXFLAGS:-} -g0 -ffile-prefix-map=$node_source=/usr/src/node -fdebug-prefix-map=$node_source=/usr/src/node"
  export LDFLAGS="${LDFLAGS:-} -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1"
}

cmd_prepare() {
  echo "==> prepare: sources + Python stage (WORK_DIR=$WORK_DIR)"
  rm -rf -- "$WORK_DIR/work"
  mkdir -p -- "$WORK_DIR/work" "$OUT_DIR"
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
  write_state NODE_SOURCE "$NODE_SOURCE"
  write_state PYTHON_INCLUDE "$WORK_DIR/work/python-prefix/include/python3.14"
  write_state GENERATED_INCLUDE "$WORK_DIR/work/generated"
  write_state NATIVE_STAGE "$OUT_DIR/jniLibs/arm64-v8a"
  write_state PHASE_PREPARE done
  echo "prepare complete: NODE_SOURCE=$NODE_SOURCE"
}

cmd_node_configure() {
  load_state
  require_state NODE_SOURCE
  echo "==> node-configure: patch + configure ($NODE_SOURCE)"
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

  export_node_env "$NODE_SOURCE"
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
  popd >/dev/null
  write_state NODE_BINARY "$NODE_SOURCE/out/Release/node"
  write_state PHASE_NODE_CONFIGURE done
  echo "node-configure complete"
}

cmd_node_compile() {
  load_state
  require_state NODE_SOURCE NODE_BINARY
  WAVE_LABEL="${NODE_COMPILE_WAVE:-manual}"
  if [[ -f "$NODE_BINARY" ]]; then
    echo "==> node-compile [$WAVE_LABEL]: already complete ($NODE_BINARY)"
    write_state PHASE_NODE_COMPILE done
    return 0
  fi
  if [[ ! -f "$NODE_SOURCE/out/Makefile" ]]; then
    echo "ERROR: Node is not configured; run node-configure first." >&2
    exit 1
  fi

  local jobs
  jobs="$(node_jobs)"
  echo "==> node-compile [$WAVE_LABEL]: make -j${jobs} (JOBS=${JOBS})"
  free -h || true
  swapon --show || true
  # Count existing V8 objects so the CI log shows progress across waves.
  local before_objs=0
  if [[ -d "$NODE_SOURCE/out/Release/obj.target" ]]; then
    before_objs="$(find "$NODE_SOURCE/out/Release/obj.target" -type f -name '*.o' 2>/dev/null | wc -l | tr -d ' ')"
  fi
  echo "object files before wave: $before_objs"

  export_node_env "$NODE_SOURCE"
  pushd "$NODE_SOURCE" >/dev/null
  # Keep a single line of progress without dumping every compiler command.
  # On low-RAM hosts, raise swap and lower NODE_JOBS rather than relying on
  # make's jobserver to stay under the OOM killer.
  set +e
  make -j "$jobs" V=0
  local make_status=$?
  set -e
  popd >/dev/null

  local after_objs=0
  if [[ -d "$NODE_SOURCE/out/Release/obj.target" ]]; then
    after_objs="$(find "$NODE_SOURCE/out/Release/obj.target" -type f -name '*.o' 2>/dev/null | wc -l | tr -d ' ')"
  fi
  echo "object files after wave: $after_objs (delta $((after_objs - before_objs)))"
  free -h || true

  if [[ -f "$NODE_BINARY" ]]; then
    echo "node-compile [$WAVE_LABEL]: produced $NODE_BINARY"
    write_state PHASE_NODE_COMPILE done
    return 0
  fi
  if [[ "$make_status" -eq 0 ]]; then
    echo "ERROR: make exited 0 but $NODE_BINARY is missing." >&2
    exit 1
  fi
  # Non-zero make without a binary: leave a soft signal for CI waves. The
  # calling workflow decides whether another wave should continue.
  echo "node-compile [$WAVE_LABEL]: incomplete (make status=$make_status); binary not ready yet"
  write_state PHASE_NODE_COMPILE incomplete
  write_state LAST_NODE_MAKE_STATUS "$make_status"
  return "$make_status"
}

cmd_package() {
  load_state
  require_state NODE_SOURCE NODE_BINARY PYTHON_INCLUDE GENERATED_INCLUDE NATIVE_STAGE
  if [[ ! -f "$NODE_BINARY" ]]; then
    echo "ERROR: Node binary missing at $NODE_BINARY; compile Node first." >&2
    exit 1
  fi
  echo "==> package: strip Node, build launcher + Go, finalize stage"
  export_node_env "$NODE_SOURCE"
  "$STRIP" --strip-unneeded "$NODE_BINARY"

  local launcher_binary go_binary yaegi_license go_license
  launcher_binary="$WORK_DIR/libvantaloom_python.so"
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
    -o "$launcher_binary"
  "$STRIP" --strip-unneeded "$launcher_binary"

  local go_runner_root="$RUNTIME_ROOT/go-runner"
  pushd "$go_runner_root" >/dev/null
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
  go_binary="$WORK_DIR/libvantaloom_go.so"
  GOWORK=off GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build \
      -buildmode=pie \
      -trimpath \
      -buildvcs=false \
      -ldflags="-s -w -buildid= -X main.buildVersion=0.1.0" \
      -o "$go_binary" \
      .
  yaegi_license="$YAEGI_DIR/LICENSE"
  go_license="$(go env GOROOT)/LICENSE"
  popd >/dev/null

  assert_pie() {
    local binary="$1"
    "$READELF" -h "$binary" | grep -Eq 'Type:[[:space:]]+DYN'
    "$READELF" -h "$binary" | grep -Eq 'Machine:[[:space:]]+AArch64'
    "$READELF" -l "$binary" | grep -q '/system/bin/linker64'
  }
  assert_pie "$NODE_BINARY"
  assert_pie "$launcher_binary"
  assert_pie "$go_binary"

  local libcxx_arguments=()
  if "$READELF" -d "$NODE_BINARY" | grep -q 'Shared library: \[libc++_shared.so\]'; then
    local libcxx="$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    if [[ ! -f "$libcxx" ]]; then
      echo "ERROR: Node requires libc++_shared.so but the NDK copy is missing." >&2
      exit 1
    fi
    libcxx_arguments+=(--libcxx "$libcxx")
  fi

  "$PYTHON_BIN" "$SCRIPT_DIR/package_runtime.py" --lock "$LOCK_FILE" finalize \
    --stage "$OUT_DIR" \
    --node-binary "$NODE_BINARY" \
    --launcher-binary "$launcher_binary" \
    --go-binary "$go_binary" \
    --yaegi-license "$yaegi_license" \
    --go-license "$go_license" \
    --ndk-notice "$NDK_NOTICE" \
    "${libcxx_arguments[@]}"

  write_state PHASE_PACKAGE done
  echo "Runtime engines staged at: $OUT_DIR"
  echo "  JNI libraries: $OUT_DIR/jniLibs/arm64-v8a"
  echo "  APK assets:    $OUT_DIR/assets/runtime-engines"
  echo "  Manifest:      $OUT_DIR/runtime-engines.manifest.json"
}

cmd_all() {
  cmd_prepare
  cmd_node_configure
  # Keep compiling until the binary exists. Local/full runs are unbounded;
  # CI uses fixed-length waves via the node-compile command instead.
  local attempt=1
  while [[ ! -f "${NODE_BINARY:-}" ]]; do
    load_state
    NODE_COMPILE_WAVE="all-$attempt" cmd_node_compile || true
    load_state
    if [[ -f "$NODE_BINARY" ]]; then
      break
    fi
    if [[ "${PHASE_NODE_COMPILE:-}" == "done" ]]; then
      break
    fi
    # Hard fail on clang OOM / real compile errors after a few attempts so a
    # broken tree does not spin forever locally.
    if [[ "$attempt" -ge 8 ]]; then
      echo "ERROR: Node compile did not produce a binary after $attempt attempts." >&2
      exit 1
    fi
    attempt=$((attempt + 1))
  done
  cmd_package
}

case "$COMMAND" in
  prepare) cmd_prepare ;;
  node-configure) cmd_node_configure ;;
  node-compile) cmd_node_compile ;;
  package) cmd_package ;;
  all) cmd_all ;;
  *)
    echo "ERROR: unknown command '$COMMAND' (expected prepare|node-configure|node-compile|package|all)" >&2
    exit 2
    ;;
esac
