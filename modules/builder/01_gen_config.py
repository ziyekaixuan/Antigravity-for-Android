#!/usr/bin/env python3
"""
01_gen_config.py - Generates BuildConfig.java, gen.xml, and dev-aware AndroidManifest.xml.
"""
import sys
import os
import html
import shutil
import re

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))


def parse_env(path):
    data = {}
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                data[key.strip()] = value.strip().strip('"').strip("'")
    return data


def java_string(value):
    escaped = str(value).replace("\\", "\\\\").replace('"', '\\"')
    return '"' + escaped + '"'


REQUIRED_KEYS = (
    "PKG", "PKG_DEV", "PORT", "PORT_DEV", "GATEWAY_PORT", "GATEWAY_PORT_DEV",
    "CORE_VERSION", "APP_VERSION_NAME", "APP_VERSION_CODE", "OAUTH_SCHEME",
    "APP_NAME", "APP_NAME_DEV", "MIN_API", "TARGET_SDK",
)


def require(env, key):
    """config/app.env is the single source of truth: a missing key must fail
    the build loudly instead of silently falling back to a stale default."""
    value = env.get(key)
    if value is None or value == "":
        raise SystemExit(f"[ERROR] config/app.env is missing required key: {key}")
    return value


def sync_agents_docs(env):
    """Copy config/AGENTS.md (single source) to the two build-time locations
    that used to hold stale copies, after verifying the package name and
    ports mentioned in it match config/app.env."""
    src = os.path.join(PROJECT_ROOT, "config", "AGENTS.md")
    with open(src, "r", encoding="utf-8") as f:
        content = f.read()
    for key in ("PKG", "PORT", "PORT_DEV"):
        if env[key] not in content:
            raise SystemExit(
                f"[ERROR] config/AGENTS.md does not mention {key}={env[key]} "
                f"from config/app.env; update the doc or the env file.")
    targets = (
        os.path.join(PROJECT_ROOT, "modules", "core", "seed", "AGENTS.md"),
        os.path.join(PROJECT_ROOT, "env", "overlay", "etc", "antigravity", "AGENTS.md"),
    )
    for target in targets:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"[OK] Synced AGENTS.md -> {target}")


def main():
    build_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PROJECT_ROOT, "build")
    env_file = os.path.join(PROJECT_ROOT, "config", "app.env")
    env = parse_env(env_file)

    for key in REQUIRED_KEYS:
        require(env, key)

    is_dev = os.environ.get("DEV_MODE") == "1"

    pkg_base = env["PKG"]
    app_id = env["PKG_DEV"] if is_dev else pkg_base
    port = env["PORT_DEV"] if is_dev else env["PORT"]
    port_dev = env["PORT_DEV"]
    gw_port = env["GATEWAY_PORT_DEV"] if is_dev else env["GATEWAY_PORT"]
    core_version = env["CORE_VERSION"]
    app_version_name = env["APP_VERSION_NAME"]
    app_version_code = env["APP_VERSION_CODE"]
    oauth_scheme = env["OAUTH_SCHEME"]
    app_name = env["APP_NAME_DEV"] if is_dev else env["APP_NAME"]
    min_api = env["MIN_API"]
    target_sdk = env["TARGET_SDK"]
    is_debug = "true" if is_dev else "false"

    sync_agents_docs(env)

    # BuildConfig always lives in the com.agy code package, even though the
    # install applicationId (PKG) is com.qin.agy: all Java sources import
    # com.agy.BuildConfig, so the generated class must not follow PKG.
    pkg_dir = os.path.join(build_dir, "gen", "com", "agy")
    res_values_dir = os.path.join(build_dir, "res", "values")
    os.makedirs(pkg_dir, exist_ok=True)
    os.makedirs(res_values_dir, exist_ok=True)

    # 1. BuildConfig.java (always in com.agy package for source compatibility)
    build_config_path = os.path.join(pkg_dir, "BuildConfig.java")
    with open(build_config_path, "w", encoding="utf-8") as f:
        f.write("package com.agy;\n\n")
        f.write("public final class BuildConfig {\n")
        f.write("    private BuildConfig() {}\n\n")
        f.write("    public static final String APPLICATION_ID = %s;\n" % java_string(app_id))
        f.write("    public static final int PORT = %s;\n" % port)
        f.write("    public static final int PORT_DEV = %s;\n" % port_dev)
        f.write("    public static final int GATEWAY_PORT = %s;\n" % gw_port)
        f.write("    public static final String CORE_VERSION = %s;\n" % java_string(core_version))
        f.write("    public static final String APP_VERSION_NAME = %s;\n" % java_string(app_version_name))
        f.write("    public static final int APP_VERSION_CODE = %s;\n" % app_version_code)
        f.write("    public static final String OAUTH_SCHEME = %s;\n" % java_string(oauth_scheme))
        f.write("    public static final String APP_NAME = %s;\n" % java_string(app_name))
        f.write("    public static final int MIN_API = %s;\n" % min_api)
        f.write("    public static final int TARGET_SDK = %s;\n" % target_sdk)
        f.write("    public static final boolean DEBUG = %s;\n" % is_debug)
        f.write("}\n")

    # 2. gen.xml
    gen_xml_path = os.path.join(res_values_dir, "gen.xml")
    with open(gen_xml_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write("<resources>\n")
        f.write('    <string name="app_name">%s</string>\n' % html.escape(app_name, quote=True))
        f.write('    <integer name="https_server_port">%s</integer>\n' % port)
        f.write('    <integer name="gateway_port">%s</integer>\n' % gw_port)
        f.write('    <string name="oauth_scheme">%s</string>\n' % html.escape(oauth_scheme, quote=True))
        f.write("</resources>\n")

    # 3. AndroidManifest.xml (dev-aware)
    src_manifest = os.path.join(PROJECT_ROOT, "app", "src", "main", "AndroidManifest.xml")
    target_manifest = os.path.join(build_dir, "AndroidManifest.xml")
    with open(src_manifest, "r", encoding="utf-8") as f:
        content = f.read()

    content = content.replace('${applicationId}', app_id)
    content = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{app_version_name}"', content)
    content = re.sub(r'android:versionCode="[^"]+"', f'android:versionCode="{app_version_code}"', content)

    if is_dev:
        # NOTE: package and permission were already set to the dev applicationId
        # by the ${applicationId} substitution above; no further replaces needed.
        content = content.replace('<application', '<application android:debuggable="true"', 1)
        print(f"[*] Configured DEV manifest: package={app_id}, debuggable=true")

    with open(target_manifest, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"[OK] Generated BuildConfig.java -> {build_config_path}")
    print(f"[OK] Generated gen.xml -> {gen_xml_path}")
    print(f"[OK] Generated manifest -> {target_manifest}")


if __name__ == "__main__":
    main()
