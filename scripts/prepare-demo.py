#!/usr/bin/env python3
"""Prepare a private Paper demo folder on the user's external drive."""

from __future__ import annotations

import hashlib
import json
import os
import shlex
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME_VERSION = "1.21.11"
DEMO_HOME = Path(os.environ.get(
    "GENECRAFT_DEMO_HOME",
    os.environ.get("FABLEORBIT_DEMO_HOME", str(Path.home() / "Minecraft" / "GeneCraft Demo")),
)).expanduser()
SERVER_DIR = DEMO_HOME / "server"
PLUGIN_JAR = ROOT / "target" / "genecraft-paper-0.2.0.jar"
USER_AGENT = "GeneCraft setup/0.2 (https://github.com/shrut10/genecraft)"


def fetch_json(url: str) -> object:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def download_paper() -> tuple[str, Path]:
    builds = fetch_json(f"https://fill.papermc.io/v3/projects/paper/versions/{GAME_VERSION}/builds")
    if not isinstance(builds, list):
        raise RuntimeError("PaperMC did not return a build list for Minecraft " + GAME_VERSION)
    stable = next((item for item in builds if item.get("channel") == "STABLE"), None)
    if not stable:
        raise RuntimeError("No stable Paper build is available for Minecraft " + GAME_VERSION)
    download = stable["downloads"]["server:default"]
    target = SERVER_DIR / "paper.jar"
    if target.exists():
        print("Keeping the existing Paper server JAR; remove it manually only if you intend to upgrade the demo.")
        return str(stable["id"]), target
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
    plugins = SERVER_DIR / "plugins"
    plugins.mkdir(exist_ok=True)
    shutil.copy2(PLUGIN_JAR, plugins / PLUGIN_JAR.name)
    build, paper_jar = download_paper()
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
    print(f"Paper: Minecraft {GAME_VERSION}, stable build {build} ({paper_jar.stat().st_size:,} bytes)")
    print(f"Plugin installed: {plugins / PLUGIN_JAR.name}")
    print(f"Client game directory to select in Minecraft Launcher: {DEMO_HOME / 'client-game'}")
    print(f"Server start file: {launcher}")
    print("The server EULA was left at eula=false. Read it and accept it yourself before starting the server.")


if __name__ == "__main__":
    main()
