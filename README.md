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

Configure:

- Gateway URL, e.g. `wss://mc.example.com/tunnel`
- Token
- Display name
- Local TCP port, default `25566`

Click **Start / Stop**. The same screen also has a **Logs** button. The in-game log viewer keeps the latest 500 client events in memory and supports older/newer paging and clearing the buffer. Connection failures, unexpected gateway disconnects, invalid settings, and configuration save failures are also shown through Minecraft's native top-right system toast while being retained in the log viewer.

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

## Current scope

The implementation currently targets Fabric 1.20.1, 1.21.1, and 26.2. Minecraft 26.2 is the primary test target. Its build uses Java 25, Fabric Loader 0.19.3, Loom 1.17, and Fabric API 0.157.0+26.2. The project layout is designed for adding more Minecraft versions and Forge/NeoForge adapters without changing `core`.

The current transport intentionally closes active Minecraft TCP sessions if the WebSocket session dies. Transparent Minecraft session resume is not attempted.
