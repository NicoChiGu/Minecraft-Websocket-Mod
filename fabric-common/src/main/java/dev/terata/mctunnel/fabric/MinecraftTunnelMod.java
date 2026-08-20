package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ServerConfig;
import dev.terata.mctunnel.core.TunnelServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

public final class MinecraftTunnelMod implements DedicatedServerModInitializer {
    private static volatile TunnelServer server;
    private static int tabLatencyTicks;

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
                tabLatencyTicks = 0;
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

        ServerTickEvents.END_SERVER_TICK.register(mcServer -> {
            if (++tabLatencyTicks < 20) return;
            tabLatencyTicks = 0;
            List<ServerPlayerEntity> players = mcServer.getPlayerManager().getPlayerList();
            if (players.isEmpty()) return;
            mcServer.getPlayerManager().sendToAll(new PlayerListS2CPacket(
                EnumSet.of(
                    PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
                    PlayerListS2CPacket.Action.UPDATE_LATENCY
                ),
                players
            ));
        });
    }
}
