#!/usr/bin/env python3
"""
prepare_runtime.py - Assembles a fully patched, verified runtime in destination path.
"""
import sys
import os
import hashlib
import shutil
import tempfile

RUNNER_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(RUNNER_DIR)
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "unpack"))
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "bundle"))
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "manifest"))
sys.path.insert(0, os.path.join(MODULES_DIR, "patches"))

import extractor
import assemble
import manifest_verifier
import pipeline

# Expected SHA-256 of the patched core binaries.
# Core 2.34.0 (updated 2026-11-23): patched with the modules/patches pipeline
# with the failfast group skipped (Go 1.28 restructured its signatures).
TARGET_SHA_FULL = "5c0a76ea4ae92b0f4de38148f9792b12d39f69764852a653b6f5860b99314438"
TARGET_SHA_NO_BYPASS = "0fae7fa32d313c0f8c5a760a0303681f3f19ec226f3e48f5eae84a6a599bbeff"
DEFAULT_DEST = "/data/user/0/io.github.ziyekaixuan.antigravity/files/standalone_runtime"


def sha256_file(path: str) -> str:
    hasher = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def prepare(dest_runtime_dir: str = DEFAULT_DEST, no_bypass: bool = False) -> bool:
    no_bypass = no_bypass or (os.environ.get("NO_BYPASS") == "1") or (os.environ.get("SKIP_GATES") == "1")

    print("=" * 60)
    print(f"  PREPARING NEW MODULAR RUNTIME {'[NO BYPASS]' if no_bypass else '[WITH BYPASS]'}")
    print("=" * 60)

    dest_runtime_dir = os.path.abspath(dest_runtime_dir)
    core_dir = os.path.join(MODULES_DIR, "core")
    archive_path = os.path.join(core_dir, "pristine", "language_server.tar.gz")

    tmp_dir = tempfile.mkdtemp(prefix="agy_staging_")
    app_base = "/data/user/0/io.github.ziyekaixuan.antigravity.dev" if os.path.exists("/data/user/0/io.github.ziyekaixuan.antigravity.dev") and os.access("/data/user/0/io.github.ziyekaixuan.antigravity.dev", os.W_OK) else "/data/user/0/io.github.ziyekaixuan.antigravity"
    cache_dir = os.environ.get("AGY_CACHE_DIR", os.path.join(app_base, "cache") if os.access(app_base, os.W_OK) else os.path.join(tempfile.gettempdir(), "agy_cache"))
    os.makedirs(cache_dir, exist_ok=True)
    target_sha = TARGET_SHA_NO_BYPASS if no_bypass else TARGET_SHA_FULL
    cache_tag = f"language_server_patched_{'nobypass_' if no_bypass else ''}{target_sha[:8]}"
    cached_patched = os.path.join(cache_dir, cache_tag)

    try:
        staged_bin = os.path.join(tmp_dir, "language_server")
        cache_valid = False
        if os.path.isfile(cached_patched):
            cached_sha = sha256_file(cached_patched)
            if cached_sha == target_sha:
                cache_valid = True
                print(f"\n[1-2/4] Reusing cached patched core (SHA-256 verified: {target_sha[:8]}...)...")
                shutil.copyfile(cached_patched, staged_bin)
            else:
                print(f"\n[!] Cached core SHA mismatch ({cached_sha[:8]} != {target_sha[:8]}); re-patching...")

        if not cache_valid:
            # 1. Extract pristine core to temporary staging area
            print("\n[1/4] Extracting pristine core archive...")
            extractor.extract_core(archive_path, staged_bin)
            print(f"  [OK] Extracted: {os.path.getsize(staged_bin):,} bytes")

            # 2. Apply patch modules
            print(f"\n[2/4] Applying modules/patches pipeline (skip_gates={no_bypass})...")
            ok_patch = pipeline.run_pipeline(staged_bin, dry_run=False, verify=False, allow_missing=True, skip_gates=no_bypass)
            assert ok_patch, "Patch pipeline failed!"
            # Fail closed: with allow_missing=True a patch group can silently
            # no-op (e.g. upstream moved a signature). The patched binary MUST
            # match TARGET_SHA or the build must stop here — never ship an
            # unpatched (possibly unbypassed) core.
            patched_sha = sha256_file(staged_bin)
            assert patched_sha == target_sha, (
                f"Patched core SHA mismatch: got {patched_sha}, expected {target_sha}. "
                "A patch group did not apply (upstream core changed?) — refusing to continue."
            )
            print(f"  [OK] Patches applied and SHA-256 verified ({target_sha[:8]}...).")
            try:
                shutil.copyfile(staged_bin, cached_patched)
            except Exception:
                pass

        # 3. Assemble full runtime assets into destination
        print(f"\n[3/4] Assembling runtime assets -> {dest_runtime_dir}...")
        if os.path.exists(dest_runtime_dir):
            shutil.rmtree(dest_runtime_dir)
        
        manifest = assemble.assemble_runtime(staged_bin, core_dir, dest_runtime_dir)
        print(f"  [OK] Runtime assembled with {len(manifest['files'])} files.")

        # 4. Verify cryptographic manifest
        print("\n[4/4] Verifying destination integrity...")
        manifest_path = os.path.join(dest_runtime_dir, "manifest.json")
        ok_verify = manifest_verifier.verify_manifest(manifest_path, dest_runtime_dir)
        assert ok_verify, "Manifest verification failed on assembled runtime!"
        print("  [OK] Cryptographic manifest verified 100% byte-for-byte.")

        print("\n" + "=" * 60)
        print(f"  RUNTIME PREPARED AND READY FOR EXECUTION! {'[NO BYPASS]' if no_bypass else ''}")
        print("=" * 60 + "\n")
        return True

    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

def main():
    dest = DEFAULT_DEST
    no_bypass = (os.environ.get("NO_BYPASS") == "1") or (os.environ.get("SKIP_GATES") == "1")
    for arg in sys.argv[1:]:
        if arg in ("--no-bypass", "--nobypass", "--skip-gates", "--no-gates"):
            no_bypass = True
        elif not arg.startswith("-"):
            dest = arg
    ok = prepare(dest, no_bypass=no_bypass)
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
