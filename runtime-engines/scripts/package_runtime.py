#!/usr/bin/env python3
"""Fetch, stage and verify Vantaloom's immutable Android runtime engines."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


RUNTIME_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = RUNTIME_ROOT / "sources.lock.json"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMPONENT_ID_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
JNI_NAME_RE = re.compile(r"^lib[A-Za-z0-9_+.-]+\.so$")
ELF_MAGIC = b"\x7fELF"
EM_AARCH64 = 183
ET_DYN = 3
PYTHON_DEPENDENCY_LICENSES = {
    "bzip2": ("1.0.8-3", "bzip2-1.0.6"),
    "libffi": ("3.4.4-3", "MIT"),
    "openssl": ("3.5.7-0", "Apache-2.0"),
    "sqlite": ("3.50.4-0", "blessing"),
    "xz": ("5.4.6-1", "LicenseRef-XZ-libLZMA-Public-Domain"),
    "zstd": ("1.5.7-2", "BSD-3-Clause"),
    "expat": ("2.8.1", "MIT"),
    "libmpdec": ("2.5.1", "BSD-2-Clause"),
    "hacl-star": (
        "8ba599b2f6c9701b3dc961db895b0856a2210f76",
        "MIT",
    ),
}
DOWNLOAD_RETRY_DELAYS = (1.0, 2.0, 4.0)


class PackagingError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise PackagingError(message)


def validate_locked_download(value: Any, label: str) -> None:
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    url = value.get("url")
    parsed_url = urllib.parse.urlparse(str(url))
    if (
        not isinstance(url, str)
        or parsed_url.scheme != "https"
        or not parsed_url.netloc
    ):
        fail(f"{label} must use an HTTPS URL")
    filename = value.get("filename")
    if (
        not isinstance(filename, str)
        or "\\" in filename
        or PurePosixPath(filename).name != filename
    ):
        fail(f"{label} filename is invalid")
    size = value.get("size")
    if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
        fail(f"{label} size is invalid")
    sha256 = value.get("sha256")
    if not isinstance(sha256, str) or not SHA256_RE.fullmatch(sha256):
        fail(f"{label} sha256 is invalid")


def validate_python_bundled_components(artifact: dict[str, Any]) -> None:
    components = artifact.get("bundledComponents")
    if not isinstance(components, list):
        fail("Lock Python bundledComponents must be a list")
    component_ids: list[str] = []
    for component in components:
        if not isinstance(component, dict):
            fail("Lock Python bundled component must be an object")
        component_id = component.get("id")
        if not isinstance(component_id, str) or not COMPONENT_ID_RE.fullmatch(
            component_id
        ):
            fail("Lock Python bundled component id is invalid")
        if component_id in component_ids:
            fail(f"Duplicate Python bundled component id: {component_id}")
        component_ids.append(component_id)
        expected = PYTHON_DEPENDENCY_LICENSES.get(component_id)
        if expected is None:
            fail(f"Unexpected Python bundled component: {component_id}")
        if not isinstance(component.get("name"), str) or not component["name"]:
            fail(f"Lock Python bundled component {component_id} name is invalid")
        if component.get("version") != expected[0]:
            fail(f"Lock Python bundled component {component_id} version is invalid")
        license_info = component.get("license")
        validate_locked_download(
            license_info,
            f"Lock Python bundled component {component_id} license",
        )
        if license_info.get("spdx") != expected[1]:
            fail(f"Lock Python bundled component {component_id} SPDX is invalid")

    if component_ids != list(PYTHON_DEPENDENCY_LICENSES):
        fail("Lock Python bundled components are incomplete or out of order")
    xz = components[component_ids.index("xz")]
    if xz.get("bundledLibrary") != "liblzma" or xz.get("linkage") != "static":
        fail("Lock XZ component must describe statically bundled liblzma only")


def load_lock(path: Path) -> dict[str, Any]:
    try:
        lock = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"Unable to read lock file {path}: {exc}")

    if lock.get("schemaVersion") != 1:
        fail("sources.lock.json schemaVersion must be 1")

    target = lock.get("target")
    if not isinstance(target, dict):
        fail("Lock target must be an object")
    expected_target = {
        "abi": "arm64-v8a",
        "androidTriple": "aarch64-linux-android",
        "minApi": 24,
        "targetSdk": 34,
    }
    for key, expected in expected_target.items():
        if target.get(key) != expected:
            fail(f"Lock target {key} must be {expected!r}")
    if not re.fullmatch(r"\d+\.\d+\.\d+", str(target.get("ndkVersion", ""))):
        fail("Lock target ndkVersion is invalid")
    if not isinstance(target.get("sourceDateEpoch"), int):
        fail("Lock target sourceDateEpoch must be an integer")

    go_runner = lock.get("goRunner")
    if not isinstance(go_runner, dict) or go_runner.get("version") != "0.1.0":
        fail("Lock goRunner metadata is invalid")
    yaegi = go_runner.get("yaegi")
    if not isinstance(yaegi, dict):
        fail("Lock Yaegi metadata is missing")
    if (
        yaegi.get("module") != "github.com/traefik/yaegi"
        or yaegi.get("version") != "v0.16.1"
        or urllib.parse.urlparse(str(yaegi.get("url", ""))).scheme != "https"
        or not isinstance(yaegi.get("size"), int)
        or not SHA256_RE.fullmatch(str(yaegi.get("sha256", "")))
        or not str(yaegi.get("goModuleSum", "")).startswith("h1:")
        or not str(yaegi.get("goModSum", "")).startswith("h1:")
        or not isinstance(yaegi.get("license"), dict)
    ):
        fail("Lock Yaegi source or license metadata is invalid")

    artifacts = lock.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != {"python", "node"}:
        fail("Lock artifacts must contain exactly python and node")
    for artifact_id, artifact in artifacts.items():
        if not isinstance(artifact, dict):
            fail(f"Lock artifact {artifact_id} must be an object")
        validate_locked_download(artifact, f"Lock artifact {artifact_id}")
        license_info = artifact.get("license")
        if not isinstance(license_info, dict) or not license_info.get("spdx"):
            fail(f"Lock artifact {artifact_id} license metadata is missing")
        for patch in artifact.get("patches", []):
            if not isinstance(patch, dict):
                fail(f"Lock artifact {artifact_id} patch metadata is invalid")
            patch_relative = patch.get("path")
            patch_hash = patch.get("sha256")
            if (
                not isinstance(patch_relative, str)
                or PurePosixPath(patch_relative).is_absolute()
                or ".." in PurePosixPath(patch_relative).parts
                or not isinstance(patch_hash, str)
                or not SHA256_RE.fullmatch(patch_hash)
            ):
                fail(f"Lock artifact {artifact_id} patch lock is invalid")
            patch_path = path.parent / PurePosixPath(patch_relative)
            if not patch_path.is_file() or sha256_file(patch_path) != patch_hash:
                fail(f"Locked patch is missing or modified: {patch_path}")
    validate_python_bundled_components(artifacts["python"])
    return lock


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_artifact(path: Path, artifact: dict[str, Any]) -> None:
    actual_size = path.stat().st_size
    if actual_size != artifact["size"]:
        fail(
            f"Size mismatch for {path}: expected {artifact['size']}, "
            f"got {actual_size}"
        )
    actual_hash = sha256_file(path)
    if actual_hash != artifact["sha256"]:
        fail(
            f"SHA256 mismatch for {path}: expected {artifact['sha256']}, "
            f"got {actual_hash}"
        )


def fetch_artifact(cache_dir: Path, artifact: dict[str, Any]) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    destination = cache_dir / artifact["filename"]
    if destination.exists():
        verify_artifact(destination, artifact)
        return destination

    partial = destination.with_suffix(destination.suffix + ".partial")
    if partial.exists():
        fail(f"Refusing to reuse partial download: {partial}")

    request = urllib.request.Request(
        artifact["url"],
        headers={"User-Agent": "Vantaloom-runtime-packager/1"},
    )
    for attempt in range(len(DOWNLOAD_RETRY_DELAYS) + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                final_url = urllib.parse.urlparse(response.geturl())
                if final_url.scheme != "https":
                    fail(
                        f"Artifact redirected to non-HTTPS URL: "
                        f"{response.geturl()}"
                    )
                with partial.open("xb") as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
            verify_artifact(partial, artifact)
            partial.replace(destination)
            return destination
        except PackagingError:
            if partial.exists():
                partial.unlink()
            raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if partial.exists():
                partial.unlink()
            if attempt == len(DOWNLOAD_RETRY_DELAYS):
                raise
            time.sleep(DOWNLOAD_RETRY_DELAYS[attempt])
        except Exception:
            if partial.exists():
                partial.unlink()
            raise
    return destination


def fetch_python_dependency_licenses(
    cache_dir: Path,
    python_artifact: dict[str, Any],
) -> dict[str, Path]:
    licenses: dict[str, Path] = {}
    for component in python_artifact["bundledComponents"]:
        component_id = component["id"]
        licenses[component_id] = fetch_artifact(
            cache_dir / "licenses" / "python-dependencies" / component_id,
            component["license"],
        )
    return licenses


def python_dependency_license_relative_path(
    component: dict[str, Any],
) -> PurePosixPath:
    return (
        PurePosixPath("python-dependencies")
        / component["id"]
        / component["license"]["filename"]
    )


def stage_python_dependency_licenses(
    sources: dict[str, Path],
    python_artifact: dict[str, Any],
    stage_dir: Path,
    epoch: int,
) -> None:
    expected_ids = [
        component["id"] for component in python_artifact["bundledComponents"]
    ]
    if set(sources) != set(expected_ids):
        fail("Python dependency license sources are incomplete")
    license_root = stage_dir / "assets" / "runtime-engines" / "licenses"
    for component in python_artifact["bundledComponents"]:
        component_id = component["id"]
        license_info = component["license"]
        source = sources[component_id]
        if not source.is_file():
            fail(f"Python dependency license is missing: {component_id}")
        verify_artifact(source, license_info)
        write_bytes(
            license_root / python_dependency_license_relative_path(component),
            source.read_bytes(),
            executable=False,
            epoch=epoch,
        )


def ensure_empty_directory(path: Path) -> None:
    if path.exists() and any(path.iterdir()):
        fail(f"Output directory must be absent or empty: {path}")
    path.mkdir(parents=True, exist_ok=True)


def normalized_tar_path(name: str) -> PurePosixPath:
    if "\\" in name:
        fail(f"Archive member contains a backslash: {name}")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part == ".." for part in path.parts):
        fail(f"Unsafe archive member path: {name}")
    return path


def validate_tar_members(members: Iterable[tarfile.TarInfo]) -> None:
    for member in members:
        path = normalized_tar_path(member.name)
        if member.ischr() or member.isblk() or member.isfifo():
            fail(f"Unsupported special archive member: {member.name}")
        if member.issym():
            target = PurePosixPath(member.linkname)
            if target.is_absolute():
                fail(f"Unsafe absolute symlink in archive: {member.name}")
            resolved = path.parent / target
            if any(part == ".." for part in resolved.parts):
                fail(f"Unsafe symlink target in archive: {member.name}")
        if member.islnk():
            target = normalized_tar_path(member.linkname)
            if any(part == ".." for part in target.parts):
                fail(f"Unsafe hardlink target in archive: {member.name}")


def extract_archive(archive: Path, destination: Path, expected_root: str) -> Path:
    ensure_empty_directory(destination)
    with tarfile.open(archive, "r:*") as bundle:
        members = bundle.getmembers()
        validate_tar_members(members)
        try:
            bundle.extractall(destination, members=members, filter="data")
        except TypeError:
            bundle.extractall(destination, members=members)
    root = destination / expected_root
    if not root.is_dir():
        fail(f"Expected archive root is missing: {root}")
    return root


def tar_member_map(bundle: tarfile.TarFile) -> dict[str, tarfile.TarInfo]:
    result: dict[str, tarfile.TarInfo] = {}
    members = bundle.getmembers()
    validate_tar_members(members)
    for member in members:
        normalized = str(normalized_tar_path(member.name))
        if normalized == ".":
            continue
        if normalized in result:
            fail(f"Duplicate archive member: {normalized}")
        result[normalized] = member
    return result


def resolve_tar_file(
    bundle: tarfile.TarFile,
    members: dict[str, tarfile.TarInfo],
    name: str,
    seen: set[str] | None = None,
) -> bytes:
    normalized = str(normalized_tar_path(name))
    member = members.get(normalized)
    if member is None:
        fail(f"Archive member is missing: {normalized}")
    if seen is None:
        seen = set()
    if normalized in seen:
        fail(f"Archive link cycle at {normalized}")
    seen.add(normalized)
    if member.isfile():
        stream = bundle.extractfile(member)
        if stream is None:
            fail(f"Unable to read archive member: {normalized}")
        return stream.read()
    if member.issym():
        target = str(PurePosixPath(normalized).parent / member.linkname)
        return resolve_tar_file(bundle, members, target, seen)
    if member.islnk():
        return resolve_tar_file(bundle, members, member.linkname, seen)
    fail(f"Archive member is not a file: {normalized}")


def write_bytes(path: Path, data: bytes, executable: bool, epoch: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(0o755 if executable else 0o644)
    os.utime(path, (epoch, epoch))


def write_json(path: Path, value: Any, epoch: int) -> None:
    data = (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()
    write_bytes(path, data, executable=False, epoch=epoch)


def extension_alias(index: int, original_name: str) -> str:
    module_name = original_name.split(".", 1)[0]
    safe_module = re.sub(r"[^A-Za-z0-9_]", "_", module_name)[:48]
    alias = f"libvantaloom_pyext_{index:03d}_{safe_module}.so"
    if not JNI_NAME_RE.fullmatch(alias):
        fail(f"Generated invalid JNI library name: {alias}")
    return alias


def generate_extension_header(
    path: Path,
    extensions: list[dict[str, str]],
    epoch: int,
) -> None:
    lines = [
        "#ifndef VANTALOOM_PYTHON_EXTENSIONS_GENERATED_H",
        "#define VANTALOOM_PYTHON_EXTENSIONS_GENERATED_H",
        "",
        "static const char *const VANTALOOM_PYTHON_EXTENSIONS[][2] = {",
    ]
    for extension in extensions:
        original = json.dumps(extension["originalName"])
        native = json.dumps(extension["nativeLibrary"])
        lines.append(f"    {{{original}, {native}}},")
    lines.extend(
        [
            "};",
            "static const size_t VANTALOOM_PYTHON_EXTENSION_COUNT =",
            "    sizeof(VANTALOOM_PYTHON_EXTENSIONS) /",
            "    sizeof(VANTALOOM_PYTHON_EXTENSIONS[0]);",
            "",
            "#endif",
            "",
        ]
    )
    write_bytes(path, "\n".join(lines).encode(), executable=False, epoch=epoch)


def stage_python(
    archive: Path,
    artifact: dict[str, Any],
    stage_dir: Path,
    work_dir: Path,
    epoch: int,
) -> list[dict[str, str]]:
    stdlib_version = artifact["stdlibVersion"]
    lib_prefix = "prefix/lib/"
    stdlib_prefix = f"prefix/lib/python{stdlib_version}/"
    include_prefix = f"prefix/include/python{stdlib_version}/"
    native_dir = stage_dir / "jniLibs" / "arm64-v8a"
    python_assets = stage_dir / "assets" / "runtime-engines" / "python"
    build_include = work_dir / "python-prefix" / "include" / f"python{stdlib_version}"

    with tarfile.open(archive, "r:*") as bundle:
        members = tar_member_map(bundle)
        versioned_libpython = f"prefix/lib/libpython{stdlib_version}.so"
        native_members = [versioned_libpython]
        native_members.extend(
            sorted(
                name
                for name in members
                if name.startswith(lib_prefix)
                and "/" not in name[len(lib_prefix) :]
                and name.endswith("_python.so")
            )
        )
        for member_name in native_members:
            destination = native_dir / PurePosixPath(member_name).name
            write_bytes(
                destination,
                resolve_tar_file(bundle, members, member_name),
                executable=True,
                epoch=epoch,
            )

        include_count = 0
        for member_name in sorted(members):
            if not member_name.startswith(include_prefix):
                continue
            member = members[member_name]
            if member.isdir():
                continue
            relative = member_name[len(include_prefix) :]
            if not relative:
                continue
            write_bytes(
                build_include / PurePosixPath(relative),
                resolve_tar_file(bundle, members, member_name),
                executable=False,
                epoch=epoch,
            )
            include_count += 1
        if include_count == 0:
            fail("Python headers were not found in the verified archive")

        extension_names = sorted(
            name
            for name in members
            if name.startswith(stdlib_prefix + "lib-dynload/")
            and name.endswith(".so")
        )
        if not extension_names:
            fail("Python lib-dynload extensions were not found")
        extensions: list[dict[str, str]] = []
        for index, member_name in enumerate(extension_names):
            original_name = PurePosixPath(member_name).name
            alias = extension_alias(index, original_name)
            write_bytes(
                native_dir / alias,
                resolve_tar_file(bundle, members, member_name),
                executable=True,
                epoch=epoch,
            )
            extensions.append(
                {"originalName": original_name, "nativeLibrary": alias}
            )

        asset_file_count = 0
        for member_name in sorted(members):
            if not member_name.startswith(stdlib_prefix):
                continue
            member = members[member_name]
            if member.isdir():
                continue
            relative = member_name[len(stdlib_prefix) :]
            if not relative:
                continue
            if relative.startswith("lib-dynload/") and relative.endswith(".so"):
                continue
            if relative.endswith(".so"):
                fail(f"Unexpected Python ELF outside lib-dynload: {member_name}")
            if "/__pycache__/" in f"/{relative}" or relative.endswith((".pyc", ".pyo")):
                continue
            write_bytes(
                python_assets / "lib" / f"python{stdlib_version}" / PurePosixPath(relative),
                resolve_tar_file(bundle, members, member_name),
                executable=False,
                epoch=epoch,
            )
            asset_file_count += 1
        if asset_file_count == 0:
            fail("Python standard library assets were not staged")

        license_member = artifact["license"]["pathInArtifact"]
        write_bytes(
            stage_dir / "assets" / "runtime-engines" / "licenses" / "python" / "LICENSE.txt",
            resolve_tar_file(bundle, members, license_member),
            executable=False,
            epoch=epoch,
        )

    extension_map = {
        "schemaVersion": 1,
        "pythonVersion": artifact["version"],
        "extensions": extensions,
    }
    write_json(
        python_assets
        / "lib"
        / f"python{stdlib_version}"
        / "lib-dynload"
        / "extensions.json",
        extension_map,
        epoch,
    )
    generate_extension_header(
        work_dir / "generated" / "python_extensions.generated.h",
        extensions,
        epoch,
    )
    write_json(
        python_assets / "runtime-metadata.json",
        {
            "engine": "python",
            "version": artifact["version"],
            "stdlib": f"lib/python{stdlib_version}",
            "extensionCount": len(extensions),
        },
        epoch,
    )
    return extensions


def copy_tree_as_data(source: Path, destination: Path, epoch: int) -> None:
    if not source.is_dir():
        fail(f"Required source directory is missing: {source}")
    for path in sorted(source.rglob("*")):
        if path.is_dir():
            continue
        relative = path.relative_to(source)
        if path.is_symlink():
            target = path.resolve(strict=True)
            try:
                target.relative_to(source.resolve())
            except ValueError:
                fail(f"Refusing external symlink in data tree: {path}")
            data = target.read_bytes()
        else:
            data = path.read_bytes()
        if data.startswith(ELF_MAGIC):
            fail(f"Executable data is forbidden in assets: {path}")
        write_bytes(destination / relative, data, executable=False, epoch=epoch)


def patch_npm_android_bin_links(npm_destination: Path, epoch: int) -> None:
    fix_bin = npm_destination / "node_modules" / "bin-links" / "lib" / "fix-bin.js"
    try:
        source = fix_bin.read_text(encoding="utf-8")
    except OSError as exc:
        fail(f"Unable to read npm bin-links implementation: {exc}")
    original = """const fixBin = (file, mode = execMode) => chmod(file, mode)\n  .then(() => isWindowsHashbangFile(file))\n  .then(isWHB => isWHB ? dos2Unix(file) : null)\n"""
    replacement = r"""const fixAndroidHashbang = file => process.platform !== 'android' ? null
  : readFile(file, 'utf8').then(content => {
    const launcher = process.env.VANTALOOM_NODE_LAUNCHER
    if (!launcher || launcher[0] !== '/') {
      throw new Error('VANTALOOM_NODE_LAUNCHER is required on Android')
    }
    const updated = content.replace(
      /^#!(?:\/usr\/bin\/env\s+)?node(?:\s+[^\n]*)?\r?$/m,
      `#!${launcher} node`
    )
    return updated === content ? null : writeFileAtomic(file, updated)
  })

const fixBin = (file, mode = execMode) => chmod(file, mode)
  .then(() => isWindowsHashbangFile(file))
  .then(isWHB => isWHB ? dos2Unix(file) : null)
  .then(() => fixAndroidHashbang(file))
"""
    if source.count(original) != 1:
        fail("Pinned npm bin-links source no longer matches the Android patch")
    write_bytes(
        fix_bin,
        source.replace(original, replacement).encode(),
        executable=False,
        epoch=epoch,
    )


def stage_node_assets(
    node_root: Path,
    artifact: dict[str, Any],
    stage_dir: Path,
    epoch: int,
) -> None:
    npm_root = node_root / "deps" / "npm"
    package_json_path = npm_root / "package.json"
    try:
        npm_package = json.loads(package_json_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"Unable to read bundled npm metadata: {exc}")
    if npm_package.get("version") != artifact["npmVersion"]:
        fail(
            f"Bundled npm version mismatch: expected {artifact['npmVersion']}, "
            f"got {npm_package.get('version')}"
        )

    assets_root = stage_dir / "assets" / "runtime-engines"
    npm_destination = assets_root / "node" / "lib" / "node_modules" / "npm"
    copy_tree_as_data(
        npm_root,
        npm_destination,
        epoch,
    )
    patch_npm_android_bin_links(npm_destination, epoch)
    write_bytes(
        assets_root / "licenses" / "node" / "LICENSE",
        (node_root / artifact["license"]["pathInArtifact"]).read_bytes(),
        executable=False,
        epoch=epoch,
    )
    write_bytes(
        assets_root / "licenses" / "npm" / "LICENSE",
        (node_root / artifact["npmLicense"]["pathInArtifact"]).read_bytes(),
        executable=False,
        epoch=epoch,
    )
    write_json(
        assets_root / "node" / "runtime-metadata.json",
        {
            "engine": "node",
            "version": artifact["version"],
            "npmVersion": artifact["npmVersion"],
            "npmCli": "lib/node_modules/npm/bin/npm-cli.js",
            "npxCli": "lib/node_modules/npm/bin/npx-cli.js",
        },
        epoch,
    )


def write_third_party_notice(lock: dict[str, Any], stage_dir: Path) -> None:
    epoch = lock["target"]["sourceDateEpoch"]
    python = lock["artifacts"]["python"]
    node = lock["artifacts"]["node"]
    python_dependencies = []
    for component in python["bundledComponents"]:
        license_info = component["license"]
        entry = {
            "id": component["id"],
            "name": component["name"],
            "version": component["version"],
            "spdx": license_info["spdx"],
            "licenseFile": str(
                python_dependency_license_relative_path(component)
            ),
            "licenseSource": license_info["url"],
        }
        if component["id"] == "xz":
            entry["bundledLibrary"] = component["bundledLibrary"]
            entry["linkage"] = component["linkage"]
        python_dependencies.append(entry)
    notice = {
        "schemaVersion": 1,
        "components": [
            {
                "name": python["name"],
                "version": python["version"],
                "spdx": python["license"]["spdx"],
                "source": python["url"],
            },
            *python_dependencies,
            {
                "name": node["name"],
                "version": node["version"],
                "spdx": node["license"]["spdx"],
                "source": node["url"],
            },
            {
                "name": "npm",
                "version": node["npmVersion"],
                "spdx": node["npmLicense"]["spdx"],
                "source": node["url"],
            },
            {
                "name": "Yaegi",
                "version": lock["goRunner"]["yaegi"]["version"],
                "spdx": lock["goRunner"]["yaegi"]["license"]["spdx"],
                "source": lock["goRunner"]["yaegi"]["url"],
            },
            {
                "name": "Go standard library/runtime",
                "version": "build toolchain version",
                "spdx": "BSD-3-Clause",
                "source": "https://go.dev/LICENSE",
            },
        ],
    }
    write_json(
        stage_dir
        / "assets"
        / "runtime-engines"
        / "licenses"
        / "THIRD_PARTY_COMPONENTS.json",
        notice,
        epoch,
    )


def verify_staged_python_dependency_licenses(
    lock: dict[str, Any],
    stage_dir: Path,
) -> None:
    licenses_root = stage_dir / "assets" / "runtime-engines" / "licenses"
    notice_path = licenses_root / "THIRD_PARTY_COMPONENTS.json"
    try:
        notice = json.loads(notice_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"Unable to read third-party component notice: {exc}")
    if notice.get("schemaVersion") != 1:
        fail("Third-party component notice schemaVersion must be 1")
    notice_components = notice.get("components")
    if not isinstance(notice_components, list):
        fail("Third-party component notice is invalid")
    entries: dict[str, dict[str, Any]] = {}
    for entry in notice_components:
        if not isinstance(entry, dict) or "id" not in entry:
            continue
        component_id = entry["id"]
        if not isinstance(component_id, str) or component_id in entries:
            fail("Third-party component notice has duplicate or invalid ids")
        entries[component_id] = entry

    python_artifact = lock["artifacts"]["python"]
    for component in python_artifact["bundledComponents"]:
        component_id = component["id"]
        license_info = component["license"]
        relative = python_dependency_license_relative_path(component)
        packaged = licenses_root / relative
        if not packaged.is_file():
            fail(f"Packaged Python dependency license is missing: {component_id}")
        verify_artifact(packaged, license_info)
        expected_entry = {
            "id": component_id,
            "name": component["name"],
            "version": component["version"],
            "spdx": license_info["spdx"],
            "licenseFile": str(relative),
            "licenseSource": license_info["url"],
        }
        if component_id == "xz":
            expected_entry["bundledLibrary"] = component["bundledLibrary"]
            expected_entry["linkage"] = component["linkage"]
        if entries.get(component_id) != expected_entry:
            fail(f"Third-party component notice mismatch: {component_id}")
    if set(entries) != set(PYTHON_DEPENDENCY_LICENSES):
        fail("Third-party component notice dependency set is invalid")


def prepare(
    lock: dict[str, Any],
    cache_dir: Path,
    work_dir: Path,
    stage_dir: Path,
    python_only: bool,
) -> None:
    ensure_empty_directory(work_dir)
    ensure_empty_directory(stage_dir)
    epoch = lock["target"]["sourceDateEpoch"]
    python_artifact = lock["artifacts"]["python"]
    python_archive = fetch_artifact(cache_dir, python_artifact)
    python_dependency_licenses = fetch_python_dependency_licenses(
        cache_dir,
        python_artifact,
    )
    stage_python(
        python_archive,
        python_artifact,
        stage_dir,
        work_dir,
        epoch,
    )
    stage_python_dependency_licenses(
        python_dependency_licenses,
        python_artifact,
        stage_dir,
        epoch,
    )

    if not python_only:
        node_artifact = lock["artifacts"]["node"]
        node_archive = fetch_artifact(cache_dir, node_artifact)
        extracted = work_dir / "node-extracted"
        node_root = extract_archive(
            node_archive,
            extracted,
            node_artifact["archiveRoot"],
        )
        stage_node_assets(node_root, node_artifact, stage_dir, epoch)
        write_json(
            work_dir / "prepared.json",
            {
                "nodeSource": str(node_root.resolve()),
                "pythonInclude": str(
                    (work_dir / "python-prefix" / "include").resolve()
                ),
                "generatedInclude": str((work_dir / "generated").resolve()),
            },
            epoch,
        )
    write_third_party_notice(lock, stage_dir)
    verify_staged_python_dependency_licenses(lock, stage_dir)


def elf_info(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    if len(data) < 64 or not data.startswith(ELF_MAGIC):
        fail(f"Not an ELF file: {path}")
    if data[4] != 2 or data[5] != 1:
        fail(f"ELF must be 64-bit little-endian: {path}")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)
    elf_type = header[1]
    machine = header[2]
    program_offset = header[5]
    program_entry_size = header[9]
    program_count = header[10]
    interpreter = None
    for index in range(program_count):
        offset = program_offset + index * program_entry_size
        if offset + 56 > len(data):
            fail(f"Truncated ELF program header: {path}")
        program_type, _, file_offset, _, _, file_size, _, _ = struct.unpack_from(
            "<IIQQQQQQ", data, offset
        )
        if program_type == 3:
            end = file_offset + file_size
            if end > len(data):
                fail(f"Truncated ELF interpreter: {path}")
            interpreter = data[file_offset:end].rstrip(b"\0").decode("ascii")
    return {"type": elf_type, "machine": machine, "interpreter": interpreter}


def assert_android_elf(path: Path, executable: bool) -> None:
    info = elf_info(path)
    if info["type"] != ET_DYN:
        fail(f"Android ELF must be ET_DYN: {path}")
    if info["machine"] != EM_AARCH64:
        fail(f"Android ELF must target AArch64: {path}")
    if executable and info["interpreter"] != "/system/bin/linker64":
        fail(
            f"Android executable must use /system/bin/linker64: {path} "
            f"uses {info['interpreter']!r}"
        )


def copy_native_binary(source: Path, destination: Path, epoch: int) -> None:
    if not source.is_file():
        fail(f"Native build output is missing: {source}")
    assert_android_elf(source, executable=True)
    write_bytes(destination, source.read_bytes(), executable=True, epoch=epoch)


def stage_file_records(stage_dir: Path) -> list[dict[str, Any]]:
    records = []
    for path in sorted(stage_dir.rglob("*")):
        if not path.is_file() or path.name in {
            "manifest.json",
            "runtime-engines.manifest.json",
        }:
            continue
        relative = path.relative_to(stage_dir).as_posix()
        records.append(
            {
                "path": relative,
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    return records


def tree_size(path: Path) -> int:
    if not path.exists():
        return 0
    if path.is_file():
        return path.stat().st_size
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def engine_bundle_sizes(stage_dir: Path) -> dict[str, dict[str, int]]:
    native_dir = stage_dir / "jniLibs" / "arm64-v8a"
    assets = stage_dir / "assets" / "runtime-engines"
    python_native = sum(
        path.stat().st_size
        for path in native_dir.glob("*.so")
        if path.name == "libvantaloom_python.so"
        or path.name.startswith("libpython")
        or path.name.endswith("_python.so")
        or path.name.startswith("libvantaloom_pyext_")
    )
    node_native = tree_size(native_dir / "libvantaloom_node.so") + tree_size(
        native_dir / "libc++_shared.so"
    )
    go_native = tree_size(native_dir / "libvantaloom_go.so")
    values = {
        "python": {
            "dataBytes": tree_size(assets / "python")
            + tree_size(assets / "licenses" / "python")
            + tree_size(assets / "licenses" / "python-dependencies"),
            "nativeBytes": python_native,
        },
        "node": {
            "dataBytes": tree_size(assets / "node")
            + tree_size(assets / "licenses" / "node")
            + tree_size(assets / "licenses" / "npm"),
            "nativeBytes": node_native,
        },
        "go": {
            "dataBytes": tree_size(assets / "licenses" / "go")
            + tree_size(assets / "licenses" / "yaegi"),
            "nativeBytes": go_native,
        },
    }
    for value in values.values():
        value["bundleBytes"] = value["dataBytes"] + value["nativeBytes"]
    return values


def build_runtime_manifest(
    lock: dict[str, Any], stage_dir: Path
) -> dict[str, Any]:
    bundle_sizes = engine_bundle_sizes(stage_dir)
    return {
        "schemaVersion": 1,
        "target": lock["target"],
        "assets": {
            "apkPath": "runtime-engines",
            "filesDirPath": (
                "runtime-toolchains/bundles/<content-addressed-bundle>"
            ),
            "stateDirPath": "runtime-toolchains/state",
            "containsExecutableCode": False,
        },
        "directoryEnvironment": {
            "appFiles": "VANTALOOM_APP_FILES_DIR",
            "bundle": "VANTALOOM_RUNTIME_BUNDLE_DIR",
            "state": "VANTALOOM_RUNTIME_DATA_DIR",
        },
        "launcher": {
            "nativeLibrary": "libvantaloom_python.so",
            "requiredEnvironment": [
                "VANTALOOM_APP_FILES_DIR",
                "VANTALOOM_RUNTIME_BUNDLE_DIR",
                "VANTALOOM_RUNTIME_DATA_DIR",
            ],
            "optionalManifestEnvironment": "VANTALOOM_MOBILE_RUNTIME_MANIFEST",
            "protocolVersion": 1,
        },
        "commands": {
            "python": ["python"],
            "python3": ["python3"],
            "pip": ["pip"],
            "pip3": ["pip3"],
            "node": ["node"],
            "npm": ["npm"],
            "npx": ["npx"],
            "go": ["run", "<file.go>"],
        },
        "engines": {
            "python": {
                **bundle_sizes["python"],
                "version": lock["artifacts"]["python"]["version"],
                "nativeLibrary": "libvantaloom_python.so",
                "entryArgs": ["python"],
                "bundleRoot": "python",
                "stateRoots": [
                    "cache/python",
                    "python-lib-dynload",
                    "python-user",
                ],
                "longRunningProcesses": True,
                "loopbackEnforced": True,
                "loopbackPolicy": "CPython native audit hook rejects non-loopback socket.bind events",
                "settingsEnvironment": [
                    "PYTHONUTF8",
                    "PIP_INDEX_URL",
                    "PIP_EXTRA_INDEX_URL",
                ],
            },
            "node": {
                **bundle_sizes["node"],
                "version": lock["artifacts"]["node"]["version"],
                "npmVersion": lock["artifacts"]["node"]["npmVersion"],
                "nativeLibrary": "libvantaloom_python.so",
                "entryArgs": ["node"],
                "runtimeNativeLibrary": "libvantaloom_node.so",
                "bundleRoot": "node",
                "stateRoots": ["cache/npm", "node-prefix"],
                "longRunningProcesses": True,
                "loopbackEnforced": True,
                "loopbackPolicy": "Hash-locked native Node socket-binding patch rejects non-loopback TCP and UDP binds",
                "policyPatches": lock["artifacts"]["node"]["patches"],
                "fixedEntryArguments": [
                    "--permission",
                    "--allow-fs-read=<runtime-bundle-dir>",
                    "--allow-fs-read=<runtime-state-dir>",
                    "--allow-fs-write=<runtime-state-dir>",
                    "--allow-fs-read=<working-directory>",
                    "--allow-fs-write=<working-directory>",
                ],
                "workingDirectoryPolicy": (
                    "must resolve beneath VANTALOOM_APP_FILES_DIR and must not "
                    "overlap VANTALOOM_RUNTIME_BUNDLE_DIR"
                ),
                "settingsEnvironment": [
                    "NPM_CONFIG_REGISTRY",
                    "NPM_CONFIG_IGNORE_SCRIPTS",
                ],
            },
            "go": {
                **bundle_sizes["go"],
                "version": lock["goRunner"]["version"],
                "interpreter": "Yaegi " + lock["goRunner"]["yaegi"]["version"],
                "nativeLibrary": "libvantaloom_go.so",
                "longRunningProcesses": False,
                "loopbackEnforced": False,
                "serverAllowed": False,
                "policy": "unsafe, syscall, os/exec and listener/server constructors are not registered; Yaegi is not treated as a security boundary",
            },
        },
        "files": stage_file_records(stage_dir),
    }


def stage_android_ndk_notice(
    ndk_notice: Path,
    stage_dir: Path,
    epoch: int,
) -> None:
    if not ndk_notice.is_file():
        fail(f"Android NDK NOTICE is missing: {ndk_notice}")
    write_bytes(
        stage_dir
        / "assets"
        / "runtime-engines"
        / "licenses"
        / "android-ndk"
        / "NOTICE",
        ndk_notice.read_bytes(),
        executable=False,
        epoch=epoch,
    )


def finalize(
    lock: dict[str, Any],
    stage_dir: Path,
    node_binary: Path,
    launcher_binary: Path,
    go_binary: Path,
    yaegi_license: Path,
    go_license: Path,
    libcxx: Path | None,
    ndk_notice: Path,
) -> None:
    epoch = lock["target"]["sourceDateEpoch"]
    native_dir = stage_dir / "jniLibs" / lock["target"]["abi"]
    native_dir.mkdir(parents=True, exist_ok=True)
    copy_native_binary(node_binary, native_dir / "libvantaloom_node.so", epoch)
    copy_native_binary(
        launcher_binary,
        native_dir / "libvantaloom_python.so",
        epoch,
    )
    copy_native_binary(go_binary, native_dir / "libvantaloom_go.so", epoch)
    for license_source, license_destination in (
        (
            yaegi_license,
            stage_dir
            / "assets"
            / "runtime-engines"
            / "licenses"
            / "yaegi"
            / "LICENSE",
        ),
        (
            go_license,
            stage_dir
            / "assets"
            / "runtime-engines"
            / "licenses"
            / "go"
            / "LICENSE",
        ),
    ):
        if not license_source.is_file():
            fail(f"Runtime license is missing: {license_source}")
        write_bytes(
            license_destination,
            license_source.read_bytes(),
            executable=False,
            epoch=epoch,
        )
    stage_android_ndk_notice(ndk_notice, stage_dir, epoch)
    if libcxx is not None:
        if not libcxx.is_file():
            fail(f"libc++_shared.so is missing: {libcxx}")
        assert_android_elf(libcxx, executable=False)
        write_bytes(
            native_dir / "libc++_shared.so",
            libcxx.read_bytes(),
            executable=True,
            epoch=epoch,
        )

    verify_staged_python_dependency_licenses(lock, stage_dir)
    manifest = build_runtime_manifest(lock, stage_dir)
    manifest_path = stage_dir / "assets" / "runtime-engines" / "manifest.json"
    write_json(manifest_path, manifest, epoch)
    write_json(stage_dir / "runtime-engines.manifest.json", manifest, epoch)
    verify_stage(stage_dir, require_final=True, lock=lock)


def verify_stage(
    stage_dir: Path,
    require_final: bool,
    lock: dict[str, Any] | None = None,
) -> None:
    native_dir = stage_dir / "jniLibs" / "arm64-v8a"
    assets_dir = stage_dir / "assets"
    if not native_dir.is_dir() or not assets_dir.is_dir():
        fail("Stage must contain jniLibs/arm64-v8a and assets")
    if lock is not None:
        verify_staged_python_dependency_licenses(lock, stage_dir)

    native_files = sorted(path for path in native_dir.iterdir() if path.is_file())
    if not native_files:
        fail("No JNI libraries were staged")
    for path in native_files:
        if not JNI_NAME_RE.fullmatch(path.name):
            fail(f"Invalid jniLibs filename: {path.name}")
        assert_android_elf(
            path,
            executable=path.name
            in {
                "libvantaloom_go.so",
                "libvantaloom_node.so",
                "libvantaloom_python.so",
            },
        )

    for path in assets_dir.rglob("*"):
        if path.is_symlink():
            fail(f"Symlinks are forbidden in generated assets: {path}")
        if path.is_file():
            with path.open("rb") as stream:
                if stream.read(4) == ELF_MAGIC:
                    fail(f"ELF code is forbidden in generated assets: {path}")

    python_extension_map = (
        assets_dir
        / "runtime-engines"
        / "python"
        / "lib"
        / "python3.14"
        / "lib-dynload"
        / "extensions.json"
    )
    extension_data = json.loads(python_extension_map.read_text(encoding="utf-8"))
    for extension in extension_data["extensions"]:
        alias = native_dir / extension["nativeLibrary"]
        if not alias.is_file():
            fail(f"Python extension alias is missing: {alias}")

    if not require_final:
        return
    required = {
        "libvantaloom_node.so",
        "libvantaloom_python.so",
        "libvantaloom_go.so",
        "libpython3.14.so",
    }
    missing = sorted(required - {path.name for path in native_files})
    if missing:
        fail(f"Final stage is missing native files: {', '.join(missing)}")

    manifest_path = assets_dir / "runtime-engines" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        fail("Runtime manifest schemaVersion must be 1")
    for record in manifest.get("files", []):
        path = stage_dir / PurePosixPath(record["path"])
        if not path.is_file():
            fail(f"Manifest file is missing: {record['path']}")
        if path.stat().st_size != record["size"]:
            fail(f"Manifest size mismatch: {record['path']}")
        if sha256_file(path) != record["sha256"]:
            fail(f"Manifest SHA256 mismatch: {record['path']}")


def command_fetch(args: argparse.Namespace) -> None:
    lock = load_lock(args.lock)
    for artifact in lock["artifacts"].values():
        path = fetch_artifact(args.cache, artifact)
        print(f"verified {path}")
    for path in fetch_python_dependency_licenses(
        args.cache,
        lock["artifacts"]["python"],
    ).values():
        print(f"verified {path}")


def command_prepare(args: argparse.Namespace) -> None:
    lock = load_lock(args.lock)
    prepare(lock, args.cache, args.work, args.stage, args.python_only)
    print(f"prepared stage at {args.stage}")


def command_finalize(args: argparse.Namespace) -> None:
    lock = load_lock(args.lock)
    finalize(
        lock,
        args.stage,
        args.node_binary,
        args.launcher_binary,
        args.go_binary,
        args.yaegi_license,
        args.go_license,
        args.libcxx,
        args.ndk_notice,
    )
    print(f"finalized stage at {args.stage}")


def command_verify(args: argparse.Namespace) -> None:
    lock = load_lock(args.lock)
    verify_stage(
        args.stage,
        require_final=not args.prepared_only,
        lock=lock,
    )
    print(f"verified stage at {args.stage}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    subparsers = parser.add_subparsers(dest="command", required=True)

    fetch = subparsers.add_parser("fetch", help="download and verify locked sources")
    fetch.add_argument("--cache", type=Path, required=True)
    fetch.set_defaults(func=command_fetch)

    prepare_parser = subparsers.add_parser(
        "prepare", help="stage data and extract build inputs"
    )
    prepare_parser.add_argument("--cache", type=Path, required=True)
    prepare_parser.add_argument("--work", type=Path, required=True)
    prepare_parser.add_argument("--stage", type=Path, required=True)
    prepare_parser.add_argument("--python-only", action="store_true")
    prepare_parser.set_defaults(func=command_prepare)

    finalize_parser = subparsers.add_parser(
        "finalize", help="add built ELF files and write the runtime manifest"
    )
    finalize_parser.add_argument("--stage", type=Path, required=True)
    finalize_parser.add_argument("--node-binary", type=Path, required=True)
    finalize_parser.add_argument("--launcher-binary", type=Path, required=True)
    finalize_parser.add_argument("--go-binary", type=Path, required=True)
    finalize_parser.add_argument("--yaegi-license", type=Path, required=True)
    finalize_parser.add_argument("--go-license", type=Path, required=True)
    finalize_parser.add_argument("--libcxx", type=Path)
    finalize_parser.add_argument("--ndk-notice", type=Path, required=True)
    finalize_parser.set_defaults(func=command_finalize)

    verify_parser = subparsers.add_parser("verify", help="verify a generated stage")
    verify_parser.add_argument("--stage", type=Path, required=True)
    verify_parser.add_argument("--prepared-only", action="store_true")
    verify_parser.set_defaults(func=command_verify)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.func(args)
    except PackagingError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
