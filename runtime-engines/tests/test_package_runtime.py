import copy
import hashlib
import importlib.util
import inspect
import json
import struct
import tarfile
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from unittest import mock


RUNTIME_ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = RUNTIME_ROOT / "scripts" / "package_runtime.py"
LAUNCHER_PATH = RUNTIME_ROOT / "src" / "launcher.c"
BUILD_ALL_PATH = RUNTIME_ROOT / "scripts" / "build-all.sh"
SPEC = importlib.util.spec_from_file_location("package_runtime", MODULE_PATH)
package_runtime = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(package_runtime)


class FakeHTTPResponse(BytesIO):
    def __init__(self, data: bytes, url: str = "https://example.invalid/source"):
        super().__init__(data)
        self.url = url

    def __enter__(self):
        return self

    def __exit__(self, _error_type, _error, _traceback):
        self.close()

    def geturl(self) -> str:
        return self.url


class ResettingHTTPResponse(FakeHTTPResponse):
    def __init__(self, url: str):
        super().__init__(b"", url)
        self.read_count = 0

    def read(self, _size: int = -1) -> bytes:
        self.read_count += 1
        if self.read_count == 1:
            return b"partial"
        raise ConnectionResetError("reset during download")


class PackageRuntimeTests(unittest.TestCase):
    def test_locked_sources_and_policy_patch(self):
        lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
        self.assertEqual("3.14.6", lock["artifacts"]["python"]["version"])
        self.assertEqual("24.18.0", lock["artifacts"]["node"]["version"])
        self.assertEqual(
            "38bbe77d3167b5cd554e03b1021324926f09f3825202b065951dd7638e9c37e5",
            lock["artifacts"]["python"]["sha256"],
        )
        self.assertEqual(
            "e94afde24db08e0c564ee7110a2d5aab51ee0059382c9fd8233c54eec47b28f9",
            lock["artifacts"]["node"]["sha256"],
        )

    def test_locked_node_patches_disable_v8_traps_fail_closed(self):
        lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
        patches = lock["artifacts"]["node"]["patches"]
        self.assertEqual(
            [
                "src/patches/node-disable-v8-trap-handler.patch",
                "src/patches/node-loopback-bind.patch",
            ],
            [patch["path"] for patch in patches],
        )
        self.assertEqual(
            "810c507bcfba2be8c987698c7e9cdcc3d9ccf1b115ce46f71d68452a84fdb4de",
            patches[0]["sha256"],
        )

        trap_patch = (RUNTIME_ROOT / patches[0]["path"]).read_text(
            encoding="utf-8"
        )
        self.assertIn("-// Arm64 (non-simulator) on Linux", trap_patch)
        self.assertIn(
            "+// Android runtime builds must keep V8 trap handling disabled.",
            trap_patch,
        )
        self.assertEqual(
            1, trap_patch.count("#define V8_TRAP_HANDLER_SUPPORTED false")
        )
        self.assertIn("-#define V8_TRAP_HANDLER_SUPPORTED true", trap_patch)

        build_script = BUILD_ALL_PATH.read_text(encoding="utf-8")
        self.assertNotIn("android-patches/trap-handler.h.patch", build_script)
        for patch in patches:
            self.assertIn(f'$RUNTIME_ROOT/{patch["path"]}', build_script)
        self.assertEqual(2, build_script.count("patch --batch --forward -F 0 -p1"))
        self.assertIn("TRAP_HANDLER_DEFINITION_COUNT", build_script)
        self.assertIn("V8_TRAP_HANDLER_VIA_SIMULATOR", build_script)

    def test_build_script_separates_node_host_and_target_toolchains(self):
        build_script = BUILD_ALL_PATH.read_text(encoding="utf-8")
        for assignment in (
            'CC_host="${CC_host:-cc}"',
            'CXX_host="${CXX_host:-c++}"',
            'AR_host="${AR_host:-ar}"',
            'LINK_host="${LINK_host:-$CXX_host}"',
            "export CC_host CXX_host AR_host LINK_host",
            'CC_target="$CC"',
            'CXX_target="$CXX"',
            'AR_target="$AR"',
            'LINK_target="$CXX"',
            "export CC_target CXX_target AR_target LINK_target",
        ):
            self.assertIn(assignment, build_script)
        self.assertIn('"host_arch": expected_host_arch', build_script)
        self.assertIn('"target_arch": "arm64"', build_script)
        self.assertIn('"want_separate_host_toolset": 1', build_script)
        self.assertIn('"aarch64-linux-android" in value', build_script)

    def test_python_dependency_license_locks_are_exact(self):
        lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
        components = {
            component["id"]: component
            for component in lock["artifacts"]["python"]["bundledComponents"]
        }
        expected = {
            "bzip2": (
                "1.0.8-3",
                "bzip2-1.0.6",
                "LICENSE",
                "https://gitlab.com/bzip2/bzip2/-/raw/bzip2-1.0.8/LICENSE",
                1896,
                "c6dbbf828498be844a89eaa3b84adbab3199e342eb5cb2ed2f0d4ba7ec0f38a3",
            ),
            "libffi": (
                "3.4.4-3",
                "MIT",
                "LICENSE",
                "https://raw.githubusercontent.com/libffi/libffi/v3.4.4/LICENSE",
                1132,
                "2c9c2acb9743e6b007b91350475308aee44691d96aa20eacef8e199988c8c388",
            ),
            "openssl": (
                "3.5.7-0",
                "Apache-2.0",
                "LICENSE.txt",
                "https://raw.githubusercontent.com/openssl/openssl/openssl-3.5.7/LICENSE.txt",
                10175,
                "7d5450cb2d142651b8afa315b5f238efc805dad827d91ba367d8516bc9d49e7a",
            ),
            "sqlite": (
                "3.50.4-0",
                "blessing",
                "LICENSE.md",
                "https://raw.githubusercontent.com/sqlite/sqlite/version-3.50.4/LICENSE.md",
                3864,
                "a7a58d6022c090c528e3167026167b3141d682efc6a3c188b473ba59f1788fc7",
            ),
            "xz": (
                "5.4.6-1",
                "LicenseRef-XZ-libLZMA-Public-Domain",
                "COPYING",
                "https://raw.githubusercontent.com/tukaani-project/xz/v5.4.6/COPYING",
                3347,
                "29a1e305b2e34eefe5d4602d00cde1d528b71c5d9f2eec5106972cf6ddb6f73f",
            ),
            "zstd": (
                "1.5.7-2",
                "BSD-3-Clause",
                "LICENSE",
                "https://raw.githubusercontent.com/facebook/zstd/v1.5.7/LICENSE",
                1549,
                "7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8",
            ),
            "expat": (
                "2.8.1",
                "MIT",
                "expat-2.8.1-LICENSE",
                "https://raw.githubusercontent.com/python/cpython/v3.14.6/Modules/expat/COPYING",
                1144,
                "31b15de82aa19a845156169a17a5488bf597e561b2c318d159ed583139b25e87",
            ),
            "libmpdec": (
                "2.5.1",
                "BSD-2-Clause",
                "libmpdec-2.5.1-LICENSE.h",
                "https://raw.githubusercontent.com/python/cpython/v3.14.6/"
                "Modules/_decimal/libmpdec/crt.h",
                1719,
                "7d31f1d0dd73b62964dab0f7a1724473bf87f1f95d8febf0b40c15430ae9a47c",
            ),
            "hacl-star": (
                "8ba599b2f6c9701b3dc961db895b0856a2210f76",
                "MIT",
                "hacl-star-8ba599b2f6c9-MIT-NOTICE.c",
                "https://raw.githubusercontent.com/python/cpython/v3.14.6/"
                "Modules/_hacl/Hacl_Hash_SHA1.c",
                15783,
                "ce08721d491f3b8a9bd4cde6c27bfcc8fc01471512ccca4bd3c0b764cb551d29",
            ),
        }
        self.assertEqual(set(expected), set(components))
        for component_id, values in expected.items():
            component = components[component_id]
            license_info = component["license"]
            self.assertEqual(values[0], component["version"])
            self.assertEqual(values[1], license_info["spdx"])
            self.assertEqual(values[2], license_info["filename"])
            self.assertEqual(values[3], license_info["url"])
            self.assertEqual(values[4], license_info["size"])
            self.assertEqual(values[5], license_info["sha256"])
        self.assertEqual("liblzma", components["xz"]["bundledLibrary"])
        self.assertEqual("static", components["xz"]["linkage"])

    def test_python_dependency_license_lock_fails_closed(self):
        lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
        python_artifact = lock["artifacts"]["python"]
        mutations = (
            (
                "missing hash",
                lambda value: value["bundledComponents"][0]["license"].pop(
                    "sha256"
                ),
            ),
            (
                "non HTTPS URL",
                lambda value: value["bundledComponents"][1]["license"].__setitem__(
                    "url", "http://example.invalid/LICENSE"
                ),
            ),
            (
                "unsafe filename",
                lambda value: value["bundledComponents"][2]["license"].__setitem__(
                    "filename", "../LICENSE"
                ),
            ),
            (
                "duplicate id",
                lambda value: value["bundledComponents"][1].__setitem__(
                    "id", "bzip2"
                ),
            ),
            (
                "dynamic liblzma",
                lambda value: value["bundledComponents"][4].__setitem__(
                    "linkage", "dynamic"
                ),
            ),
            (
                "zstd GPL branch",
                lambda value: value["bundledComponents"][5]["license"].__setitem__(
                    "spdx", "BSD-3-Clause OR GPL-2.0-only"
                ),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label):
                changed = copy.deepcopy(python_artifact)
                mutate(changed)
                with self.assertRaises(package_runtime.PackagingError):
                    package_runtime.validate_python_bundled_components(changed)

    def test_python_dependency_licenses_are_verified_staged_and_referenced(self):
        lock = copy.deepcopy(
            package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
        )
        python_artifact = lock["artifacts"]["python"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache = root / "cache"
            stage = root / "stage"
            payloads = {}
            for component in python_artifact["bundledComponents"]:
                component_id = component["id"]
                payload = f"locked license for {component_id}\n".encode()
                payloads[component_id] = payload
                license_info = component["license"]
                license_info["url"] = (
                    f"https://licenses.example/{component_id}/"
                    f"{license_info['filename']}"
                )
                license_info["size"] = len(payload)
                license_info["sha256"] = hashlib.sha256(payload).hexdigest()
                cached = (
                    cache
                    / "licenses"
                    / "python-dependencies"
                    / component_id
                    / license_info["filename"]
                )
                cached.parent.mkdir(parents=True)
                cached.write_bytes(payload)

            sources = package_runtime.fetch_python_dependency_licenses(
                cache,
                python_artifact,
            )
            package_runtime.stage_python_dependency_licenses(
                sources,
                python_artifact,
                stage,
                lock["target"]["sourceDateEpoch"],
            )
            package_runtime.write_third_party_notice(lock, stage)

            notice_path = (
                stage
                / "assets"
                / "runtime-engines"
                / "licenses"
                / "THIRD_PARTY_COMPONENTS.json"
            )
            notice = json.loads(notice_path.read_text(encoding="utf-8"))
            entries = {
                component["id"]: component
                for component in notice["components"]
                if "id" in component
            }
            for component in python_artifact["bundledComponents"]:
                component_id = component["id"]
                relative = entries[component_id]["licenseFile"]
                packaged = notice_path.parent / relative
                self.assertEqual(payloads[component_id], packaged.read_bytes())
                self.assertEqual(
                    component["license"]["url"],
                    entries[component_id]["licenseSource"],
                )
            self.assertEqual("liblzma", entries["xz"]["bundledLibrary"])
            self.assertEqual("static", entries["xz"]["linkage"])
            package_runtime.verify_staged_python_dependency_licenses(
                lock,
                stage,
            )
            tampered = (
                notice_path.parent
                / entries["expat"]["licenseFile"]
            )
            tampered.write_bytes(b"tampered")
            with self.assertRaises(package_runtime.PackagingError):
                package_runtime.verify_staged_python_dependency_licenses(
                    lock,
                    stage,
                )

    def test_tampered_cached_python_dependency_license_is_rejected(self):
        lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
        python_artifact = lock["artifacts"]["python"]
        component = python_artifact["bundledComponents"][0]
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            cached = (
                cache
                / "licenses"
                / "python-dependencies"
                / component["id"]
                / component["license"]["filename"]
            )
            cached.parent.mkdir(parents=True)
            cached.write_bytes(b"tampered")
            with self.assertRaises(package_runtime.PackagingError):
                package_runtime.fetch_python_dependency_licenses(
                    cache,
                    python_artifact,
                )

    def test_transient_https_download_is_retried_with_fresh_partial(self):
        payload = b"verified license\n"
        artifact = {
            "filename": "LICENSE",
            "url": "https://example.invalid/LICENSE",
            "size": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            response = FakeHTTPResponse(payload, artifact["url"])
            with (
                mock.patch.object(
                    package_runtime.urllib.request,
                    "urlopen",
                    side_effect=[ResettingHTTPResponse(artifact["url"]), response],
                ) as urlopen,
                mock.patch.object(package_runtime.time, "sleep") as sleep,
            ):
                destination = package_runtime.fetch_artifact(cache, artifact)
            self.assertEqual(payload, destination.read_bytes())
            self.assertEqual(2, urlopen.call_count)
            sleep.assert_called_once_with(
                package_runtime.DOWNLOAD_RETRY_DELAYS[0]
            )
            self.assertFalse((cache / "LICENSE.partial").exists())

    def test_hash_mismatch_is_not_retried(self):
        expected = b"expected license\n"
        artifact = {
            "filename": "LICENSE",
            "url": "https://example.invalid/LICENSE",
            "size": len(expected),
            "sha256": hashlib.sha256(expected).hexdigest(),
        }
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            with (
                mock.patch.object(
                    package_runtime.urllib.request,
                    "urlopen",
                    return_value=FakeHTTPResponse(b"wrong contents\n"),
                ) as urlopen,
                mock.patch.object(package_runtime.time, "sleep") as sleep,
            ):
                with self.assertRaises(package_runtime.PackagingError):
                    package_runtime.fetch_artifact(cache, artifact)
            self.assertEqual(1, urlopen.call_count)
            sleep.assert_not_called()
            self.assertFalse((cache / "LICENSE").exists())
            self.assertFalse((cache / "LICENSE.partial").exists())

    def test_android_ndk_notice_is_unconditional_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "stage"
            notice = root / "NOTICE"
            notice.write_bytes(b"Android NDK notices\n")
            package_runtime.stage_android_ndk_notice(
                notice,
                stage,
                1782172800,
            )
            packaged = (
                stage
                / "assets"
                / "runtime-engines"
                / "licenses"
                / "android-ndk"
                / "NOTICE"
            )
            self.assertEqual(notice.read_bytes(), packaged.read_bytes())
            with self.assertRaises(package_runtime.PackagingError):
                package_runtime.stage_android_ndk_notice(
                    root / "missing-NOTICE",
                    stage,
                    1782172800,
                )

        build_script = BUILD_ALL_PATH.read_text(encoding="utf-8")
        self.assertIn('NDK_NOTICE="$NDK_ROOT/NOTICE"', build_script)
        self.assertIn('if [[ ! -f "$NDK_NOTICE" ]]', build_script)
        self.assertIn('--ndk-notice "$NDK_NOTICE"', build_script)
        self.assertNotIn("LIBCXX_ARGUMENTS+=(--ndk-notice", build_script)
        finalize_source = inspect.getsource(package_runtime.finalize)
        self.assertLess(
            finalize_source.index("stage_android_ndk_notice"),
            finalize_source.index("if libcxx is not None"),
        )

    def test_extension_alias_is_deterministic_and_jni_safe(self):
        alias = package_runtime.extension_alias(
            7, "_ssl.cpython-314-aarch64-linux-android.so"
        )
        self.assertEqual("libvantaloom_pyext_007__ssl.so", alias)
        self.assertRegex(alias, package_runtime.JNI_NAME_RE)

    def test_python_stage_keeps_elf_out_of_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "python.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                self.add_tar_file(bundle, "prefix/lib/libpython3.14.so", b"\x7fELFpython")
                self.add_tar_file(bundle, "prefix/lib/libssl_python.so", b"\x7fELFssl")
                self.add_tar_file(bundle, "prefix/include/python3.14/Python.h", b"header")
                self.add_tar_file(
                    bundle,
                    "prefix/lib/python3.14/encodings/__init__.py",
                    b"# encodings\n",
                )
                self.add_tar_file(
                    bundle,
                    "prefix/lib/python3.14/lib-dynload/_ssl.cpython-314-aarch64-linux-android.so",
                    b"\x7fELFextension",
                )
                self.add_tar_file(
                    bundle,
                    "prefix/lib/python3.14/LICENSE.txt",
                    b"Python license",
                )

            lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
            extensions = package_runtime.stage_python(
                archive,
                lock["artifacts"]["python"],
                root / "stage",
                root / "work",
                lock["target"]["sourceDateEpoch"],
            )
            self.assertEqual(1, len(extensions))
            self.assertTrue(
                (root / "stage" / "jniLibs" / "arm64-v8a" / extensions[0]["nativeLibrary"]).is_file()
            )
            for asset in (root / "stage" / "assets").rglob("*"):
                if asset.is_file():
                    self.assertNotEqual(b"\x7fELF", asset.read_bytes()[:4])

    def test_archive_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "unsafe.tar"
            with tarfile.open(archive, "w") as bundle:
                self.add_tar_file(bundle, "../escape", b"bad")
            with self.assertRaises(package_runtime.PackagingError):
                package_runtime.extract_archive(archive, root / "out", "root")

    def test_android_elf_contract(self):
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / "libentry.so"
            interpreter = b"/system/bin/linker64\0"
            ident = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8)
            header = struct.pack(
                "<16sHHIQQQIHHHHHH",
                ident,
                package_runtime.ET_DYN,
                package_runtime.EM_AARCH64,
                1,
                0,
                64,
                0,
                0,
                64,
                56,
                1,
                0,
                0,
                0,
            )
            program = struct.pack(
                "<IIQQQQQQ", 3, 0, 120, 0, 0, len(interpreter), len(interpreter), 1
            )
            data = bytearray(120 + len(interpreter))
            data[: len(header)] = header
            data[64 : 64 + len(program)] = program
            data[120:] = interpreter
            binary.write_bytes(data)
            package_runtime.assert_android_elf(binary, executable=True)

    def test_npm_bin_links_get_android_native_shebang(self):
        with tempfile.TemporaryDirectory() as temporary:
            npm = Path(temporary)
            fix_bin = npm / "node_modules" / "bin-links" / "lib" / "fix-bin.js"
            fix_bin.parent.mkdir(parents=True)
            fix_bin.write_text(
                "const fixBin = (file, mode = execMode) => chmod(file, mode)\n"
                "  .then(() => isWindowsHashbangFile(file))\n"
                "  .then(isWHB => isWHB ? dos2Unix(file) : null)\n",
                encoding="utf-8",
            )
            package_runtime.patch_npm_android_bin_links(npm, 1782172800)
            patched = fix_bin.read_text(encoding="utf-8")
            self.assertIn("process.platform !== 'android'", patched)
            self.assertIn("VANTALOOM_NODE_LAUNCHER", patched)
            self.assertIn("#!${launcher} node", patched)
            self.assertNotIn("#!${process.execPath}", patched)

    def test_manifest_declares_split_directories_and_launcher_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary) / "stage"
            (stage / "assets" / "runtime-engines" / "python").mkdir(
                parents=True
            )
            (stage / "assets" / "runtime-engines" / "node").mkdir()
            lock = package_runtime.load_lock(RUNTIME_ROOT / "sources.lock.json")
            manifest = package_runtime.build_runtime_manifest(lock, stage)

        self.assertEqual(
            {
                "appFiles": "VANTALOOM_APP_FILES_DIR",
                "bundle": "VANTALOOM_RUNTIME_BUNDLE_DIR",
                "state": "VANTALOOM_RUNTIME_DATA_DIR",
            },
            manifest["directoryEnvironment"],
        )
        self.assertEqual(
            [
                "VANTALOOM_APP_FILES_DIR",
                "VANTALOOM_RUNTIME_BUNDLE_DIR",
                "VANTALOOM_RUNTIME_DATA_DIR",
            ],
            manifest["launcher"]["requiredEnvironment"],
        )
        python = manifest["engines"]["python"]
        node = manifest["engines"]["node"]
        self.assertEqual("libvantaloom_python.so", python["nativeLibrary"])
        self.assertEqual(["python"], python["entryArgs"])
        self.assertEqual("python", python["bundleRoot"])
        self.assertEqual("libvantaloom_python.so", node["nativeLibrary"])
        self.assertEqual(["node"], node["entryArgs"])
        self.assertEqual("libvantaloom_node.so", node["runtimeNativeLibrary"])
        self.assertEqual("node", node["bundleRoot"])
        self.assertIn(
            "--allow-fs-read=<runtime-bundle-dir>",
            node["fixedEntryArguments"],
        )
        self.assertIn(
            "--allow-fs-write=<runtime-state-dir>",
            node["fixedEntryArguments"],
        )
        self.assertNotIn("NPM_CONFIG_CACHE", node["settingsEnvironment"])
        self.assertNotIn("PYTHONUSERBASE", python["settingsEnvironment"])

    def test_launcher_source_enforces_bundle_state_and_node_contract(self):
        source = LAUNCHER_PATH.read_text(encoding="utf-8")
        self.assertIn(
            '#define APP_FILES_DIR_ENV "VANTALOOM_APP_FILES_DIR"', source
        )
        self.assertIn(
            '#define BUNDLE_DIR_ENV "VANTALOOM_RUNTIME_BUNDLE_DIR"', source
        )
        self.assertIn(
            '#define STATE_DIR_ENV "VANTALOOM_RUNTIME_DATA_DIR"', source
        )
        self.assertIn(
            'paths->bundle_dir,\n                  "python"', source
        )
        self.assertIn(
            'paths->state_dir,\n                  "python-lib-dynload"', source
        )
        self.assertIn(
            'paths, "PYTHONPYCACHEPREFIX", "cache/python/pycache"', source
        )
        self.assertIn(
            'set_managed_subdirectory(paths, "PIP_CACHE_DIR", "cache/pip")',
            source,
        )
        self.assertIn('set_managed_environment("PIP_USER", "1")', source)
        self.assertIn(
            'set_managed_environment("NPM_CONFIG_IGNORE_SCRIPTS", "true")',
            source,
        )
        self.assertIn('validate_npm_arguments(argument_count, arguments)', source)
        self.assertIn('"--no-ignore-scripts"', source)
        self.assertIn(
            'set_managed_subdirectory(paths, "NPM_CONFIG_CACHE", "cache/npm")',
            source,
        )
        self.assertIn(
            '"VANTALOOM_NODE_LAUNCHER", paths->launcher', source
        )
        self.assertIn(
            '"python", "python3", "pip", "pip3", "node", "npm", "npx"',
            source,
        )
        self.assertIn("ensure_symlink(link_path, paths->launcher)", source)
        self.assertNotIn("ensure_symlink(node_link, paths->node)", source)
        self.assertIn('policy->arguments[0] = "--permission"', source)
        self.assertIn(
            "path_is_strictly_within(paths->app_files_dir, cwd)", source
        )
        self.assertIn("paths_overlap(cwd, paths->bundle_dir)", source)
        self.assertIn('"--env-file"', source)
        self.assertIn('"--no-permission"', source)

    @staticmethod
    def add_tar_file(bundle: tarfile.TarFile, name: str, data: bytes) -> None:
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o644
        bundle.addfile(info, BytesIO(data))


if __name__ == "__main__":
    unittest.main()
