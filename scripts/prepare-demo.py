#!/usr/bin/env python3
"""Prepare or upgrade a private Paper demo folder while its server is stopped."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import shutil
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME_VERSION = "1.21.11"
DEMO_HOME = Path(os.environ.get(
    "GENECRAFT_DEMO_HOME",
    os.environ.get("FABLEORBIT_DEMO_HOME", str(Path.home() / "Minecraft" / "GeneCraft Demo")),
)).expanduser()
SERVER_DIR = DEMO_HOME / "server"


def project_version(root: Path = ROOT) -> str:
    version = ET.parse(root / "pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version", "")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version):
        raise RuntimeError("pom.xml must contain an explicit, valid GeneCraft project version.")
    return version


VERSION = project_version()
PLUGIN_JAR = ROOT / "target" / f"genecraft-paper-{VERSION}.jar"
USER_AGENT = f"GeneCraft setup/{VERSION} (https://github.com/shrut10/genecraft)"


def plugin_metadata(jar: Path) -> dict[str, str]:
    """Read only the simple identifying fields, without adding a YAML dependency."""
    try:
        with zipfile.ZipFile(jar) as archive:
            text = archive.read("plugin.yml").decode("utf-8")
    except (OSError, KeyError, ValueError, zipfile.BadZipFile):
        return {}
    return {key: value.strip().strip("\"'") for key, value in
            re.findall(r"^(name|version):\s*([^\r\n]+)", text, re.MULTILINE)}


def install_plugin(jar: Path, server_dir: Path, version: str = VERSION) -> Path:
    if plugin_metadata(jar) != {"name": "GeneCraft", "version": version}:
        raise RuntimeError("Plugin JAR metadata does not match the current GeneCraft version. Rebuild the plugin first.")
    plugins = server_dir / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    destination = plugins / jar.name
    # Never replace an unrelated plugin just because someone renamed its file.
    if destination.exists() and plugin_metadata(destination).get("name") != "GeneCraft":
        raise RuntimeError(f"Refusing to replace an unrecognised plugin: {destination}")
    previous = [path for path in plugins.glob("genecraft-paper-*.jar")
                if path.is_file() and plugin_metadata(path).get("name") == "GeneCraft"]
    backups = server_dir / "backups"
    backups.mkdir(exist_ok=True)
    backup_dir = Path(tempfile.mkdtemp(prefix="genecraft-plugin-", dir=backups))
    # Stage outside plugins, so Paper cannot discover an incomplete/duplicate JAR.
    staged = backup_dir / "new-plugin.tmp"
    shutil.copy2(jar, staged)
    moved: list[Path] = []
    try:
        for old in previous:
            old.replace(backup_dir / old.name)
            moved.append(old)
        staged.replace(destination)
    except OSError:
        for old in moved:
            (backup_dir / old.name).replace(old)
        raise
    print(f"Previous plugin JARs backed up in: {backup_dir}")
    return destination


def fetch_json(url: str) -> object:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def download_paper() -> tuple[str, Path]:
    target = SERVER_DIR / "paper.jar"
    if target.exists():
        print("Keeping the existing Paper server JAR; remove it manually only if you intend to upgrade the demo.")
        return "existing", target
    builds = fetch_json(f"https://fill.papermc.io/v3/projects/paper/versions/{GAME_VERSION}/builds")
    if not isinstance(builds, list):
        raise RuntimeError("PaperMC did not return a build list for Minecraft " + GAME_VERSION)
    stable = next((item for item in builds if item.get("channel") == "STABLE"), None)
    if not stable:
        raise RuntimeError("No stable Paper build is available for Minecraft " + GAME_VERSION)
    download = stable["downloads"]["server:default"]
    temp = SERVER_DIR / "paper.jar.download"
    request = urllib.request.Request(download["url"], headers={"User-Agent": USER_AGENT})
    digest = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=120) as response, temp.open("wb") as output:
        shutil.copyfileobj(response, output)
        output.flush()
        os.fsync(output.fileno())
    with temp.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    if digest.hexdigest() != download["checksums"]["sha256"]:
        temp.unlink(missing_ok=True)
        raise RuntimeError("Paper JAR checksum did not match PaperMC's published SHA-256.")
    temp.replace(target)
    return str(stable["id"]), target


def write_once(path: Path, content: str) -> None:
    if not path.exists():
        path.write_text(content, encoding="utf-8")


def main() -> None:
    if not PLUGIN_JAR.is_file():
        raise RuntimeError("Build the plugin first using scripts/build-plugin.sh.")
    SERVER_DIR.mkdir(parents=True, exist_ok=True)
    (DEMO_HOME / "client-game").mkdir(parents=True, exist_ok=True)
    build, paper_jar = download_paper()
    installed = install_plugin(PLUGIN_JAR, SERVER_DIR)
    write_once(SERVER_DIR / "eula.txt", "# Review https://www.minecraft.net/eula before accepting.\neula=false\n")
    properties = """# Private local GeneCraft sandbox: server binds to this device only.
motd=GeneCraft local demo
server-ip=127.0.0.1
server-port=25565
online-mode=true
max-players=2
white-list=false
enable-rcon=false
enable-query=false
pvp=false
view-distance=5
simulation-distance=4
allow-flight=true
spawn-protection=0
level-name=world
"""
    write_once(SERVER_DIR / "server.properties", properties)
    launcher = SERVER_DIR / ("start-server.bat" if os.name == "nt" else "start-server.command")
    if os.name == "nt":
        launcher.write_text(f'@echo off\ncd /d "{SERVER_DIR}"\njava -Xms512M -Xmx2G -jar paper.jar --nogui\npause\n', encoding="utf-8")
    else:
        launcher.write_text(
            "#!/bin/sh\nset -eu\ncd " + shlex.quote(str(SERVER_DIR)) + "\nexec java -Xms512M -Xmx2G -jar paper.jar --nogui\n",
            encoding="utf-8",
        )
        launcher.chmod(0o755)
    print(f"Demo directory: {DEMO_HOME}")
    print(f"Paper: Minecraft {GAME_VERSION}, build {build} ({paper_jar.stat().st_size:,} bytes)")
    print(f"Plugin installed: {installed}")
    print(f"Client game directory to select in Minecraft Launcher: {DEMO_HOME / 'client-game'}")
    print(f"Server start file: {launcher}")
    print("Existing world, settings and EULA choice were preserved. A new server starts with eula=false; read and accept the EULA before starting it.")


if __name__ == "__main__":
    main()
