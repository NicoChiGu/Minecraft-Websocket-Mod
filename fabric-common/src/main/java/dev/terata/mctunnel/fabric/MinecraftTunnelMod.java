package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ServerConfig;
import dev.terata.mctunnel.core.TunnelServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class MinecraftTunnelMod implements DedicatedServerModInitializer {
    private static volatile TunnelServer server;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(mcServer -> {
            try {
                Path configPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("minecraft-websocket")
                    .resolve("config.toml");
                ServerConfig config = ServerConfig.load(configPath);
                TunnelServer tunnelServer = new TunnelServer(config);
                tunnelServer.start();
                server = tunnelServer;
                System.out.println("[Minecraft WebSocket Tunnel] Listening on " + config.bindHost() + ":" + config.bindPort()
                    + " -> " + config.targetHost() + ":" + config.targetPort());
            } catch (Exception e) {
                System.err.println("[Minecraft WebSocket Tunnel] Failed to start server tunnel: " + e.getMessage());
                e.printStackTrace();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(mcServer -> {
            TunnelServer tunnelServer = server;
            if (tunnelServer != null) {
                tunnelServer.shutdown();
                server = null;
            }
        });
    }
}
