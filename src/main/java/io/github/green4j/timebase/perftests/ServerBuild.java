package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the distribution of a server given as a git repository and ref:
 * {@code "git": {"repo": "...", "ref": "branch|tag|commit|WORKTREE"}}. The ref is checked out into a private clone,
 * so the repository itself is not touched; {@code WORKTREE} also takes its uncommitted changes and untracked files.
 * Builds are cached by commit, plus a hash of the changes for {@code WORKTREE}.
 */
final class ServerBuild {

    static final String WORKTREE = "WORKTREE";
    private static final String DEFAULT_TASK = ":java:timebase:server:copyTimebaseServerContainerDependensies";
    private static final String DEFAULT_OUTPUT = "java/timebase/server/build/docker/timebase";
    private static final String BUILT_MARKER = "BUILT";

    private ServerBuild() {
    }

    /** Sets {@code dist} of every git server to its built distribution and records what was built. */
    static void resolve(JsonNode campaign, Path cache, Map<String, String> env) throws Exception {
        for (JsonNode v : campaign.path("servers")) {
            if (v.has("git"))
                resolve((ObjectNode) v, cache, env);
        }
    }

    private static void resolve(ObjectNode server, Path cache, Map<String, String> env) throws Exception {
        final JsonNode git = server.get("git");
        final Path repo = Path.of(required(git, "repo").replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
        final String ref = required(git, "ref");
        final boolean worktree = WORKTREE.equals(ref);
        final String commit = exec(repo, "git", "rev-parse", "--verify", (worktree ? "HEAD" : ref) + "^{commit}");
        final Changes changes = worktree ? Changes.of(repo) : Changes.NONE;
        final String task = git.path("task").asText(DEFAULT_TASK);
        final String output = git.path("output").asText(DEFAULT_OUTPUT);
        final List<String> gradleArgs = new ArrayList<>();
        git.path("gradleArgs").forEach(a -> gradleArgs.add(a.asText()));
        final String javaHome = git.path("javaHome").asText(env.getOrDefault("TB_BUILD_JAVA_HOME", ""));
        if (javaHome.isEmpty())
            throw new IllegalArgumentException("Set git.javaHome of server " + server.path("id").asText()
                    + " or TB_BUILD_JAVA_HOME to a JDK that can run the repository's Gradle");

        final MessageDigest config = MessageDigest.getInstance("SHA-256");
        for (String part : List.of(repo.toString(), task, output, String.join("\u0001", gradleArgs), javaHome, changes.hash))
            config.update((part + "\0").getBytes(StandardCharsets.UTF_8));
        final String key = commit + "-" + Runner.hex(config.digest()).substring(0, 16);

        final Path dir = cache.toAbsolutePath().resolve(key);
        final Path src = dir.resolve("src");
        final Path dist = src.resolve(output);
        final Path marker = dir.resolve(BUILT_MARKER);
        if (!Files.exists(marker) || !Files.readString(marker).trim().equals(key)) {
            Runner.deleteRecursively(dir);
            Files.createDirectories(dir);
            System.out.printf("Building %s %s (%s) in %s%n", repo, ref, key, src);
            exec(dir, "git", "clone", "-q", "--no-checkout", repo.toString(), src.toString());
            exec(src, "git", "checkout", "-q", "--detach", commit);
            changes.applyTo(repo, src);

            final List<String> gradle = new ArrayList<>(List.of(src.resolve("gradlew").toString(), "-q", "--no-daemon", task));
            gradle.addAll(gradleArgs);
            final ProcessBuilder builder = new ProcessBuilder(gradle)
                    .directory(src.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("build.log").toFile());
            builder.environment().put("JAVA_HOME", javaHome);
            final int exit = builder.start().waitFor();
            if (exit != 0)
                throw new IllegalStateException("Build of " + ref + " failed with code " + exit + ", see " + dir.resolve("build.log"));
            ServerProcess.serverJar(dist.resolve("lib"));
            Files.writeString(marker, key + "\n");
        }
        ServerProcess.serverJar(dist.resolve("lib"));

        server.put("dist", dist.toString());
        final ObjectNode built = server.putObject("built");
        built.put("repo", repo.toString());
        built.put("ref", ref);
        built.put("commit", commit);
        built.put("uncommittedChanges", !changes.isEmpty());
        if (!changes.isEmpty())
            built.put("changesSha256", changes.hash);
    }

    /**
     * Deletes the builds that neither this campaign nor any campaign under {@code root} uses, unfinished ones
     * included, so that the cache does not grow with every change of a {@code WORKTREE}. Call it under
     * {@link #lock}, which a campaign holds until its {@code campaign.json} names its builds.
     */
    static void prune(Path cache, Path root, JsonNode campaign) throws IOException {
        final Path dir = cache.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir))
            return;
        final Set<String> used = new HashSet<>();
        addUsed(campaign, dir, used);
        try (Stream<Path> campaigns = Files.list(root)) {
            for (Path c : (Iterable<Path>) campaigns::iterator) {
                final Path file = c.resolve("campaign.json");
                if (!Files.isRegularFile(file))
                    continue;
                try {
                    addUsed(Output.JSON.readTree(file.toFile()), dir, used);
                } catch (IOException e) {
                    System.out.printf("Kept every build: cannot read %s: %s%n", file, e.getMessage());
                    return;
                }
            }
        }
        try (Stream<Path> builds = Files.list(dir)) {
            for (Path b : (Iterable<Path>) builds::iterator) {
                if (Files.isDirectory(b) && !used.contains(b.getFileName().toString())) {
                    System.out.printf("Removing the unused build %s%n", b);
                    Runner.deleteRecursively(b);
                }
            }
        }
    }

    /** Excludes other runners from building into and pruning the cache; waits for one that holds it. */
    static FileChannel lock(Path cache) throws IOException {
        Files.createDirectories(cache);
        final FileChannel channel = FileChannel.open(cache.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() == null) {
                System.out.printf("Waiting for another runner to release %s%n", cache);
                channel.lock();
            }
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void addUsed(JsonNode campaign, Path cache, Set<String> used) {
        for (JsonNode v : campaign.path("servers")) {
            if (!v.hasNonNull("dist"))
                continue;
            final Path dist = Path.of(v.get("dist").asText()).toAbsolutePath().normalize();
            if (dist.startsWith(cache) && dist.getNameCount() > cache.getNameCount())
                used.add(dist.getName(cache.getNameCount()).toString());
        }
    }

    /** Uncommitted state of a working tree: modified and untracked files to copy, deleted files to remove. */
    static final class Changes {
        static final Changes NONE = new Changes(List.of(), List.of(), "");

        final List<String> present;
        final List<String> deleted;
        final String hash;

        private Changes(List<String> present, List<String> deleted, String hash) {
            this.present = present;
            this.deleted = deleted;
            this.hash = hash;
        }

        /** Staged and unstaged changes against {@code HEAD}, plus untracked files that are not ignored. */
        static Changes of(Path repo) throws Exception {
            final List<String> present = new ArrayList<>();
            final List<String> deleted = new ArrayList<>();
            final String[] diff = execRaw(repo, "git", "diff", "--name-status", "--no-renames", "-z", "HEAD").split("\0");
            for (int i = 0; i + 1 < diff.length; i += 2) {
                if (diff[i].startsWith("D"))
                    deleted.add(diff[i + 1]);
                else
                    present.add(diff[i + 1]);
            }
            for (String path : execRaw(repo, "git", "ls-files", "-o", "--exclude-standard", "-z").split("\0")) {
                if (!path.isEmpty())
                    present.add(path);
            }
            Collections.sort(present);
            Collections.sort(deleted);
            if (present.isEmpty() && deleted.isEmpty())
                return NONE;
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : present) {
                digest.update(("+" + path + "\n").getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(repo.resolve(path)));
            }
            for (String path : deleted)
                digest.update(("-" + path + "\n").getBytes(StandardCharsets.UTF_8));
            return new Changes(present, deleted, Runner.hex(digest.digest()));
        }

        boolean isEmpty() {
            return present.isEmpty() && deleted.isEmpty();
        }

        void applyTo(Path repo, Path src) throws IOException {
            for (String path : present) {
                final Path target = src.resolve(path);
                Files.createDirectories(target.getParent());
                Files.copy(repo.resolve(path), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            for (String path : deleted)
                Files.deleteIfExists(src.resolve(path));
        }
    }

    static String exec(Path dir, String... command) throws IOException, InterruptedException {
        return execRaw(dir, command).trim();
    }

    private static String execRaw(Path dir, String... command) throws IOException, InterruptedException {
        final Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        final String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (p.waitFor() != 0)
            throw new IllegalStateException(String.join(" ", command) + " failed in " + dir + ": " + out.trim());
        return out;
    }

    private static String required(JsonNode node, String field) {
        if (!node.hasNonNull(field))
            throw new IllegalArgumentException("git." + field + " is required");
        return node.get(field).asText();
    }
}
