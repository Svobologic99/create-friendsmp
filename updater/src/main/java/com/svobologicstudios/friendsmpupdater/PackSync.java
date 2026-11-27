package com.svobologicstudios.friendsmpupdater;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Makes the game folder match a packwiz pack (pack.toml + index.toml + *.pw.toml). */
public final class PackSync {
    public static final String DEFAULT_PACK_URL =
            "https://raw.githubusercontent.com/Svobologic99/create-friendsmp/main/pack.toml";
    private static final String UPDATER_PREFIX = "friendsmp-updater";
    private static final String USER_AGENT =
            "SvobologicStudios/friendsmp-updater (github.com/Svobologic99/create-friendsmp)";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Result {
        public int added, updated, removed;
        public boolean pendingOps;
        public String error;

        public boolean needsRestart() {
            return added + updated + removed > 0 || pendingOps;
        }

        public String summary() {
            return added + " added, " + updated + " updated, " + removed + " removed"
                    + (pendingOps ? ", some changes finish after Minecraft closes" : "")
                    + (error != null ? ", error: " + error : "");
        }
    }

    /** What we remember between launches. */
    static final class State {
        Set<String> managed = new HashSet<>();
        Map<String, Rec> hashes = new HashMap<>();
    }

    static final class Rec {
        String algo;
        String hash;
        long size;
        long mtime;
    }

    private record Target(String rel, URI url, String algo, String hash, boolean preserve) {}

    private final Path gameDir;
    private final Path dataDir;
    private final Path cacheDir;
    private final Path tmpDir;
    private final Path pendingFile;
    private final Path ownJar;
    private final HttpClient http;
    private final Result result = new Result();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private State state;

    public PackSync(Path gameDir, Path configDir, Path ownJar) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.dataDir = configDir.resolve("friendsmp-updater");
        this.cacheDir = dataDir.resolve("cache");
        this.tmpDir = dataDir.resolve("tmp");
        this.pendingFile = dataDir.resolve("pending.txt");
        this.ownJar = ownJar;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public Result run() throws Exception {
        Files.createDirectories(cacheDir);
        Files.createDirectories(tmpDir);
        Properties cfg = loadConfig();
        if (!Boolean.parseBoolean(cfg.getProperty("enabled", "true"))) {
            FriendSmpUpdater.LOG.info("[FriendSMP Updater] Disabled in config");
            return result;
        }
        state = loadState();

        // Leftovers from last time (if the helper could not finish them).
        List<String> pending = PendingOps.runAll(PendingOps.read(pendingFile));

        Progress progress = Progress.start("FriendSMP: checking modpack", 3);
        try {
            URI packUri = new URI(cfg.getProperty("pack-url", DEFAULT_PACK_URL).trim());
            UnmodifiableConfig pack = parse(new String(getBytes(packUri, 30), StandardCharsets.UTF_8));
            progress.step();

            String indexPath = pack.get("index.file");
            String indexFmt = pack.getOrElse("index.hash-format", "sha256");
            String indexHash = pack.get("index.hash");
            URI indexUri = packUri.resolve(encodePath(indexPath));
            byte[] indexBytes = getBytes(indexUri, 30);
            if (!checkHash(indexBytes, indexFmt, indexHash)) throw new IOException("index.toml hash mismatch");
            UnmodifiableConfig index = parse(new String(indexBytes, StandardCharsets.UTF_8));
            String defaultFmt = index.getOrElse("hash-format", "sha256");
            List<UnmodifiableConfig> files = index.getOrElse("files", List.of());
            progress.step();

            // Index paths are relative to the folder that holds index.toml; that folder is the game folder.
            String indexDir = indexPath.contains("/") ? indexPath.substring(0, indexPath.lastIndexOf('/') + 1) : "";

            Map<String, Target> targets = new ConcurrentHashMap<>();
            Set<String> packProjects = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                Progress meta = Progress.start("FriendSMP: reading mod list", files.size());
                List<Future<?>> jobs = new ArrayList<>();
                for (UnmodifiableConfig f : files) {
                    jobs.add(pool.submit(() -> {
                        try {
                            readEntry(f, indexUri, indexDir, defaultFmt, targets, packProjects);
                        } catch (Exception e) {
                            errors.add(f.get("file") + ": " + e.getMessage());
                        }
                        meta.step();
                    }));
                }
                waitAll(jobs);
                meta.done();
                if (!errors.isEmpty()) {
                    // Never delete anything if we could not read the whole pack.
                    throw new IOException("Could not read the mod list (" + errors.size() + " files), e.g. " + errors.get(0));
                }

                // Find what needs downloading.
                Progress check = Progress.start("FriendSMP: checking files", targets.size());
                List<Target> toGet = Collections.synchronizedList(new ArrayList<>());
                jobs.clear();
                for (Target t : targets.values()) {
                    jobs.add(pool.submit(() -> {
                        try {
                            Path local = resolveSafe(t.rel());
                            if (Files.exists(local)) {
                                if (t.preserve() || matches(t.rel(), local, t.algo(), t.hash())) {
                                    if (!t.preserve()) {
                                        synchronized (state) {
                                            state.managed.add(t.rel());
                                        }
                                    }
                                } else {
                                    toGet.add(t);
                                }
                            } else {
                                toGet.add(t);
                            }
                        } catch (Exception e) {
                            errors.add(t.rel() + ": " + e.getMessage());
                        }
                        check.step();
                    }));
                }
                waitAll(jobs);
                check.done();

                // Download.
                if (!toGet.isEmpty()) {
                    Progress dl = Progress.start("FriendSMP: downloading " + toGet.size() + " files", toGet.size());
                    jobs.clear();
                    for (Target t : toGet) {
                        jobs.add(pool.submit(() -> {
                            try {
                                install(t, pending);
                            } catch (Exception e) {
                                errors.add(t.rel() + ": " + e.getMessage());
                                FriendSmpUpdater.LOG.warn("[FriendSMP Updater] Download failed for {}", t.rel(), e);
                            }
                            dl.step();
                        }));
                    }
                    waitAll(jobs);
                    dl.done();
                }

                // Remove what is no longer in the pack (only if everything new arrived).
                if (errors.isEmpty()) removeOld(targets, packProjects, pending);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            progress.done();
            saveState();
            PendingOps.write(pendingFile, pending);
            if (!pending.isEmpty()) {
                result.pendingOps = true;
                scheduleHelper();
            }
            cleanTmp();
        }
        if (!errors.isEmpty()) {
            result.error = errors.size() + " file(s) failed, e.g. " + errors.get(0);
        }
        return result;
    }

    // ---------------------------------------------------------------- pack reading

    private void readEntry(UnmodifiableConfig f, URI indexUri, String indexDir, String defaultFmt,
                           Map<String, Target> targets, Set<String> packProjects) throws Exception {
        String rel = indexDir + (String) f.get("file");
        String fmt = f.getOrElse("hash-format", defaultFmt);
        String hash = f.get("hash");
        boolean metafile = f.getOrElse("metafile", false);
        boolean preserve = f.getOrElse("preserve", false);
        URI fileUri = indexUri.resolve(encodePath(f.get("file")));

        if (!metafile) {
            targets.put(rel, new Target(rel, fileUri, fmt, hash, preserve));
            return;
        }

        byte[] bytes = cachedMetafile(fileUri, fmt, hash);
        UnmodifiableConfig meta = parse(new String(bytes, StandardCharsets.UTF_8));
        String side = meta.getOrElse("side", "both");
        String modrinthId = meta.get("update.modrinth.mod-id");
        if (modrinthId != null) packProjects.add(modrinthId);
        if (side.equalsIgnoreCase("server")) return;

        String filename = meta.get("filename");
        String url = meta.get("download.url");
        if (filename == null || url == null) throw new IOException("metafile has no filename/url");
        String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/') + 1) : "";
        String target = dir + filename;
        targets.put(target, new Target(target, URI.create(url.replace(" ", "%20")),
                meta.getOrElse("download.hash-format", "sha1"), meta.get("download.hash"), preserve));
    }

    private byte[] cachedMetafile(URI uri, String fmt, String hash) throws Exception {
        Path cached = cacheDir.resolve(fmt + "-" + hash + ".toml");
        if (Files.exists(cached)) {
            byte[] b = Files.readAllBytes(cached);
            if (checkHash(b, fmt, hash)) return b;
        }
        byte[] b = getBytes(uri, 30);
        if (!checkHash(b, fmt, hash)) throw new IOException("hash mismatch");
        Files.write(cached, b);
        return b;
    }

    private static UnmodifiableConfig parse(String toml) {
        return new TomlParser().parse(toml);
    }

    // ---------------------------------------------------------------- installing

    private void install(Target t, List<String> pending) throws Exception {
        Path local = resolveSafe(t.rel());
        Path tmp = tmpDir.resolve(UUID.randomUUID() + ".part");
        download(t.url(), tmp, t.algo(), t.hash());
        Files.createDirectories(local.getParent());
        boolean existed = Files.exists(local);
        try {
            Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException locked) {
            // File is in use (Windows). Finish it after Minecraft closes.
            Path staged = dataDir.resolve("staged").resolve(UUID.randomUUID() + ".jar");
            Files.createDirectories(staged.getParent());
            Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
            synchronized (pending) {
                pending.add("MOVE\t" + staged + "\t" + local);
            }
        }
        synchronized (state) {
            state.managed.add(t.rel());
            state.hashes.remove(t.rel());
        }
        synchronized (result) {
            if (existed) result.updated++;
            else result.added++;
        }
        FriendSmpUpdater.LOG.info("[FriendSMP Updater] {} {}", existed ? "Updated" : "Added", t.rel());
    }

    private void removeOld(Map<String, Target> targets, Set<String> packProjects, List<String> pending) {
        // 1) Files we installed earlier that are no longer in the pack.
        for (String rel : new ArrayList<>(state.managed)) {
            if (targets.containsKey(rel)) continue;
            state.managed.remove(rel);
            state.hashes.remove(rel);
            try {
                remove(resolveSafe(rel), pending);
            } catch (Exception e) {
                errors.add(rel + ": " + e.getMessage());
            }
        }

        // 2) Jars in mods/ we never installed: old copies of pack mods (other version) or old updater jars.
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) return;
        boolean packHasUpdater = targets.keySet().stream().anyMatch(r -> r.startsWith("mods/" + UPDATER_PREFIX));
        Map<String, Path> unknown = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : ds) {
                String rel = "mods/" + p.getFileName();
                if (targets.containsKey(rel) || state.managed.contains(rel)) continue;
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(UPDATER_PREFIX)) {
                    if (packHasUpdater) remove(p, pending);
                    continue;
                }
                try {
                    unknown.put(hashFile(rel, p, "sha1"), p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            errors.add("mods folder: " + e.getMessage());
            return;
        }
        if (unknown.isEmpty() || packProjects.isEmpty()) return;

        // Ask Modrinth which projects these jars belong to.
        try {
            JsonObject body = new JsonObject();
            body.add("hashes", GSON.toJsonTree(new ArrayList<>(unknown.keySet())));
            body.addProperty("algorithm", "sha1");
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/version_files"))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonObject map = JsonParser.parseString(resp.body()).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : map.entrySet()) {
                String project = e.getValue().getAsJsonObject().get("project_id").getAsString();
                Path p = unknown.get(e.getKey());
                if (p != null && packProjects.contains(project)) {
                    FriendSmpUpdater.LOG.info("[FriendSMP Updater] {} is another version of a pack mod", p.getFileName());
                    remove(p, pending);
                }
            }
        } catch (Exception e) {
            FriendSmpUpdater.LOG.warn("[FriendSMP Updater] Modrinth lookup failed", e);
        }
    }

    private void remove(Path p, List<String> pending) {
        if (!Files.exists(p)) return;
        try {
            Files.delete(p);
        } catch (IOException locked) {
            synchronized (pending) {
                pending.add("DELETE\t" + p.toAbsolutePath());
            }
        }
        synchronized (result) {
            result.removed++;
        }
        FriendSmpUpdater.LOG.info("[FriendSMP Updater] Removed {}", p.getFileName());
    }

    /** Runs PendingOps in a separate Java process once Minecraft has closed. */
    private void scheduleHelper() {
        if (ownJar == null || !Files.isRegularFile(ownJar)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // Run from a copy, because our own jar may be one of the files to delete.
                Path copy = Files.createTempFile("friendsmp-updater-helper", ".jar");
                Files.copy(ownJar, copy, StandardCopyOption.REPLACE_EXISTING);
                boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
                ProcessBuilder pb = new ProcessBuilder(java.toString(), "-cp", copy.toString(),
                        PendingOps.class.getName(), Long.toString(ProcessHandle.current().pid()), pendingFile.toString());
                pb.redirectErrorStream(true);
                pb.redirectOutput(dataDir.resolve("helper.log").toFile());
                pb.start();
            } catch (Throwable ignored) {
            }
        }, "FriendSMP updater helper"));
    }

    // ---------------------------------------------------------------- hashing / http

    private boolean matches(String rel, Path local, String algo, String hash) throws IOException {
        return hash != null && hash.equalsIgnoreCase(hashFile(rel, local, algo));
    }

    private String hashFile(String rel, Path p, String algo) throws IOException {
        long size = Files.size(p);
        long mtime = Files.getLastModifiedTime(p).toMillis();
        synchronized (state) {
            Rec r = state.hashes.get(rel);
            if (r != null && r.size == size && r.mtime == mtime && algo.equalsIgnoreCase(r.algo)) return r.hash;
        }
        MessageDigest md = digest(algo);
        if (md == null) throw new IOException("unsupported hash " + algo);
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        Rec r = new Rec();
        r.algo = algo;
        r.hash = HexFormat.of().formatHex(md.digest());
        r.size = size;
        r.mtime = mtime;
        synchronized (state) {
            state.hashes.put(rel, r);
        }
        return r.hash;
    }

    private static MessageDigest digest(String algo) {
        String name = switch (algo.toLowerCase(Locale.ROOT)) {
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            case "md5" -> "MD5";
            default -> null;
        };
        try {
            return name == null ? null : MessageDigest.getInstance(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean checkHash(byte[] data, String algo, String hash) {
        MessageDigest md = digest(algo);
        if (md == null) return true; // e.g. murmur2 (CurseForge): cannot check, accept
        return hash != null && hash.equalsIgnoreCase(HexFormat.of().formatHex(md.digest(data)));
    }

    private byte[] getBytes(URI uri, int timeoutSec) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT)
                        .header("Cache-Control", "no-cache")
                        .timeout(Duration.ofSeconds(timeoutSec)).GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) return resp.body();
                last = new IOException("HTTP " + resp.statusCode() + " for " + uri);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1000L * (attempt + 1));
        }
        throw last;
    }

    private void download(URI uri, Path out, String algo, String hash) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofMinutes(10)).GET().build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    resp.body().close();
                    throw new IOException("HTTP " + resp.statusCode() + " for " + uri);
                }
                MessageDigest md = digest(algo);
                try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        if (md != null) md.update(buf, 0, n);
                    }
                }
                if (md != null && (hash == null || !hash.equalsIgnoreCase(HexFormat.of().formatHex(md.digest())))) {
                    throw new IOException("hash mismatch for " + uri);
                }
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(out);
            }
            Thread.sleep(1000L * (attempt + 1));
        }
        throw last;
    }

    // ---------------------------------------------------------------- helpers

    private Path resolveSafe(String rel) throws IOException {
        Path p = gameDir.resolve(rel).normalize();
        if (!p.startsWith(gameDir) || p.equals(gameDir)) throw new IOException("unsafe path " + rel);
        return p;
    }

    private static String encodePath(String path) throws URISyntaxException {
        return new URI(null, null, path, null).toASCIIString();
    }

    private static void waitAll(List<Future<?>> jobs) throws Exception {
        for (Future<?> f : jobs) f.get();
    }

    private Properties loadConfig() throws IOException {
        Path file = dataDir.resolve("config.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        } else {
            p.setProperty("enabled", "true");
            p.setProperty("pack-url", DEFAULT_PACK_URL);
            Files.createDirectories(dataDir);
            try (OutputStream os = Files.newOutputStream(file)) {
                p.store(os, "FriendSMP Updater");
            }
        }
        return p;
    }

    private State loadState() {
        try {
            Path f = dataDir.resolve("state.json");
            if (Files.exists(f)) {
                State s = GSON.fromJson(Files.readString(f), State.class);
                if (s != null) {
                    if (s.managed == null) s.managed = new HashSet<>();
                    if (s.hashes == null) s.hashes = new HashMap<>();
                    return s;
                }
            }
        } catch (Exception e) {
            FriendSmpUpdater.LOG.warn("[FriendSMP Updater] Could not read state.json", e);
        }
        return new State();
    }

    private void saveState() {
        if (state == null) return;
        try {
            synchronized (state) {
                Files.writeString(dataDir.resolve("state.json"), GSON.toJson(state));
            }
        } catch (IOException e) {
            FriendSmpUpdater.LOG.warn("[FriendSMP Updater] Could not save state.json", e);
        }
    }

    private void cleanTmp() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmpDir)) {
            for (Path p : ds) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    /** Progress bar on the NeoForge loading screen; silently does nothing if unavailable. */
    static final class Progress {
        private final ProgressMeter meter;
        private final AtomicInteger count = new AtomicInteger();

        private Progress(ProgressMeter meter) {
            this.meter = meter;
        }

        static Progress start(String label, int max) {
            ProgressMeter m = null;
            try {
                m = StartupNotificationManager.addProgressBar(label, Math.max(1, max));
            } catch (Throwable ignored) {
            }
            return new Progress(m);
        }

        void step() {
            count.incrementAndGet();
            try {
                if (meter != null) meter.increment();
            } catch (Throwable ignored) {
            }
        }

        void done() {
            try {
                if (meter != null) meter.complete();
            } catch (Throwable ignored) {
            }
        }
    }
}
