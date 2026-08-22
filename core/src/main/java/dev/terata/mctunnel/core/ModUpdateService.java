package dev.terata.mctunnel.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks GitHub Releases and stages a verified, target-specific Fabric mod update. */
public final class ModUpdateService implements AutoCloseable {
    public static final String REPOSITORY = "NicoChiGu/Minecraft-Websocket-Mod";
    public static final URI LATEST_RELEASE_API = URI.create(
        "https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
    public static final String PROXY_PREFIX = "https://gh-proxy.org/";
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_API_BYTES = 1024 * 1024;
    private static final String MOD_ID = "minecraft_websocket_tunnel";

    public record ReleaseInfo(String tag, String version, String assetName, URI downloadUri,
                              long size, String digest) { }

    public record CheckResult(String currentVersion, ReleaseInfo latest) {
        public boolean releaseExists() { return latest != null; }
        public boolean updateAvailable() {
            return latest != null && compareVersions(latest.version(), currentVersion) > 0;
        }
    }

    public enum UpdateStatus { NO_RELEASE, UP_TO_DATE, STAGED }

    public record UpdateResult(UpdateStatus status, CheckResult check, Path stagedPath,
                               Path backupPath, Path helperJar, Path installerLog) { }

    private final String currentVersion;
    private final String minecraftTarget;
    private final Path currentJar;
    private final Path helperDirectory;
    private final HttpClient httpClient;
    private final URI latestReleaseApi;
    private final String proxyPrefix;
    private final ExecutorService executor;
    private final AtomicReference<CompletableFuture<UpdateResult>> activeUpdate = new AtomicReference<>();

    public ModUpdateService(String currentVersion, String minecraftTarget, Path currentJar, Path helperDirectory) {
        this(currentVersion, minecraftTarget, currentJar, helperDirectory,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(),
            LATEST_RELEASE_API, PROXY_PREFIX,
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mc-wss-update");
                thread.setDaemon(true);
                return thread;
            }));
    }

    ModUpdateService(String currentVersion, String minecraftTarget, Path currentJar, Path helperDirectory,
                     HttpClient httpClient, URI latestReleaseApi, String proxyPrefix, ExecutorService executor) {
        parseVersion(currentVersion);
        this.currentVersion = currentVersion;
        this.minecraftTarget = Objects.requireNonNull(minecraftTarget, "minecraftTarget");
        this.currentJar = currentJar.toAbsolutePath().normalize();
        this.helperDirectory = helperDirectory.toAbsolutePath().normalize();
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.latestReleaseApi = Objects.requireNonNull(latestReleaseApi, "latestReleaseApi");
        this.proxyPrefix = Objects.requireNonNull(proxyPrefix, "proxyPrefix");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletableFuture<CheckResult> checkAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try { return check(); }
            catch (IOException | InterruptedException e) { throw new UpdateException(e); }
        }, executor);
    }

    public synchronized CompletableFuture<UpdateResult> prepareUpdateAsync() {
        CompletableFuture<UpdateResult> existing = activeUpdate.get();
        if (existing != null && !existing.isDone()) return existing;
        CompletableFuture<UpdateResult> created = CompletableFuture.supplyAsync(() -> {
            try { return prepareUpdate(); }
            catch (IOException | InterruptedException e) { throw new UpdateException(e); }
        }, executor);
        activeUpdate.set(created);
        created.whenComplete((ignored, error) -> activeUpdate.compareAndSet(created, null));
        return created;
    }

    public CheckResult check() throws IOException, InterruptedException {
        Optional<String> json = fetchLatestReleaseJson();
        if (json.isEmpty()) return new CheckResult(currentVersion, null);
        if (shouldIgnoreRelease(json.get())) return new CheckResult(currentVersion, null);
        ReleaseInfo latest = parseRelease(json.get(), minecraftTarget);
        return new CheckResult(currentVersion, latest);
    }

    public UpdateResult prepareUpdate() throws IOException, InterruptedException {
        CheckResult check = check();
        if (!check.releaseExists()) {
            return new UpdateResult(UpdateStatus.NO_RELEASE, check, null, null, null, null);
        }
        if (!check.updateAvailable()) {
            return new UpdateResult(UpdateStatus.UP_TO_DATE, check, null, null, null, null);
        }

        ReleaseInfo release = check.latest();
        Path directory = currentJar.getParent();
        if (directory == null || !Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new IOException("Current mod directory is not writable: " + directory);
        }
        Path part = directory.resolve(currentJar.getFileName() + ".mcws-update.part");
        Path staged = directory.resolve(currentJar.getFileName() + ".mcws-update");
        Files.deleteIfExists(part);
        try {
            downloadRelease(release, part);
            validateJar(part, release);
            move(part, staged);
        } finally {
            Files.deleteIfExists(part);
        }

        Files.createDirectories(helperDirectory);
        Path helperJar = helperDirectory.resolve("mcws-update-helper.jar");
        writeHelperJar(helperJar);
        Path backup = directory.resolve(currentJar.getFileName() + ".mcws-backup");
        Path log = helperDirectory.resolve("update-installer.log");
        launchInstaller(helperJar, staged, backup, log);
        return new UpdateResult(UpdateStatus.STAGED, check, staged, backup, helperJar, log);
    }

    private Optional<String> fetchLatestReleaseJson() throws IOException, InterruptedException {
        try {
            return getText(latestReleaseApi);
        } catch (IOException directFailure) {
            try {
                return getText(proxied(latestReleaseApi));
            } catch (IOException proxyFailure) {
                proxyFailure.addSuppressed(directFailure);
                throw proxyFailure;
            }
        }
    }

    private Optional<String> getText(URI uri) throws IOException, InterruptedException {
        HttpRequest request = request(uri).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 404) {
            response.body().close();
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        try (InputStream input = response.body()) {
            return Optional.of(new String(readLimited(input, MAX_API_BYTES), StandardCharsets.UTF_8));
        }
    }

    private void download(URI uri, Path destination, long expectedSize) throws IOException, InterruptedException {
        HttpRequest request = request(uri).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " while downloading " + uri);
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredLength > MAX_DOWNLOAD_BYTES) {
            response.body().close();
            throw new IOException("Update exceeds the 64 MiB size limit");
        }
        try (InputStream input = response.body(); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Update exceeds the 64 MiB size limit");
                output.write(buffer, 0, read);
            }
            if (expectedSize >= 0L && total != expectedSize) {
                throw new IOException("Update size mismatch: expected " + expectedSize + ", got " + total);
            }
        }
    }

    void downloadRelease(ReleaseInfo release, Path destination) throws IOException, InterruptedException {
        try {
            download(release.downloadUri(), destination, release.size());
        } catch (IOException directFailure) {
            Files.deleteIfExists(destination);
            try {
                download(proxied(release.downloadUri()), destination, release.size());
            } catch (IOException proxyFailure) {
                proxyFailure.addSuppressed(directFailure);
                throw proxyFailure;
            }
        }
    }

    void validateJar(Path jar, ReleaseInfo release) throws IOException {
        if (release.digest() != null && !release.digest().isBlank()) {
            String digest = release.digest().trim().toLowerCase(Locale.ROOT);
            if (digest.startsWith("sha256:")) {
                String actual = sha256(jar);
                if (!actual.equals(digest.substring("sha256:".length()))) {
                    throw new IOException("Update SHA-256 digest mismatch");
                }
            }
        }

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadataEntry = zip.getEntry("fabric.mod.json");
            if (metadataEntry == null) throw new IOException("Update does not contain fabric.mod.json");
            JsonObject metadata;
            try (InputStream input = zip.getInputStream(metadataEntry)) {
                metadata = JsonParser.parseReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("Update contains invalid Fabric metadata", e);
            }
            if (!MOD_ID.equals(string(metadata, "id"))) throw new IOException("Update has the wrong mod id");
            if (!release.version().equals(string(metadata, "version"))) throw new IOException("Update has the wrong version");
            JsonObject depends = metadata.has("depends") && metadata.get("depends").isJsonObject()
                ? metadata.getAsJsonObject("depends") : null;
            if (depends == null || !depends.has("minecraft") || !matchesTarget(depends.get("minecraft"), minecraftTarget)) {
                throw new IOException("Update does not target Minecraft " + minecraftTarget);
            }
        }
    }

    private void writeHelperJar(Path destination) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, UpdateInstaller.class.getName());
        String classResource = "/" + UpdateInstaller.class.getName().replace('.', '/') + ".class";
        try (InputStream input = UpdateInstaller.class.getResourceAsStream(classResource)) {
            if (input == null) throw new IOException("Could not read embedded update installer class");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporary), manifest)) {
                jar.putNextEntry(new ZipEntry(classResource.substring(1)));
                input.transferTo(jar);
                jar.closeEntry();
            }
            move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void launchInstaller(Path helperJar, Path staged, Path backup, Path log) throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = javaHome.resolve("bin").resolve(windows && Files.exists(javaHome.resolve("bin/javaw.exe"))
            ? "javaw.exe" : windows ? "java.exe" : "java");
        if (!Files.isRegularFile(java)) throw new IOException("Java executable was not found: " + java);
        new ProcessBuilder(java.toString(), "-jar", helperJar.toString(),
            Long.toString(ProcessHandle.current().pid()), currentJar.toString(), staged.toString(),
            backup.toString(), log.toString())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start();
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Minecraft-Websocket-Tunnel/" + currentVersion);
    }

    private URI proxied(URI original) {
        return URI.create(proxyPrefix + original.toString());
    }

    static ReleaseInfo parseRelease(String json, String minecraftTarget) throws IOException {
        final JsonObject root;
        try { root = JsonParser.parseString(json).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("GitHub returned invalid release metadata", e); }
        if (booleanValue(root, "draft") || booleanValue(root, "prerelease")) {
            throw new IOException("GitHub latest release is not stable");
        }
        String tag = string(root, "tag_name");
        Version version = parseVersion(tag);
        String normalizedVersion = version.normalized();
        String expectedAsset = "mcws-" + minecraftTarget + "-" + normalizedVersion + ".jar";
        JsonArray assets = root.has("assets") && root.get("assets").isJsonArray()
            ? root.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) continue;
            JsonObject asset = element.getAsJsonObject();
            if (!expectedAsset.equals(string(asset, "name"))) continue;
            String download = string(asset, "browser_download_url");
            long size = asset.has("size") ? asset.get("size").getAsLong() : -1L;
            String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
                ? asset.get("digest").getAsString() : null;
            if (size > MAX_DOWNLOAD_BYTES) throw new IOException("Release asset exceeds the 64 MiB size limit");
            try {
                return new ReleaseInfo(tag, normalizedVersion, expectedAsset, URI.create(download), size, digest);
            } catch (IllegalArgumentException e) {
                throw new IOException("Release asset has an invalid download URL", e);
            }
        }
        throw new IOException("Release " + tag + " does not contain " + expectedAsset);
    }

    static int compareVersions(String left, String right) {
        return parseVersion(left).compareTo(parseVersion(right));
    }

    private static boolean shouldIgnoreRelease(String json) throws IOException {
        final JsonObject root;
        try { root = JsonParser.parseString(json).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("GitHub returned invalid release metadata", e); }
        if (booleanValue(root, "draft") || booleanValue(root, "prerelease")) return true;
        try {
            parseVersion(string(root, "tag_name"));
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static Version parseVersion(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Version string cannot be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Version string cannot be empty");
        }
        String normalized = trimmed;
        if ((normalized.startsWith("v") || normalized.startsWith("V")) && normalized.length() > 1) {
            char next = normalized.charAt(1);
            if (Character.isDigit(next) || next == '.' || next == '_' || next == '-') {
                normalized = normalized.substring(1).trim();
            }
        }
        if (normalized.isEmpty() || !normalized.matches("^[0-9A-Za-z][0-9A-Za-z._+-]*$")) {
            throw new IllegalArgumentException("Invalid version format: " + raw);
        }

        String core = normalized;
        int plusIndex = core.indexOf('+');
        if (plusIndex >= 0) {
            core = core.substring(0, plusIndex);
        }

        Matcher matcher = Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)(.*)$").matcher(core);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Version must start with a number: " + raw);
        }

        String digitsPart = matcher.group(1);
        String suffixPart = matcher.group(2);

        String[] digitStrings = digitsPart.split("\\.");
        List<Integer> numericParts = new ArrayList<>();
        for (String s : digitStrings) {
            try {
                numericParts.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid numeric segment in version: " + raw, e);
            }
        }

        return new Version(normalized, numericParts, suffixPart);
    }

    private static boolean matchesTarget(JsonElement value, String target) {
        if (value.isJsonPrimitive()) return target.equals(value.getAsString());
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonPrimitive() && target.equals(element.getAsString())) return true;
            }
        }
        return false;
    }

    private static boolean booleanValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static String string(JsonObject object, String key) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IOException("Release metadata is missing " + key);
        return object.get(key).getAsString();
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > limit) throw new IOException("GitHub response is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override public void close() { executor.shutdownNow(); }

    public static final class UpdateException extends RuntimeException {
        public UpdateException(Throwable cause) { super(cause); }
    }

    private record Version(String normalized, List<Integer> numericParts, String suffix) implements Comparable<Version> {
        private boolean isPrerelease() {
            if (suffix == null || suffix.isBlank()) return false;
            String lower = suffix.toLowerCase(Locale.ROOT);
            return lower.startsWith("-") || lower.contains("rc") || lower.contains("beta")
                || lower.contains("alpha") || lower.contains("preview") || lower.contains("dev")
                || lower.contains("snapshot") || lower.contains("pre");
        }

        private boolean isFix() {
            if (suffix == null || suffix.isBlank()) return false;
            String lower = suffix.toLowerCase(Locale.ROOT);
            return lower.startsWith("_") || lower.contains("fix") || lower.contains("patch")
                || lower.contains("hotfix") || lower.contains("sp");
        }

        @Override
        public int compareTo(Version other) {
            int maxLen = Math.max(numericParts.size(), other.numericParts.size());
            for (int i = 0; i < maxLen; i++) {
                int leftVal = i < numericParts.size() ? numericParts.get(i) : 0;
                int rightVal = i < other.numericParts.size() ? other.numericParts.get(i) : 0;
                int cmp = Integer.compare(leftVal, rightVal);
                if (cmp != 0) return cmp;
            }

            boolean leftHasSuffix = suffix != null && !suffix.isBlank();
            boolean rightHasSuffix = other.suffix != null && !other.suffix.isBlank();

            if (!leftHasSuffix && !rightHasSuffix) return 0;

            if (!leftHasSuffix) {
                return other.isPrerelease() ? 1 : (other.isFix() ? -1 : 1);
            }
            if (!rightHasSuffix) {
                return isPrerelease() ? -1 : (isFix() ? 1 : -1);
            }

            if (isPrerelease() && !other.isPrerelease()) return -1;
            if (!isPrerelease() && other.isPrerelease()) return 1;

            List<String> leftTokens = tokenize(suffix);
            List<String> rightTokens = tokenize(other.suffix);
            int maxTokens = Math.max(leftTokens.size(), rightTokens.size());
            for (int i = 0; i < maxTokens; i++) {
                if (i >= leftTokens.size()) return -1;
                if (i >= rightTokens.size()) return 1;
                String a = leftTokens.get(i);
                String b = rightTokens.get(i);
                boolean aNum = a.chars().allMatch(Character::isDigit);
                boolean bNum = b.chars().allMatch(Character::isDigit);
                int cmp;
                if (aNum && bNum) {
                    cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } else if (aNum != bNum) {
                    cmp = aNum ? -1 : 1;
                } else {
                    cmp = a.compareToIgnoreCase(b);
                }
                if (cmp != 0) return cmp;
            }
            return 0;
        }

        private static List<String> tokenize(String str) {
            List<String> tokens = new ArrayList<>();
            Matcher matcher = Pattern.compile("[0-9]+|[A-Za-z]+").matcher(str);
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            return tokens;
        }
    }
}
