#!/usr/bin/env python3
"""Stage checksum-pinned official CPython Android packages for the ANB APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath


PACKAGES = {
    "arm64-v8a": {
        "triplet": "aarch64-linux-android",
        "sha256": "423c80b957330cf0ebb92b9b20f2294c292144a441bdb0ceb8c69d14928e569b",
    },
    "x86_64": {
        "triplet": "x86_64-linux-android",
        "sha256": "6ebda1fc3f28efc5861cad397254e8ce5d81ba4f83a3cb6a0c124c3826a0e14c",
    },
}
VERSION = "3.14.10"
PYTHON_MINOR = ".".join(VERSION.split(".")[:2])
BASE_URL = f"https://www.python.org/ftp/python/{VERSION}"
REQUIRED_MODULES = ("os.py", "socket.py", "ssl.py", "sqlite3/__init__.py")
REQUIRED_EXTENSIONS = ("_socket", "_ssl", "_sqlite3")
DEVELOPMENT_EXTENSION_PREFIXES = ("_test", "_ctypes_test", "_xxtest", "xx")


def is_development_extension(module_name: str) -> bool:
    """Return whether an extension is a CPython test fixture, not runtime code."""
    return module_name.startswith(DEVELOPMENT_EXTENSION_PREFIXES)


def ignore_development_extensions(directory: str, names: list[str]) -> set[str]:
    if Path(directory).name != "lib-dynload":
        return set()
    return {
        name
        for name in names
        if name.endswith(".so") and is_development_extension(name.split(".", 1)[0])
    }


def download_package(abi: str, destination: Path) -> None:
    package = PACKAGES[abi]
    filename = f"python-{VERSION}-{package['triplet']}.tar.gz"
    url = f"{BASE_URL}/{filename}"
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": "ANB-Hosting-Android-Build"})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)

    actual = digest.hexdigest()
    expected = package["sha256"]
    if actual != expected:
        destination.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 mismatch for {filename}: expected {expected}, got {actual}")


def extract_package(package_path: Path, destination: Path) -> Path:
    prefix = destination / "prefix"
    prefix.mkdir(parents=True, exist_ok=True)
    with tarfile.open(package_path, "r:gz") as archive:
        for member in archive.getmembers():
            parts = PurePosixPath(member.name).parts
            parts = tuple(part for part in parts if part not in ("", "."))
            if not parts or parts[0] != "prefix":
                continue
            if not member.isdir() and not member.isfile():
                continue
            target = destination.joinpath(*parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError(f"Unsafe path in CPython package: {member.name}")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            source = archive.extractfile(member)
            if source is None:
                raise RuntimeError(f"Could not read CPython package member: {member.name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
    return prefix


def stage_abi(abi: str, prefix: Path, output: Path) -> None:
    python_lib = prefix / "lib" / f"python{PYTHON_MINOR}"
    if not python_lib.is_dir():
        raise RuntimeError(f"Python standard library is missing: {python_lib}")
    for module in REQUIRED_MODULES:
        if not (python_lib / module).is_file():
            raise RuntimeError(f"Python standard library module is missing: {module}")
    extension_names = [path.name for path in (python_lib / "lib-dynload").glob("*.so")]
    for extension in REQUIRED_EXTENSIONS:
        if not any(name.startswith(f"{extension}.") for name in extension_names):
            raise RuntimeError(f"Python Android extension is missing: {extension}")

    c_prefix = output / "prefix" / abi
    include_source = prefix / "include" / f"python{PYTHON_MINOR}"
    if not (include_source / "Python.h").is_file():
        raise RuntimeError(f"Python C API headers are missing: {include_source}")
    shutil.copytree(include_source, c_prefix / "include" / f"python{VERSION[:4]}")

    runtime_libs = [prefix / "lib" / f"libpython{VERSION[:4]}.so"]
    runtime_libs.extend(sorted((prefix / "lib").glob("lib*_python.so")))
    if not runtime_libs[0].is_file():
        raise RuntimeError(f"Python shared library is missing: {runtime_libs[0]}")
    jni_lib = output / "jniLibs" / abi
    jni_lib.mkdir(parents=True, exist_ok=True)
    for library in runtime_libs:
        if not library.is_file():
            raise RuntimeError(f"Python runtime library is missing: {library}")
        shutil.copy2(library, jni_lib / library.name)

    asset_prefix = output / "assets" / "python-runtime" / abi / "prefix"
    asset_lib = asset_prefix / "lib"
    asset_lib.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        python_lib,
        asset_lib / python_lib.name,
        ignore=ignore_development_extensions,
    )
    for directory in ("engines-3", "ossl-modules"):
        source = prefix / "lib" / directory
        if source.is_dir():
            shutil.copytree(source, asset_lib / directory)
    (asset_prefix / "VERSION").write_text(VERSION + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    manifest = output / "manifest.json"
    expected_manifest = {
        "layout": 4,
        "version": VERSION,
        "excluded_extension_prefixes": list(DEVELOPMENT_EXTENSION_PREFIXES),
        "packages": {abi: data["sha256"] for abi, data in PACKAGES.items()},
    }
    if manifest.is_file() and json.loads(manifest.read_text(encoding="utf-8")) == expected_manifest:
        print(f"Using checksum-verified CPython {VERSION} Android runtime already staged at {output}")
        return

    staging = output.with_name(f"{output.name}.staging")
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    try:
        with tempfile.TemporaryDirectory(prefix="anb-cpython-") as temporary:
            temporary_dir = Path(temporary)
            for abi in PACKAGES:
                package_path = temporary_dir / f"{abi}.tar.gz"
                download_package(abi, package_path)
                prefix = extract_package(package_path, temporary_dir / abi)
                stage_abi(abi, prefix, staging)
        (staging / "manifest.json").write_text(
            json.dumps(expected_manifest, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        shutil.rmtree(output, ignore_errors=True)
        staging.rename(output)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    print(f"Staged checksum-verified CPython {VERSION} Android runtime at {output}")


if __name__ == "__main__":
    main()
