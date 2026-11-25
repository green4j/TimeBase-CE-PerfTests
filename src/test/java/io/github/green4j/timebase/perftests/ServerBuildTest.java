package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBuildTest {

    private static void git(Path repo, String... args) throws Exception {
        final String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        ServerBuild.exec(repo, command);
    }

    private static Path repository(Path dir) throws Exception {
        git(dir, "init", "-q");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        Files.writeString(dir.resolve("kept.txt"), "kept");
        Files.writeString(dir.resolve("staged.txt"), "staged");
        Files.writeString(dir.resolve("unstaged.txt"), "unstaged");
        Files.writeString(dir.resolve("deleted.txt"), "deleted");
        Files.writeString(dir.resolve(".gitignore"), "ignored.txt\n");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "Initial");
        return dir;
    }

    @Test
    void cleanTreeHasNoChanges(@TempDir Path dir) throws Exception {
        assertTrue(ServerBuild.Changes.of(repository(dir)).isEmpty(), "A clean tree reported changes");
    }

    @Test
    void takesStagedUnstagedUntrackedAndDeletedFiles(@TempDir Path dir) throws Exception {
        final Path repo = repository(dir);
        Files.writeString(repo.resolve("staged.txt"), "staged change");
        git(repo, "add", "staged.txt");
        Files.writeString(repo.resolve("unstaged.txt"), "unstaged change");
        Files.writeString(repo.resolve("new.txt"), "new");
        Files.writeString(repo.resolve("ignored.txt"), "ignored");
        git(repo, "rm", "-q", "deleted.txt");

        final ServerBuild.Changes changes = ServerBuild.Changes.of(repo);

        assertEquals(List.of("new.txt", "staged.txt", "unstaged.txt"), changes.present, "Wrong changed files");
        assertEquals(List.of("deleted.txt"), changes.deleted, "Wrong deleted files");
        assertFalse(changes.hash.isEmpty());
    }

    @Test
    void pruneKeepsOnlyTheBuildsOfCampaigns(@TempDir Path root) throws Exception {
        final Path cache = root.resolve(".builds");
        for (String key : List.of("current", "earlier", "given-as-dist", "stale", "unfinished"))
            Files.createDirectories(cache.resolve(key).resolve("src"));
        Files.createDirectories(root.resolve("old-campaign"));
        Files.writeString(root.resolve("old-campaign").resolve("campaign.json"), "{\"servers\": [{\"id\": \"a\", \"git\": {}, \"dist\": \""
                + cache.resolve("earlier").resolve("src").resolve("dist") + "\"}]}");
        final JsonNode campaign = Output.JSON.readTree("{\"servers\": [{\"id\": \"b\", \"git\": {}, \"dist\": \""
                + cache.resolve("current").resolve("src").resolve("dist") + "\"}, {\"id\": \"c\", \"dist\": \"/elsewhere\"}, {\"id\": \"d\", \"dist\": \""
                + cache.resolve("given-as-dist").resolve("src") + "\"}]}");

        ServerBuild.prune(cache, root, campaign);

        try (Stream<Path> left = Files.list(cache)) {
            assertEquals(List.of("current", "earlier", "given-as-dist"), left.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList()),
                    "Wrong builds were kept");
        }
    }

    @Test
    void hashFollowsTheContent(@TempDir Path dir) throws Exception {
        final Path repo = repository(dir);
        Files.writeString(repo.resolve("unstaged.txt"), "one");
        final String first = ServerBuild.Changes.of(repo).hash;
        Files.writeString(repo.resolve("unstaged.txt"), "two");

        assertNotEquals(first, ServerBuild.Changes.of(repo).hash, "A content change kept the hash");
    }

    @Test
    void pruneKeepsEveryBuildWhenACampaignCannotBeRead(@TempDir Path root) throws Exception {
        final Path cache = root.resolve(".builds");
        Files.createDirectories(cache.resolve("unknown"));
        Files.createDirectories(root.resolve("broken"));
        Files.writeString(root.resolve("broken").resolve("campaign.json"), "{\"servers\": [");

        ServerBuild.prune(cache, root, Output.JSON.readTree("{\"servers\": []}"));

        assertTrue(Files.isDirectory(cache.resolve("unknown")), "A build was removed although a campaign could not be read");
    }

    /** Holds the lock of the cache given as the argument until its standard input closes. */
    public static void main(String[] args) throws Exception {
        try (FileChannel lock = ServerBuild.lock(Path.of(args[0]))) {
            System.out.println("Locked");
            System.out.flush();
            System.in.readAllBytes();
        }
    }

    @Test
    void lockWaitsForAnotherRunnerAndIsReleased(@TempDir Path cache) throws Exception {
        final Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ServerBuildTest.class.getName(), cache.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            final BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("Locked", out.readLine(), "The other runner did not take the lock");

            final CompletableFuture<FileChannel> waiting = CompletableFuture.supplyAsync(() -> {
                try {
                    return ServerBuild.lock(cache);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(500);
            assertFalse(waiting.isDone(), "The lock was taken while another runner held it");

            holder.getOutputStream().close();
            try (FileChannel lock = waiting.get(30, TimeUnit.SECONDS)) {
                assertNotNull(lock, "The lock was not taken after the other runner released it");
            }
        } finally {
            holder.destroyForcibly();
        }
    }
}
