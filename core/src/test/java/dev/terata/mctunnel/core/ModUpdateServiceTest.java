package dev.terata.mctunnel.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModUpdateServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void comparesStableAndPrereleaseVersions() {
        assertTrue(ModUpdateService.compareVersions("1.2.0", "1.1.9") > 0);
        assertTrue(ModUpdateService.compareVersions("1.0.0", "1.0.0-rc.1") > 0);
        assertTrue(ModUpdateService.compareVersions("1.0.0-rc.2", "1.0.0-rc.1") > 0);
        assertEquals(0, ModUpdateService.compareVersions("v1.2.3", "1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> ModUpdateService.compareVersions("latest", "1.0.0"));
    }

    @Test
    void selectsTheExactMinecraftReleaseAsset() throws Exception {
        String json = releaseJson("v2.4.0", false, false,
            "fabric-1.20.1-2.4.0.jar", 123L, "sha256:abc");
        ModUpdateService.ReleaseInfo info = ModUpdateService.parseRelease(json, "1.20.1");

        assertEquals("2.4.0", info.version());
        assertEquals("fabric-1.20.1-2.4.0.jar", info.assetName());
        assertEquals(123L, info.size());
    }

    @Test
    void rejectsPrereleaseAndMissingTargetAsset() {
        assertThrows(Exception.class, () -> ModUpdateService.parseRelease(
            releaseJson("v2.4.0", false, true, "fabric-1.20.1-2.4.0.jar", 1L, null), "1.20.1"));
        assertThrows(Exception.class, () -> ModUpdateService.parseRelease(
            releaseJson("v2.4.0", false, false, "fabric-26.2-2.4.0.jar", 1L, null), "1.20.1"));
    }

    @Test
    void ignoresUnstableAndInvalidLatestReleaseTags() throws Exception {
        HttpServer server = startServer();
        try {
            server.createContext("/latest", exchange -> respond(exchange, 200,
                releaseJson("nightly", false, false, "unused.jar", 1L, null)));
            try (ModUpdateService service = service(server, "/latest")) {
                assertFalse(service.check().releaseExists());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToProxyForReleaseApiAndAssetDownload() throws Exception {
        byte[] jarBytes = "downloaded".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer();
        try {
            String base = base(server);
            server.createContext("/latest", exchange -> respond(exchange, 503, "direct failed"));
            server.createContext("/asset", exchange -> respond(exchange, 503, "direct failed"));
            server.createContext("/proxy/", exchange -> {
                if (exchange.getRequestURI().toString().contains("/asset")) {
                    respond(exchange, 200, jarBytes);
                } else {
                    respond(exchange, 200, releaseJson("v2.0.0", false, false,
                        "fabric-1.20.1-2.0.0.jar", jarBytes.length, null, base + "/asset"));
                }
            });
            try (ModUpdateService service = service(server, "/latest")) {
                ModUpdateService.CheckResult check = service.check();
                assertTrue(check.updateAvailable());
                Path downloaded = temporaryDirectory.resolve("fallback.jar");
                service.downloadRelease(check.latest(), downloaded);
                assertEquals("downloaded", Files.readString(downloaded));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWrongDownloadSize() throws Exception {
        byte[] content = "too short".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer();
        try {
            server.createContext("/asset", exchange -> respond(exchange, 200, content));
            server.createContext("/proxy/", exchange -> respond(exchange, 200, content));
            try (ModUpdateService service = service(server, "/latest")) {
                ModUpdateService.ReleaseInfo release = new ModUpdateService.ReleaseInfo("v2.0.0", "2.0.0",
                    "fabric-1.20.1-2.0.0.jar", URI.create(base(server) + "/asset"), content.length + 1L, null);
                assertThrows(IOException.class, () -> service.downloadRelease(release,
                    temporaryDirectory.resolve("wrong-size.jar")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesDigestZipAndFabricMetadataBeforeStaging() throws Exception {
        Path valid = writeModJar("valid.jar", "minecraft_websocket_tunnel", "2.0.0", "1.20.1");
        try (ModUpdateService service = offlineService()) {
            ModUpdateService.ReleaseInfo validRelease = releaseFor(valid, "2.0.0", "sha256:" + sha256(valid));
            service.validateJar(valid, validRelease);

            assertThrows(IOException.class, () -> service.validateJar(valid,
                releaseFor(valid, "2.0.0", "sha256:" + "00".repeat(32))));

            Path corrupt = temporaryDirectory.resolve("corrupt.jar");
            Files.writeString(corrupt, "not a zip");
            assertThrows(IOException.class, () -> service.validateJar(corrupt,
                releaseFor(corrupt, "2.0.0", null)));

            Path wrongId = writeModJar("wrong-id.jar", "some_other_mod", "2.0.0", "1.20.1");
            Path wrongVersion = writeModJar("wrong-version.jar", "minecraft_websocket_tunnel", "9.9.9", "1.20.1");
            Path wrongTarget = writeModJar("wrong-target.jar", "minecraft_websocket_tunnel", "2.0.0", "1.21.1");
            assertThrows(IOException.class, () -> service.validateJar(wrongId, releaseFor(wrongId, "2.0.0", null)));
            assertThrows(IOException.class, () -> service.validateJar(wrongVersion, releaseFor(wrongVersion, "2.0.0", null)));
            assertThrows(IOException.class, () -> service.validateJar(wrongTarget, releaseFor(wrongTarget, "2.0.0", null)));
        }
    }

    @Test
    void standaloneInstallerReplacesJarAndKeepsBackup() throws Exception {
        Path current = temporaryDirectory.resolve("mod.jar");
        Path staged = temporaryDirectory.resolve("mod.jar.mcws-update");
        Path backup = temporaryDirectory.resolve("mod.jar.mcws-backup");
        Files.writeString(current, "old");
        Files.writeString(staged, "new");

        UpdateInstaller.install(current, staged, backup);

        assertEquals("new", Files.readString(current));
        assertEquals("old", Files.readString(backup));
        assertFalse(Files.exists(staged));
    }

    @Test
    void installerDoesNotTouchCurrentJarWhenStageIsMissing() throws Exception {
        Path current = temporaryDirectory.resolve("mod.jar");
        Files.writeString(current, "old");

        assertThrows(Exception.class, () -> UpdateInstaller.install(current,
            temporaryDirectory.resolve("missing"), temporaryDirectory.resolve("backup")));
        assertEquals("old", Files.readString(current));
    }

    @Test
    void installerRollsBackWhenInstallingStagedJarFails() throws Exception {
        Path current = temporaryDirectory.resolve("rollback.jar");
        Path staged = temporaryDirectory.resolve("rollback.jar.mcws-update");
        Path backup = temporaryDirectory.resolve("rollback.jar.mcws-backup");
        Files.writeString(current, "old");
        Files.writeString(staged, "new");
        int[] moves = {0};

        assertThrows(IOException.class, () -> UpdateInstaller.install(current, staged, backup,
            (source, target, replace) -> {
                moves[0]++;
                if (moves[0] == 2) throw new IOException("simulated install failure");
                if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(source, target);
            }));

        assertEquals("old", Files.readString(current));
        assertEquals("new", Files.readString(staged));
        assertFalse(Files.exists(backup));
    }

    private static String releaseJson(String tag, boolean draft, boolean prerelease,
                                      String asset, long size, String digest) {
        return releaseJson(tag, draft, prerelease, asset, size, digest,
            "https://github.com/example/release.jar");
    }

    private static String releaseJson(String tag, boolean draft, boolean prerelease,
                                      String asset, long size, String digest, String downloadUrl) {
        return "{" +
            "\"tag_name\":\"" + tag + "\"," +
            "\"draft\":" + draft + "," +
            "\"prerelease\":" + prerelease + "," +
            "\"assets\":[{" +
            "\"name\":\"" + asset + "\"," +
            "\"browser_download_url\":\"" + downloadUrl + "\"," +
            "\"size\":" + size + "," +
            "\"digest\":" + (digest == null ? "null" : "\"" + digest + "\"") +
            "}]}";
    }

    private ModUpdateService service(HttpServer server, String apiPath) {
        return new ModUpdateService("1.0.0", "1.20.1", temporaryDirectory.resolve("current.jar"),
            temporaryDirectory.resolve("helper"),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            URI.create(base(server) + apiPath), base(server) + "/proxy/",
            Executors.newSingleThreadExecutor());
    }

    private ModUpdateService offlineService() {
        return new ModUpdateService("1.0.0", "1.20.1", temporaryDirectory.resolve("current.jar"),
            temporaryDirectory.resolve("helper"), HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:1/latest"), "http://127.0.0.1:1/proxy/",
            Executors.newSingleThreadExecutor());
    }

    private ModUpdateService.ReleaseInfo releaseFor(Path path, String version, String digest) throws IOException {
        return new ModUpdateService.ReleaseInfo("v" + version, version,
            "fabric-1.20.1-" + version + ".jar", path.toUri(), Files.size(path), digest);
    }

    private Path writeModJar(String name, String id, String version, String target) throws IOException {
        Path path = temporaryDirectory.resolve(name);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new ZipEntry("fabric.mod.json"));
            jar.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                + "\",\"depends\":{\"minecraft\":\"" + target + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return path;
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
