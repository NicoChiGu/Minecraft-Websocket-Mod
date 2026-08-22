# Minecraft Tunnel Mod (WebSocket / gRPC)

A transparent Minecraft TCP <-> WebSocket & gRPC tunnel. The tunnel does not parse Minecraft packets, so protocol changes between Minecraft versions stay outside the tunnel core.

## Architecture

- **WebSocket Mode**: `Minecraft Client TCP <-> WS Tunnel Client <-> CDN / Reverse Proxy (WSS) <-> WS Tunnel Server <-> Minecraft Server TCP`
- **gRPC Mode**: `Minecraft Client TCP <-> gRPC Tunnel Client <-> CDN / Reverse Proxy (gRPC/HTTP2) <-> gRPC Tunnel Server <-> Minecraft Server TCP`

## Modules & Minecraft Version Support

- `core`: Minecraft-independent binary tunnel protocol, transport abstraction (WebSocket & gRPC), and TCP bridge.
- `fabric-common`: shared Fabric client/server adapter source.
- `fabric-1.20.1`: Fabric build supporting Minecraft **1.20.x** (1.20, 1.20.1, 1.20.2, 1.20.4, 1.20.6) / Java 17.
- `fabric-1.21.1`: Fabric build supporting Minecraft **1.21.x** (1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.11) / Java 21.
- `fabric-26.2`: Fabric build supporting Minecraft **26.x** (26.1, 26.2, etc.) / Java 21+.

Each version produces one mod JAR that can be installed on both the client and the dedicated server.

## Languages

The client UI automatically follows Minecraft's selected language. Bundled translations currently include:
- English (`en_us`)
- Simplified Chinese (`zh_cn`)
- Traditional Chinese (`zh_tw`)
- Japanese (`ja_jp`)
- Korean (`ko_kr`)
- German (`de_de`)
- French (`fr_fr`)
- Spanish (`es_es`)
- Brazilian Portuguese (`pt_br`)
- Russian (`ru_ru`)
- Italian (`it_it`)
- Polish (`pl_pl`)
- Turkish (`tr_tr`)
- Vietnamese (`vi_vn`)
- Indonesian (`id_id`)
- Ukrainian (`uk_ua`)
- Dutch (`nl_nl`)
- Czech (`cs_cz`)
- Hungarian (`hu_hu`)
- Thai (`th_th`)
- Swedish (`sv_se`)

## Client

Install the matching Fabric API and mod JAR. Open **Multiplayer** and click **WS Tunnel**.

The settings screen stores multiple local tunnel profiles. Existing values from
`config/minecraft-websocket/client.properties` are imported automatically into
`client-profiles.properties` the first time the new profile store is loaded.
Configure each profile with:

- Gateway URL, e.g. `wss://mc.example.com/tunnel`
- Token
- Display name
- Local TCP port, default `25566`

Select a profile and click **Connect**. Only one profile can be active at a time.
Connection setup runs off the Minecraft render thread and shows an experience-style
activity bar; profile editing is locked until the connection is stopped or fails.
The same screen also has a **Logs** button. Gateway URLs up to 2048 characters are
accepted, so long WSS host names and paths are not truncated by Minecraft's default
text-field limit. The **Client Settings** page stores global preferences separately in
`config/minecraft-websocket/client-settings.properties`: the network HUD can be
disabled, its opacity is adjustable in 5% steps, and startup update checks can be
turned off. Settings are saved through a temporary file and atomic replacement.

Each profile row also has a **Set Auto** button. At most one profile can be marked
for automatic connection; clicking **Cancel Auto** on that profile disables the
feature. Minecraft starts the selected tunnel after the client finishes starting,
without automatically joining the remote Minecraft server. If the initial gateway
connection fails, the client waits five seconds and retries up to five times.

The normal multiplayer screen keeps one responsive **WS Tunnel** settings button.
When the selected tunnel is running, an ephemeral WS server entry is pinned above
the saved server list. It uses the vanilla server status/ping presentation and the
localized **Quick Connect** action, but is never written to `servers.dat` and cannot
be edited, deleted, or reordered. While the tunnel is running, the top-right HUD
shows tunnel latency and rolling application-heartbeat packet loss beside an animated
9x9 pixel globe. It follows F1 HUD hiding and uses cyan, green, yellow, orange, and
red severity colors; the previous top-left connection message has been removed.

On a dedicated server with the mod installed, the server appends each player's current
latency to the Tab display name in gray (for example, `Player  42ms`) and sends the
updated display name and latency to clients once per second. The client does not draw
an additional numeric latency column; vanilla continues to render the signal bars.

## Updates

Stable releases are checked asynchronously through the GitHub Releases API. A client
update produces one toast; a dedicated-server update is logged to the console. Failed
checks never prevent the tunnel from starting. Run `/mcws update` on a standalone
client or from the dedicated-server console/RCON to download the matching target JAR.
Server players require permission level 2. `/mcws update client` and
`/mcws update server` are available when the target would otherwise be ambiguous.

GitHub is tried first. Network, timeout, or HTTP failure falls back to
`https://gh-proxy.org/` followed by the original GitHub API or release-asset URL. The
download is size-limited and verified against the asset size, optional SHA-256 digest,
ZIP structure, mod ID, mod version, and exact Minecraft dependency. The validated JAR
is staged next to the installed mod. A small Java helper waits for Minecraft to exit,
keeps one non-JAR `.mcws-backup`, atomically installs the new file, and restores the
backup if installation fails. Restart Minecraft or the server to activate the update.
Development runs, read-only directories, nested/multi-path mods, and installations
whose current JAR cannot be identified report a clear error instead of replacing files.

The in-game log viewer keeps the latest 500 client events in memory and supports older/newer paging and clearing the buffer. Connection failures, invalid settings, and configuration save failures are shown through a dedicated top-right tunnel toast while being retained in the log viewer. If an established gateway connection drops, the client closes the affected local Minecraft sessions and automatically retries up to five times, waiting five seconds before each attempt. One reusable toast counts down each wait instead of stacking notifications. A successful retry restores the local listener; after the fifth failure the tunnel returns to the stopped state and must be connected manually.

When the Minecraft client stops, the mod cancels pending connection or reconnect
work, closes local tunnel sockets, and waits up to two seconds for the WebSocket
close handshake before forcing the underlying socket closed.

While running, connect using the normal Minecraft multiplayer UI to:

`127.0.0.1:25566`

The complete path is:

```text
Minecraft client
  -> local TCP listener in client mod
  -> binary WebSocket/WSS
  -> CDN/reverse proxy
  -> WebSocket listener in server mod
  -> 127.0.0.1:25565
  -> Minecraft server
```

## Tunnel protocol v1

Binary WebSocket frames use a fixed 6-byte header:

```text
byte 0     protocol version (1)
byte 1     frame type
bytes 2-5  signed 32-bit connection id, network byte order
bytes 6..  raw payload
```

Frame types: `OPEN=1`, `DATA=2`, `CLOSE=3`, `PING=4`, `PONG=5`, `ERROR=6`.

One WebSocket session multiplexes multiple TCP connections by connection id. Minecraft packets are never decoded, re-encoded, or version translated.

## Build

The project includes a Windows-local Gradle bootstrap launcher. You do not need to install Gradle globally or add it to `PATH`.

From PowerShell in this directory, run:

```powershell
.\gradlew.bat :fabric-26.2:build
```

On first use, `gradlew.bat` downloads Gradle 9.5.1 into `.gradle-local/`, verifies its SHA-256 checksum, and keeps Gradle caches under `.gradle-user-home/`. The Foojay toolchain resolver is enabled in `settings.gradle`, so the Java 25 toolchain required by Minecraft 26.2 is automatically provisioned if Java 25 is not already installed. These local tool/cache directories are ignored by `.gitignore`.

Other targets can be built the same way:

```powershell
.\gradlew.bat :fabric-1.20.1:build
.\gradlew.bat :fabric-1.21.1:build
```

Artifacts are written under each module's `build/libs/` directory.

Gradle itself must run on Java 21 or newer because Loom 1.17.19 requires it. The
Fabric 1.20.1 module still uses a Java 17 toolchain and emits Java 17-compatible
bytecode; Fabric 1.21.1 uses Java 21, and Fabric 26.2 uses Java 25.

## GitHub Actions

The repository includes `.github/workflows/build-release.yml` at the repository root.

- **Manual build:** open the repository's **Actions** tab, select **Build Minecraft WebSocket Tunnel**, and choose **Run workflow**.
- **Automatic CI:** every branch push and pull request builds Fabric 1.20.1, 1.21.1, and 26.2 in parallel and uploads the installable JARs as workflow artifacts for 14 days.
- **Tag release:** pushing any Git tag builds all three targets, creates a GitHub Release with generated release notes, and uploads only the installable JARs (not sources JARs). Re-running the same tagged workflow replaces the release assets instead of failing because the release already exists.

For version tags, a leading `v` is stripped from the embedded mod version. For example, pushing `v0.2.0` produces `mcws-26.2-0.2.0.jar` and matching 1.20.1/1.21.1 JARs.

```powershell
git tag v0.2.0
git push origin v0.2.0
```

The release job uses the workflow's built-in `GITHUB_TOKEN` with `contents: write`; no personal access token or release secret is required under normal GitHub repository settings.

## Current scope

The implementation currently targets Fabric 1.20.1, 1.21.1, and 26.2. Minecraft 26.2 is the primary test target. Its build uses Java 25, Fabric Loader 0.19.3, Loom 1.17.19, and Fabric API 0.157.0+26.2. The project layout is designed for adding more Minecraft versions and Forge/NeoForge adapters without changing `core`.

The current transport intentionally closes active Minecraft TCP sessions if the WebSocket session dies. Transparent Minecraft session resume is not attempted.
