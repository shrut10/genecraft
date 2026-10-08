# GeneCraft

> **GeneCraft is an independent community project. It is not an official Minecraft product or service and is not approved by or associated with Mojang or Microsoft.**

GeneCraft is a local-first agent sandbox for **Minecraft Java Edition**. A Paper server hosts named agents with visible forms, saved goals and memory, and an optional opt-in observation loop. A small local Python bridge can connect an eligible ChatGPT account so a model chooses from a short, validated list of in-game actions.

The project is cross-platform: the Paper server and Python bridge run on macOS, Windows, and Linux. A Minecraft Java client can connect from any platform to the server. It is not a Bedrock Edition add-on. The easiest way to try it is the downloadable Paper plugin in [GitHub Releases](https://github.com/shrut10/genecraft/releases); source builds are available below.

## What it does

- Spawn a tagged villager, allay, cat, wolf, or fox. A player-style agent can use a Minecraft profile skin: `/genecraft spawn Gen skin shruts`.
- Run a local pathfinding demo without connecting an AI account.
- Give an agent a persistent goal and enable periodic observation of nearby structured game state.
- Save short memories between turns and pass bounded messages to another nearby agent you own.
- Use a separate, hard-coded Bodyguard Bill behavior that follows its owner and reacts to nearby hostiles without model calls.
- Optionally connect through OpenAI's Sign in with ChatGPT flow. Account eligibility and permission are required; an API key is not used.

The observation loop is deliberately bounded: it runs at a configurable interval, only while the owner is online and near the agent, and is limited to two active agents per owner and 60 automated model calls per server hour. The loop sees a compact snapshot such as nearby entity types and distances, weather, time, waypoints, saved memory, and incoming messages. It does not see screenshots or the player's full world. An agent can speak, follow its owner briefly, go to a saved waypoint, stop, remember one short note, or message another nearby owned agent. It cannot issue server commands, run code, attack, break or place blocks, or choose arbitrary destinations.

## Try it

### 1. Get the plugin

Download `genecraft-paper-0.2.0.jar` from [Releases](https://github.com/shrut10/genecraft/releases) and place it in the `plugins` folder of a Paper server running Minecraft 1.21.11 or compatible. Start the server once and review its EULA prompt. The demo-preparation script below can create a private local server and install the plugin for you.

### 2. Prepare a private demo server (optional)

Install Java 21 or newer and Python 3.10 or newer. From the repository folder, build the plugin with `./scripts/build-plugin.sh` on macOS/Linux or `scripts\\build-plugin.ps1` in PowerShell on Windows. Then prepare a local server folder:

**macOS/Linux**

```sh
export GENECRAFT_DEMO_HOME="$HOME/Minecraft/GeneCraft Demo"
python3 scripts/prepare-demo.py
```

**Windows PowerShell**

```powershell
$env:GENECRAFT_DEMO_HOME = "$HOME\Minecraft\GeneCraft Demo"
python scripts/prepare-demo.py
```

The script downloads Paper's stable Minecraft 1.21.11 build and verifies the published SHA-256 checksum. It writes `eula=false`; read the [Minecraft EULA](https://www.minecraft.net/eula) and only change that setting if you accept it. Start the server with `start-server.command` on macOS/Linux or `start-server.bat` on Windows. Select the generated `client-game` folder as the Minecraft Launcher's game directory, start Minecraft Java Edition, and connect to `127.0.0.1:25565`.

The server binds to your own device by default. Keep it private unless you understand and configure the security requirements for a networked server.

### 3. Start the optional ChatGPT bridge

**macOS/Linux**

```sh
./scripts/setup-bridge.sh
./scripts/start-bridge.sh
```

**Windows PowerShell**

```powershell
.\scripts\setup-bridge.ps1
.\scripts\start-bridge.ps1
```

In Minecraft, run `/genecraft login` and use **Continue with ChatGPT** to review and complete sign-in. Access depends on OpenAI account eligibility and the permission you grant. Model use is governed by OpenAI's current sign-in flow and account limits. The bridge listens only on `127.0.0.1`; it stores its local token and sign-in data in your user configuration directory (`~/.config/genecraft` on macOS/Linux or `%LOCALAPPDATA%\\GeneCraft` on Windows). Existing prototype data in `~/.config/fableorbit` is copied forward when the default directory is used.

## Play

Run these in Minecraft chat:

```text
/genecraft spawn Gen allay
/genecraft spawn Echo skin <Minecraft-player-name>
/genecraft demo Gen
/genecraft waypoint set lookout
/genecraft ask Gen go to the lookout waypoint
/genecraft goal Gen set Explore the lookout, remember useful nearby places, and tell Echo if you find something interesting
/genecraft autonomy Gen on 90
/genecraft inbox Echo
/genecraft autonomy Gen off
/genecraft remove Gen
```

Use `/genecraft help` for the complete command list. Skin profiles are resolved by the Paper server from the public Minecraft profile service. If profile lookup or mannequin display is unavailable on a server build, the plugin reports or falls back to its normal villager form. Player-style avatars use Paper's mannequin API with a pathfinding controller; the other forms use their native Minecraft entity types.

Autonomous model calls can consume the connected account's usage. The loop is off by default. Set an explicit goal before enabling it; `/genecraft autonomy <name> off` stops it. `/genecraft goal <name> clear` removes the saved goal. The server stores goals, memories, and agent messages in the plugin's `config.yml` and carries them across restarts.

## Build and verify

Requirements: Java 21+, Maven 3.8+, Python 3.10+.

```sh
mvn --batch-mode --no-transfer-progress verify
python3 -m pip install -r bridge/requirements.txt
python3 -m unittest discover -s tests -v
```

The shaded plugin is created at `target/genecraft-paper-0.2.0.jar`. GitHub Actions builds and runs the bridge tests on Ubuntu, macOS, and Windows for pushes and pull requests.

## Privacy and security

- The local bridge has no hosted component and binds to loopback. Do not expose port 8765 or share its token.
- ChatGPT requests include the typed request, agent goal, and limited local context (approximate positions, nearby entity types and distances, waypoint names, memory, and messages). Do not put secrets or personal data in goals or messages.
- Sign-in uses PKCE and validates the returned identity token. Credentials are written locally with restrictive permissions where supported by the operating system; do not commit credentials, server folders, world data, or game files.
- Model output is validated by the plugin against the fixed action list before it can affect the game. Local world observation uses server-side structured entity data, not screenshots.
- The MIT license covers this repository's original source only. It does not grant rights to Minecraft or third-party assets. GeneCraft does not redistribute Minecraft files, a Paper server, or game assets. Keep the non-affiliation notice when redistributing the project.
- ChatGPT connection behavior follows OpenAI's current [Sign in with ChatGPT guidance](https://developers.openai.com/siwc/token-sharing-open-source) and [UI guidance](https://developers.openai.com/siwc/ui-ux-guidelines). Minecraft use is subject to the [EULA](https://www.minecraft.net/eula) and [usage guidelines](https://www.minecraft.net/usage-guidelines).

Report security issues privately using GitHub's **Report a vulnerability** link on the repository's Security page; please do not publish working exploit details in a public issue.

## Contributing

Bug reports, feature proposals, documentation fixes, and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change. Please keep new agent actions narrow, explicit, and validated on the server; preserve the local-only bridge default and privacy disclosures.

## Project references

- [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper server setup and Java requirements](https://docs.papermc.io/paper/getting-started/)
- [Paper entity pathfinder API](https://docs.papermc.io/paper/dev/entity-pathfinder/)
- [Paper Mannequin API](https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Mannequin.html)
- [OpenAI Sign in with ChatGPT for open-source apps](https://developers.openai.com/siwc/token-sharing-open-source)
- [OpenAI sign-in and token validation](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
