#!/usr/bin/env python3
"""Upload Linkwright's jars to CurseForge with every game version, loader, Java
version and environment tag each one supports, plus the changelog.

Prerequisites:
  1. pip install requests
  2. An API token from https://authors.curseforge.com/account/api-tokens
     in the CURSEFORGE_TOKEN environment variable (or --token)
  3. The jars built and collected: ./gradlew release  (puts them in build/libs)

Usage, from the project root:
  python scripts/upload_curseforge.py --dry-run      # resolve and preview only
  python scripts/upload_curseforge.py
  python scripts/upload_curseforge.py --only forge-1.13,fabric
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

import requests

PROJECT_ROOT = Path(__file__).resolve().parent.parent
UPLOAD_API = "https://minecraft.curseforge.com/api"

# Baked in rather than read from CURSEFORGE_PROJECT_ID, because that variable is
# shared with the other mod projects and the wrong value uploads to the wrong page.
DEFAULT_PROJECT_ID = "1808374"

# Minecraft versions Forge actually released builds for, per legacy jar.
FORGE_1_13 = ["1.13.2", "1.14.2", "1.14.3", "1.14.4", "1.15", "1.15.1", "1.15.2",
              "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5"]
FORGE_1_7 = ["1.7.10", "1.8", "1.8.8", "1.8.9", "1.9", "1.9.4", "1.10", "1.10.2",
             "1.11", "1.11.2", "1.12", "1.12.1", "1.12.2"]
MODERN_JAVA = ["Java 8", "Java 16", "Java 17", "Java 21", "Java 25"]

# "mc": a list of versions, or "1.14+" for every release from that version on.
JARS = [
    {"key": "universal", "file": "linkwright-{v}.jar", "label": "Forge 1.17+ and NeoForge",
     "loaders": ["Forge", "NeoForge"], "mc": "1.17.1+", "java": ["Java 16", "Java 17", "Java 21", "Java 25"]},
    {"key": "forge-1.13", "file": "linkwright-1.13-1.16.5-{v}.jar", "label": "Forge 1.13.2 to 1.16.5",
     "loaders": ["Forge"], "mc": FORGE_1_13, "java": ["Java 8"]},
    {"key": "forge-1.7", "file": "linkwright-1.7.10-1.12.2-{v}.jar", "label": "Forge 1.7.10 to 1.12.2",
     "loaders": ["Forge"], "mc": FORGE_1_7, "java": ["Java 8"]},
    {"key": "fabric", "file": "linkwright-fabric-{v}.jar", "label": "Fabric",
     "loaders": ["Fabric"], "mc": "1.14+", "java": MODERN_JAVA},
]
DEFAULT_KEYS = "forge-1.13,forge-1.7,fabric"

ENV_TAGS = [("environment", "Client"), ("environment", "Server")]


def read_mod_version() -> str:
    for line in (PROJECT_ROOT / "gradle.properties").read_text().splitlines():
        if line.strip().startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    sys.exit("mod_version not found in gradle.properties")


def fetch_version_ids(token: str) -> dict:
    """Map (category, display name) to numeric game version IDs.

    The same display name can exist under several version types, and the upload
    API rejects IDs from the wrong type, so the type is part of the key.
    """
    headers = {"X-Api-Token": token}
    types_resp = requests.get(f"{UPLOAD_API}/game/version-types", headers=headers, timeout=30)
    types_resp.raise_for_status()
    type_category = {}
    for vt in types_resp.json():
        name = vt["name"]
        # Usually "Minecraft 1.19", but newer types are bare numbers like "26.2"
        if name.startswith("Minecraft") or re.fullmatch(r"\d+(\.\d+)*", name):
            type_category[vt["id"]] = "minecraft"
        elif name == "Java":
            type_category[vt["id"]] = "java"
        elif name in ("Modloader", "ModLoader"):
            type_category[vt["id"]] = "modloader"
        elif name == "Environment":
            type_category[vt["id"]] = "environment"

    resp = requests.get(f"{UPLOAD_API}/game/versions", headers=headers, timeout=30)
    resp.raise_for_status()
    ids = {}
    for entry in resp.json():
        category = type_category.get(entry["gameVersionTypeID"])
        if category is not None:
            ids[(category, entry["name"])] = entry["id"]
    return ids


def version_tuple(name: str) -> tuple:
    return tuple(int(p) for p in name.split("."))


def minecraft_versions(spec, version_ids: dict) -> list:
    """Expand "1.14+" into every release (no snapshots) CurseForge knows from 1.14 on."""
    if isinstance(spec, list):
        return spec
    floor = version_tuple(spec.rstrip("+"))
    releases = [n for c, n in version_ids if c == "minecraft" and re.fullmatch(r"\d+(\.\d+)+", n)]
    return sorted((n for n in releases if version_tuple(n) >= floor), key=version_tuple)


def resolve_tags(tags: list, version_ids: dict) -> list:
    resolved = []
    for category, name in tags:
        if (category, name) not in version_ids:
            close = sorted(n for c, n in version_ids if c == category and name.split()[0] in n)[:10]
            sys.exit(f"CurseForge has no {category} version named {name!r}.\n"
                     f"Closest available names in that category: {close}")
        resolved.append(version_ids[(category, name)])
    return resolved


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project-id", default=DEFAULT_PROJECT_ID)
    parser.add_argument("--token", default=os.environ.get("CURSEFORGE_TOKEN"))
    parser.add_argument("--mod-version", default=None,
                        help="defaults to mod_version from gradle.properties")
    parser.add_argument("--changelog-file", default=str(PROJECT_ROOT / "scripts" / "changelog.html"))
    parser.add_argument("--release-type", default="release", choices=["release", "beta", "alpha"])
    parser.add_argument("--only", default=DEFAULT_KEYS,
                        help="comma-separated jars to upload: "
                             + ", ".join(j["key"] for j in JARS) + f" (default {DEFAULT_KEYS})")
    parser.add_argument("--dry-run", action="store_true",
                        help="resolve everything and show what would be uploaded")
    args = parser.parse_args()

    if not args.token:
        sys.exit("No API token: set CURSEFORGE_TOKEN or pass --token")

    mod_version = args.mod_version or read_mod_version()
    changelog = Path(args.changelog_file).read_text(encoding="utf-8")
    only = set(args.only.split(","))
    unknown = only - {j["key"] for j in JARS}
    if unknown:
        sys.exit(f"Unknown jar key(s): {', '.join(sorted(unknown))}")

    jars = [j for j in JARS if j["key"] in only]
    for jar in jars:
        jar["path"] = PROJECT_ROOT / "build" / "libs" / jar["file"].format(v=mod_version)
        if not jar["path"].is_file():
            sys.exit(f"Missing jar: {jar['path']}\nRun ./gradlew release first.")

    print("Resolving CurseForge game version IDs...")
    version_ids = fetch_version_ids(args.token)

    uploaded = []
    for jar in jars:
        tags = ([("minecraft", v) for v in minecraft_versions(jar["mc"], version_ids)]
                + [("modloader", l) for l in jar["loaders"]]
                + [("java", j) for j in jar["java"]]
                + ENV_TAGS)
        game_versions = resolve_tags(tags, version_ids)
        metadata = {
            "changelog": changelog,
            "changelogType": "html",
            "displayName": f"Linkwright {mod_version} ({jar['label']})",
            "gameVersions": game_versions,
            "releaseType": args.release_type,
        }
        print(f"\n{jar['path'].name}  ->  {metadata['displayName']}")
        for category in ("minecraft", "modloader", "java", "environment"):
            names = [n for c, n in tags if c == category]
            print(f"  {category:<11} {', '.join(names)}")
        if args.dry_run:
            continue

        with open(jar["path"], "rb") as fh:
            resp = requests.post(
                f"{UPLOAD_API}/projects/{args.project_id}/upload-file",
                headers={"X-Api-Token": args.token},
                data={"metadata": json.dumps(metadata)},
                files={"file": (jar["path"].name, fh, "application/java-archive")},
                timeout=300,
            )
        if resp.status_code != 200:
            sys.exit(f"  UPLOAD FAILED ({resp.status_code}): {resp.text}")
        file_id = resp.json()["id"]
        uploaded.append((jar["key"], file_id))
        print(f"  uploaded, file id {file_id}")

    if args.dry_run:
        print("\nDry run complete, nothing uploaded.")
    else:
        print(f"\nDone: {len(uploaded)} file(s) uploaded. "
              f"They appear once CurseForge approval finishes.")


if __name__ == "__main__":
    main()
