# Minecraft WebSocket Tunnel Mod

A transparent Minecraft TCP <-> WebSocket tunnel. The tunnel does not parse Minecraft packets, so protocol changes between Minecraft versions stay outside the tunnel core.

## Modules

- `core`: Minecraft-independent binary tunnel protocol and TCP/WebSocket bridge.
- `fabric-common`: shared Fabric client/server adapter source.
- `fabric-1.20.1`: Fabric build for Minecraft 1.20.1 / Java 17.
- `fabric-1.21.1`: Fabric build for Minecraft 1.21.1 / Java 21.
- `fabric-26.2`: primary test build for Minecraft 26.2 / Java 25. This module has a dedicated thin GUI adapter because 26.2 moved to Mojang's unobfuscated names and reorganized the screen APIs.

Each version produces one mod JAR that can be installed on both the client and the dedicated server. The tunnel core is shared; Minecraft-specific code is intentionally thin. The dedicated-server tunnel is registered through Fabric's `server` entrypoint, so it is not started by the physical client or by singleplayer/integrated servers.

## Server

Install the matching Fabric API and the generated mod JAR. On first server start the mod creates:

`config/minecraft-websocket/config.toml`

Example:

```toml
bind-host = "0.0.0.0"
bind-port = 8080
target-host = "127.0.0.1"
target-port = 25565
path = "/tunnel"
token = "replace-with-a-long-random-token"
```

The WebSocket listener is plain `ws://` by default. Put a TLS-capable CDN/reverse proxy in front of it and publish, for example, `wss://mc.example.com/tunnel` on port 443. Keep the origin listener private whenever possible.

The token is sent in the WebSocket `Authorization: Bearer ...` header, not in the URL.

## Languages

The client UI follows Minecraft's selected language. Bundled translations currently include English (`en_us`), Simplified Chinese (`zh_cn`), Traditional Chinese (`zh_tw`), Japanese (`ja_jp`), Korean (`ko_kr`), German (`de_de`), French (`fr_fr`), Spanish (`es_es`), Russian (`ru_ru`), and Brazilian Portuguese (`pt_br`).

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
text-field limit.

Each profile row also has a **Set Auto** button. At most one profile can be marked
for automatic connection; clicking **Cancel Auto** on that profile disables the
feature. Minecraft starts the selected tunnel after the client finishes starting,
without automatically joining the remote Minecraft server. If the initial gateway
connection fails, the client waits five seconds and retries up to five times.

The normal multiplayer screen keeps one responsive **WS Tunnel** settings button.
When the selected tunnel is running, an ephemeral WS server entry is pinned above
the saved server list. It uses the vanilla server status/ping presentation and the
localized **Quick Connect** action, but is never written to `servers.dat` and cannot
be edited, deleted, or reordered. While playing, the top-left HUD shows only the
tunnel connection state in red or green.

On a dedicated server with the mod installed, the Tab player list appends each player's current latency in gray (for example, `Player  42ms`) while preserving existing player names, team formatting, ordering, and the vanilla signal bars. The server refreshes the displayed values once per second.

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

## GitHub Actions

The repository includes `.github/workflows/minecraft-mod.yml` at the repository root.

- **Manual build:** open the repository's **Actions** tab, select **Minecraft Mod Build**, and choose **Run workflow**.
- **Automatic CI:** every branch push and pull request builds Fabric 1.20.1, 1.21.1, and 26.2 in parallel and uploads the installable JARs as workflow artifacts for 14 days.
- **Tag release:** pushing any Git tag builds all three targets, creates a GitHub Release with generated release notes, and uploads only the installable JARs (not sources JARs). Re-running the same tagged workflow replaces the release assets instead of failing because the release already exists.

For version tags, a leading `v` is stripped from the embedded mod version. For example, pushing `v0.2.0` produces `fabric-26.2-0.2.0.jar` and matching 1.20.1/1.21.1 JARs.

```powershell
git tag v0.2.0
git push origin v0.2.0
```

The release job uses the workflow's built-in `GITHUB_TOKEN` with `contents: write`; no personal access token or release secret is required under normal GitHub repository settings.

## Current scope

The implementation currently targets Fabric 1.20.1, 1.21.1, and 26.2. Minecraft 26.2 is the primary test target. Its build uses Java 25, Fabric Loader 0.19.3, Loom 1.17, and Fabric API 0.157.0+26.2. The project layout is designed for adding more Minecraft versions and Forge/NeoForge adapters without changing `core`.

The current transport intentionally closes active Minecraft TCP sessions if the WebSocket session dies. Transparent Minecraft session resume is not attempted.
