package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ModUpdateService;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

final class FabricUpdateSupport implements AutoCloseable {
    static final String MOD_ID = "minecraft_websocket_tunnel";
    private final ModUpdateService service;

    private FabricUpdateSupport(ModUpdateService service) { this.service = service; }

    static FabricUpdateSupport create(String minecraftTarget) throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) throw new IOException("Online updates are disabled in development mode");
        ModContainer container = loader.getModContainer(MOD_ID)
            .orElseThrow(() -> new IOException("Could not locate the running mod container"));
        ModOrigin origin = container.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) throw new IOException("The running mod is nested inside another mod");
        List<Path> paths = origin.getPaths();
        if (paths.size() != 1) throw new IOException("The running mod has multiple origin paths");
        Path currentJar = paths.get(0).toAbsolutePath().normalize();
        if (!Files.isRegularFile(currentJar) || !currentJar.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IOException("The running mod did not originate from an installable JAR");
        }
        currentJar = currentJar.toRealPath();
        Path helperDirectory = loader.getConfigDir().resolve("minecraft-websocket").resolve("update");
        String currentVersion = container.getMetadata().getVersion().getFriendlyString();
        return new FabricUpdateSupport(new ModUpdateService(currentVersion, minecraftTarget, currentJar, helperDirectory));
    }

    ModUpdateService service() { return service; }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ModUpdateService.UpdateException)
            && current.getCause() != null) current = current.getCause();
        return current;
    }

    static String readableMessage(Throwable error) {
        Throwable unwrapped = unwrap(error);
        return unwrapped.getMessage() == null || unwrapped.getMessage().isBlank()
            ? unwrapped.getClass().getSimpleName() : unwrapped.getMessage();
    }

    @Override public void close() { service.close(); }
}
