# Android runtime engines

This directory builds the real arm64 runtime engines shipped with Vantaloom
Android 0.14.32. It deliberately keeps executable code immutable:

- Every ELF is packaged under `jniLibs/arm64-v8a` with a `lib*.so` name and is
  executed or loaded from `ApplicationInfo.nativeLibraryDir`.
- `filesDir` holds a hash-addressed runtime bundle containing Python
  standard-library files, npm JavaScript/data and licenses, plus a separate
  writable state directory for caches, user packages and symlinks whose
  targets are immutable `nativeLibraryDir` files.
- It does not download executables on-device, use `proot`, or execute a rootfs
  from writable app storage. This is compatible with the Android 10+
  writable-exec restriction and targetSdk 34.

## Locked inputs

`sources.lock.json` is the source of truth:

- CPython 3.14.6 official Android aarch64 embeddable package.
- The exact upstream license bytes for CPython's bundled bzip2, libffi,
  OpenSSL, SQLite, static liblzma and zstd dependencies, plus its vendored
  Expat, libmpdec and HACL* implementations. Each URL, byte size and SHA256 is
  locked independently from the CPython archive.
- Node.js 24.18.0 (Krypton LTS) official source archive, with bundled npm
  11.16.0.
- Yaegi v0.16.1 for the pure-Go interpreted runner, pinned by Go module sums,
  proxy zip byte size and SHA256.
- Android NDK 26.0.10792818, API 24, arm64-v8a.
- Exact URLs, byte sizes, SHA256 values, SPDX metadata and the SHA256 of the
  Vantaloom Node hardening patch.

Downloads are written through a `.partial` file and are accepted only after
both size and SHA256 match. Transient HTTPS failures get three short retries,
each with a fresh `.partial`; size or hash mismatches fail immediately and are
never retried into acceptance. A mismatched cached file fails closed.

The Python dependency notices are packaged under
`licenses/python-dependencies/<id>/` and referenced by
`THIRD_PARTY_COMPONENTS.json`. The XZ recipe contributes only statically linked
`liblzma`, recorded as `LicenseRef-XZ-libLZMA-Public-Domain`, with upstream
`COPYING` included. zstd uses its BSD-3-Clause option. The locked Android NDK
`NOTICE` is mandatory and is packaged even when the final Node binary does not
need `libc++_shared.so`.
Expat's MIT text is pinned from CPython 3.14.6. libmpdec and HACL* are pinned to
the shortest stable vendored source files that contain their complete
BSD-2-Clause and MIT notices; those files are packaged unmodified.

Node documents Android as unsupported and untested upstream, although its
source includes `android-configure`. The build mirrors that configuration with
explicit flags and carries two narrowly scoped Android policies:

1. TCP and UDP binds in Node's native socket bindings reject every address
   except IPv4 `127.0.0.0/8` and IPv6 `::1`. Pure JavaScript cannot remove this
   check.
2. Native Node addons are disabled, so npm content in writable storage remains
   JavaScript/data rather than downloadable `.node` code.

Node is never advertised or linked directly to `libvantaloom_node.so`. Both
the native Android manifest and the managed `node` command enter through the
multicall launcher, which injects the Permission Model before executing the
internal Node binary. The fixed policy grants bundle read access, state
read/write access and project-cwd read/write access. The cwd must resolve under
the app's `filesDir` and must not overlap the runtime bundle.

Python installs a native CPython audit hook before initialization. The hook
rejects non-loopback `socket.bind` events and cannot be removed by Python code.
It also rejects child-process/fork/exec and ctypes dynamic-loading audit events,
closing the direct subprocess and libc-bind bypasses available to ordinary
Python code.
The generated manifest therefore truthfully marks both engines
`loopbackEnforced: true`. Shipping additional native Python extensions or Node
addons would require a new security review.

The Go entry is a Yaegi interpreter, not a full Go compiler. It omits
`unsafe`, `syscall`, `os/exec` and listener/server constructors. Because Yaegi
does not claim to be a security sandbox, Go is conservatively marked
`loopbackEnforced: false` and `serverAllowed: false`; it is available for
short-lived local programs, not background HTTP servers.

## Build

Build on Ubuntu or macOS with the locked NDK installed. Ubuntu is the reference
CI host. Go 1.23 or newer is also required for the Yaegi runner.

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.0.10792818"
bash runtime-engines/scripts/build-all.sh
```

Optional variables:

- `OUT_DIR`: generated stage; default `runtime-engines/out` and must be empty.
- `CACHE_DIR`: verified source cache; default `runtime-engines/.cache`.
- `JOBS`: Node build parallelism.
- `PYTHON_BIN`: Python 3 command used by the packager.

The build emits:

```text
out/
  jniLibs/arm64-v8a/
    libvantaloom_python.so       # PIE multicall launcher + embedded CPython
    libvantaloom_node.so         # PIE Node CLI
    libvantaloom_go.so           # PIE pure-Go Yaegi runner
    libpython3.14.so
    lib*_python.so
    libvantaloom_pyext_*.so      # CPython extension aliases
    libc++_shared.so             # only when DT_NEEDED
  assets/runtime-engines/
    manifest.json
    python/                      # no ELF files
    node/                        # npm JavaScript/data, no ELF files
    licenses/
      python-dependencies/
        bzip2/LICENSE
        libffi/LICENSE
        openssl/LICENSE.txt
        sqlite/LICENSE.md
        xz/COPYING
        zstd/LICENSE
        expat/expat-2.8.1-LICENSE
        libmpdec/libmpdec-2.5.1-LICENSE.h
        hacl-star/hacl-star-8ba599b2f6c9-MIT-NOTICE.c
      android-ndk/NOTICE
  runtime-engines.manifest.json  # CI/Kotlin-friendly mirror
```

The Python launcher reads `PYTHONHOME` exclusively from the active runtime
bundle. It creates `lib-dynload` symlinks only in runtime state; each target is
a verified `libvantaloom_pyext_*.so` in `nativeLibraryDir`, and a pre-existing
regular file at a managed link path is rejected. Python bytecode, pip cache,
user packages, npm cache/prefix and XDG data all resolve under runtime state.
The launcher exposes `python`, `python3`, `pip`, `pip3`, `node`, `npm` and `npx`
symlinks under state, and every one points back to the launcher. npm's pinned
`bin-links` data is adjusted so Android package-bin shebangs use immutable
`libvantaloom_python.so node` instead of missing `/usr/bin/env` or the internal
Node binary.

Python 3.14 and Node 24 already select `/system/bin/sh` on Android. The launcher
also sets `SHELL` and `NPM_CONFIG_SCRIPT_SHELL` to `/system/bin/sh`, and defaults
`NPM_CONFIG_IGNORE_SCRIPTS=true` for safer package installation.

## Kotlin integration contract

No generated file is committed. The APK build should:

1. Run `build-all.sh`.
2. Sync `out/jniLibs/arm64-v8a` into the app's generated jniLibs source.
3. Sync `out/assets/runtime-engines` into the app's generated assets source.
4. Keep `packaging.jniLibs.useLegacyPackaging=true`, so PIE files are extracted
   into `nativeLibraryDir` with executable mode.
5. On first install and whenever the manifest/version changes, atomically copy
   the `python`, `node` and `licenses` asset trees to a hash-addressed directory
   under `<filesDir>/runtime-toolchains/bundles`. Never copy mutable state into
   that bundle or reuse the bundle directory as state.
6. Keep writable state at `<filesDir>/runtime-toolchains/state` across bundle
   updates.
7. Start Python or Node through `libvantaloom_python.so` with this environment:

```text
VANTALOOM_APP_FILES_DIR=<filesDir>
VANTALOOM_RUNTIME_BUNDLE_DIR=<filesDir>/runtime-toolchains/bundles/bundle-<hash>
VANTALOOM_RUNTIME_DATA_DIR=<filesDir>/runtime-toolchains/state
VANTALOOM_MOBILE_RUNTIME_MANIFEST=<filesDir>/runtime-toolchains/bundles/bundle-<hash>/manifest.json
```

Set the child process working directory to the active project under the app's
`filesDir`, never to `filesDir` itself or any directory overlapping the active
bundle. Node's fixed Permission Model grants read-only access to the bundle and
read/write access only to runtime state and that working directory.

`libvantaloom_python.so` is the only advertised Python/Node multicall entry.
Pass `python`, `pip`, `node`, `npm` or `npx` as its first argument.
`libvantaloom_node.so` is an internal runtime binary and bypasses policy if
launched directly, so it must never be exposed as an engine entry point or a
managed command symlink. The manifest contains the launcher `entryArgs`, exact
directory environment contract, fixed Node Permission Model arguments,
integrity records and `loopbackEnforced` policy consumed by the backend. Each
engine also reports `dataBytes`, `nativeBytes` and their `bundleBytes` total so
the settings UI can show the real installed cost rather than only the entry
binary.

`libvantaloom_go.so` is a separate direct entry and supports:

```text
libvantaloom_go.so --version
libvantaloom_go.so run <file.go> [args...]
```

Suggested additions to the existing APK CI, without committing binaries:

```sh
sdkmanager "ndk;26.0.10792818"
OUT_DIR="$PWD/runtime-engines/dist" bash runtime-engines/scripts/build-all.sh
rsync -a runtime-engines/dist/jniLibs/arm64-v8a/ android/app/src/main/jniLibs/arm64-v8a/
rsync -a --delete runtime-engines/dist/assets/runtime-engines/ android/app/src/main/assets/runtime-engines/
```

These copies exist only in the clean CI checkout. Cache verified downloads and,
when useful, the final stage under a key covering every source, patch, script and
toolchain version.

## Host validation

The package/launcher contract and Go policy tests run without Android:

```sh
python3 -m unittest discover -s runtime-engines/tests -p 'test_*.py' -v
python3 -m py_compile runtime-engines/scripts/package_runtime.py \
  runtime-engines/tests/test_package_runtime.py
(cd runtime-engines/go-runner && go test ./...)
```

The static launcher tests assert that Python reads its home from the bundle,
extension links and caches stay in state, and every managed Node entry passes
through the launcher with a cwd contained by the app-files root.

## Validation still required on a device

The scripts perform lock validation, safe archive extraction, ELF ABI/type and
interpreter checks, asset scans that reject ELF content, JNI filename checks,
and per-file manifest hashes. The following remain real-device gates:

- Android 10, 12, 14 and 15 arm64 devices can execute the launcher, its internal
  Node runtime and the Go entry from `nativeLibraryDir` after APK install/update.
- Python imports `_ssl`, `_sqlite3`, `_ctypes` and other aliased extensions
  through the generated symlinks; `pip --version` and a pure-Python install work.
- `python -m http.server 8000 --bind 127.0.0.1` works in the foreground service,
  while binding `0.0.0.0` or a LAN address raises `PermissionError`.
- `node -e` and an HTTP server on `127.0.0.1` work; TCP/UDP binds to wildcard or
  LAN addresses return `EACCES`; `require()` of a `.node` addon is rejected.
- npm can install a pure-JavaScript server package with lifecycle scripts
  disabled, then its JavaScript entry can be launched explicitly through the
  managed Node entry. Native addons, downloaded executables and child-process
  based scripts fail clearly rather than weakening the writable-code policy.
- The Go runner executes a simple source file and passes `os.Args`; imports of
  `unsafe`, `syscall`, `os/exec`, `net.Listen` and `http.ListenAndServe` fail.
- Background servers survive only under the app's user-visible special-use
  foreground service and stop when that service is stopped.
- APK installation passes 16 KiB page-size checks on Android 15-class devices.
