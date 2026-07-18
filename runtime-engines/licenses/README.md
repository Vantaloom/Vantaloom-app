# Runtime engine license handling

`sources.lock.json` pins the SPDX expression, upstream license URL, byte size
and SHA256 for every independently downloaded Python dependency notice.

The build copies archive-carried notices and downloads the separately locked
Python dependency notices into the generated assets:

- `licenses/python/LICENSE.txt` from the CPython Android package.
- `licenses/python-dependencies/<id>/...` for bzip2, libffi, OpenSSL, SQLite,
  statically linked XZ liblzma, zstd, Expat, libmpdec and HACL*. Each file is
  accepted only when both its locked size and SHA256 match.
- `licenses/node/LICENSE` from the Node.js source distribution. Node's file
  includes the notices for its bundled dependencies.
- `licenses/npm/LICENSE` from the npm distribution bundled with Node.js.
- `licenses/yaegi/LICENSE` from the hash-locked Yaegi module.
- `licenses/go/LICENSE` from the Go toolchain used for the runner build.
- `licenses/THIRD_PARTY_COMPONENTS.json`, generated from the lock file, records
  every Python dependency above and points to its packaged notice file.

The NDK is pinned to `26.0.10792818`. Its `NOTICE` file is required and always
copied to `licenses/android-ndk/NOTICE`, regardless of whether the final Node
binary requires `libc++_shared.so`.
