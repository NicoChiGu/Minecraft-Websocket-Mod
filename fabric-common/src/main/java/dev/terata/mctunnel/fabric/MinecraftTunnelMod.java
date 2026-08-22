package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.GrpcTunnelServer;
import dev.terata.mctunnel.core.ServerConfig;
import dev.terata.mctunnel.core.TunnelServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

public final class MinecraftTunnelMod implements DedicatedServerModInitializer {
    private static volatile TunnelServer wsServer;
    private static volatile GrpcTunnelServer grpcServer;
    private static volatile FabricUpdateSupport updateSupport;
    private static int tabLatencyTicks;

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("mcws")
                .then(CommandManager.literal("update")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> startUpdate(context.getSource()))
                    .then(CommandManager.literal("server")
                        .executes(context -> startUpdate(context.getSource()))))));

        ServerLifecycleEvents.SERVER_STARTING.register(mcServer -> {
            try {
                Path configPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("minecraft-websocket")
                    .resolve("config.toml");
                ServerConfig config = ServerConfig.load(configPath);
                if (config.isGrpcMode() || config.isBothMode()) {
                    GrpcTunnelServer gServer = new GrpcTunnelServer(config);
                    gServer.start();
                    grpcServer = gServer;
                    System.out.println("[Minecraft Tunnel] Mode: gRPC. Listening on " + config.bindHost() + ":" + config.bindPort()
                        + " -> " + config.targetHost() + ":" + config.targetPort());
                }
                if (config.isWebSocketMode() || config.isBothMode()) {
                    TunnelServer tunnelServer = new TunnelServer(config);
                    tunnelServer.start();
                    wsServer = tunnelServer;
                    System.out.println("[Minecraft Tunnel] Mode: WebSocket. Listening on " + config.bindHost() + ":" + config.bindPort()
                        + " -> " + config.targetHost() + ":" + config.targetPort());
                }
                tabLatencyTicks = 0;
                try {
                    updateSupport = FabricUpdateSupport.create(MinecraftTunnelVersion.TARGET);
                    if (config.checkForUpdates()) checkForUpdates(mcServer);
                } catch (Exception updateError) {
                    System.err.println("[Minecraft Tunnel] Update service unavailable: "
                        + FabricUpdateSupport.readableMessage(updateError));
                }
            } catch (Exception e) {
                System.err.println("[Minecraft Tunnel] Failed to start server tunnel: " + e.getMessage());
                e.printStackTrace();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(mcServer -> {
            TunnelServer tunnelServer = wsServer;
            if (tunnelServer != null) {
                tunnelServer.shutdown();
                wsServer = null;
            }
            GrpcTunnelServer gServer = grpcServer;
            if (gServer != null) {
                gServer.shutdown();
                grpcServer = null;
            }
            FabricUpdateSupport updates = updateSupport;
            updateSupport = null;
            if (updates != null) updates.close();
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

    private static int startUpdate(ServerCommandSource source) {
        FabricUpdateSupport updates = updateSupport;
        if (updates == null) {
            source.sendError(Text.translatable("command.minecraft_websocket_tunnel.update_unavailable"));
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("command.minecraft_websocket_tunnel.update_checking"), false);
        updates.service().prepareUpdateAsync().whenComplete((result, error) -> source.getServer().execute(() -> {
            if (error != null) {
                source.sendError(Text.translatable("command.minecraft_websocket_tunnel.update_failed",
                    FabricUpdateSupport.readableMessage(error)));
                return;
            }
            Text feedback = switch (result.status()) {
                case NO_RELEASE -> Text.translatable("command.minecraft_websocket_tunnel.update_no_release");
                case UP_TO_DATE -> Text.translatable("command.minecraft_websocket_tunnel.update_current",
                    result.check().currentVersion());
                case STAGED -> Text.translatable("command.minecraft_websocket_tunnel.update_staged",
                    result.check().latest().version());
            };
            source.sendFeedback(() -> feedback, false);
        }));
        return 1;
    }

    private static void checkForUpdates(net.minecraft.server.MinecraftServer mcServer) {
        FabricUpdateSupport updates = updateSupport;
        if (updates == null) return;
        updates.service().checkAsync().whenComplete((result, error) -> mcServer.execute(() -> {
            if (error != null) {
                System.err.println("[Minecraft WebSocket Tunnel] Update check failed: "
                    + FabricUpdateSupport.readableMessage(error));
            } else if (result.updateAvailable()) {
                System.out.println("[Minecraft WebSocket Tunnel] Update " + result.latest().version()
                    + " is available. Run /mcws update to install it.");
            }
        }));
    }
}
