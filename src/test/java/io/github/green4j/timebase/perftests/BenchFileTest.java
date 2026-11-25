package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchFileTest {

    private static Path write(Path dir, String json) throws Exception {
        final Path file = dir.resolve("bench.json");
        Files.writeString(file, json);
        return file;
    }

    private static void rejects(Path dir, String json, String why) throws Exception {
        final Path file = write(dir, json);
        assertThrows(IllegalArgumentException.class, () -> BenchFile.load(file), why);
    }

    private static ObjectNode generate(String... args) throws Exception {
        return Profiles.generate(Args.parse(args));
    }

    @Test
    void loadsEveryKindWithPathsRelativeToTheFile(@TempDir Path dir) throws Exception {
        final JsonNode v = BenchFile.load(write(dir, "{\"baseline\": {\"id\": \"base\", \"dist\": \"dist\", \"overlay\": \"p.jar\"},"
                + " \"candidates\": [{\"id\": \"mine\", \"git\": {\"repo\": \"../tb\", \"ref\": \"WORKTREE\", \"javaHome\": \"/jdk11\","
                + " \"gradleArgs\": [\"-q\"]}},"
                + "{\"id\": \"img\", \"image\": \"tb:1\", \"containerPort\": 8011,"
                + " \"configurations\": [{\"dockerArgs\": [\"--cpuset-cpus=4-7\", \"-e\", \"X=1\"]}]},"
                + "{\"id\": \"remote\", \"url\": \"dxtick://h:8011\"}]}")).servers;

        assertEquals("base", v.get(0).path("id").asText(), "The baseline is not first");
        assertEquals(dir.resolve("dist").toString(), v.get(0).path("dist").asText());
        assertEquals(dir.resolve("p.jar").toString(), v.get(0).path("overlay").asText());
        assertEquals(dir.getParent().resolve("tb").toString(), v.get(1).path("git").path("repo").asText(),
                "A relative repo was not resolved against the file");
        assertEquals("/jdk11", v.get(1).path("git").path("javaHome").asText());
        assertEquals(4, v.size());
        assertEquals("[\"-Xmx4g\",\"-Xms4g\"]", v.get(0).path("configurations").path("default").path("jvmArgs").toString(),
                "A server without configurations did not get the default one");
    }

    @Test
    void idsDefaultAsOnTheCommandLine(@TempDir Path dir) throws Exception {
        final JsonNode v = BenchFile.load(write(dir, "{\"baseline\": {\"url\": \"dxtick://h:1\"},"
                + " \"candidates\": [{\"url\": \"dxtick://h:2\"}]}")).servers;
        assertEquals("orig", v.get(0).path("id").asText());
        assertEquals("patched", v.get(1).path("id").asText());
        assertEquals(1, BenchFile.load(write(dir, "{\"baseline\": {\"id\": \"only\", \"url\": \"dxtick://h:1\"}}")).servers.size(),
                "A baseline alone is not one server");
    }

    @Test
    void configurationsReplaceTheDefaults(@TempDir Path dir) throws Exception {
        final BenchFile bench = BenchFile.load(write(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": ["
                + "{\"id\": \"g1\", \"jvmArgs\": [\"-XX:+UseG1GC\"], \"prefix\": [\"taskset\", \"-c\", \"0-3\"]},"
                + "{\"id\": \"zgc\", \"jvmArgs\": [\"-Xmx8g\", \"-XX:+UseZGC\"]}]},"
                + " \"client\": {\"configurations\": [{\"jvmArgs\": [\"-Xmx1g\"]}]}}"));
        final JsonNode configurations = bench.servers.get(0).path("configurations");

        assertEquals("[\"-XX:+UseG1GC\"]", configurations.path("g1").path("jvmArgs").toString(), "jvmArgs did not replace the defaults");
        assertEquals(18011, configurations.path("g1").path("port").asInt(), "The default port was lost");
        assertFalse(configurations.path("g1").has("id"));
        assertEquals("[\"-Xmx1g\"]", bench.client.path("default").path("jvmArgs").toString());
    }

    @Test
    void candidatesPairWithTheBaselineByConfiguration(@TempDir Path dir) throws Exception {
        final Path file = write(dir, "{\"baseline\": {\"id\": \"orig\", \"dist\": \"a\", \"configurations\": [{\"id\": \"g1\"}, {\"id\": \"zgc\"}]},"
                + " \"candidates\": [{\"id\": \"f2\", \"dist\": \"b\", \"configurations\": [{\"id\": \"zgc\", \"jvmArgs\": [\"-XX:+UseZGC\"]},"
                + " {\"id\": \"g1\", \"jvmArgs\": [\"-XX:+UseG1GC\"]}]}],"
                + " \"client\": {\"configurations\": [{\"id\": \"small\"}, {\"id\": \"big\"}]}}");

        final ObjectNode c = generate("--profile=throughput", "--bench=" + file);
        final JsonNode suite = c.path("suites").get(0);
        assertEquals("[\"g1\",\"zgc\"]", suite.path("matrix").path("serverConfig").toString(), "Server configurations are not an axis");
        assertEquals("[\"small\",\"big\"]", suite.path("matrix").path("clientConfig").toString(), "Client configurations are not an axis in file order");
        final List<Plan.Run> runs = Plan.expand(c);
        assertEquals(3 * 2 * 3 * 2 * 2 * 2, runs.size());
        assertTrue(runs.stream().anyMatch(r -> r.id.contains("_serverConfig-zgc_clientConfig-small_r01_f2")), "The configurations are not in the run ids");
        final JsonNode f2 = c.path("servers").get(1).path("configurations");
        assertEquals("[\"-XX:+UseG1GC\"]", f2.path("g1").path("jvmArgs").toString(), "The candidate's g1 is not its own");
        assertEquals("[\"-XX:+UseZGC\"]", f2.path("zgc").path("jvmArgs").toString(), "The candidate's zgc is not its own");

        rejects(dir, "{\"baseline\": {\"dist\": \"a\", \"configurations\": [{\"id\": \"g1\"}, {\"id\": \"zgc\"}]},"
                + " \"candidates\": [{\"dist\": \"b\", \"configurations\": [{\"id\": \"g1\"}]}]}", "A configuration without a pair was accepted");
    }

    @Test
    void oneConfigurationKeepsTheRunIds() throws Exception {
        final ObjectNode c = generate("--profile=latency", "--baseline=dist:/tb");
        final JsonNode suite = c.path("suites").get(0);

        assertEquals("default", suite.path("params").path("serverConfig").asText());
        assertFalse(suite.path("matrix").has("serverConfig"));
        assertFalse(Plan.expand(c).get(0).id.contains("Config"), "A single configuration got into the run ids");
        assertEquals(19000, generate("--profile=latency", "--baseline=dist:/tb", "--port=19000")
                .path("servers").get(0).path("configurations").path("default").path("port").asInt(), "--port did not reach the configuration");
    }

    @Test
    void rejectsWhatIsWrong(@TempDir Path dir) throws Exception {
        rejects(dir, "{\"candidates\": [{\"url\": \"u\"}]}", "Candidates without a baseline were accepted");
        rejects(dir, "{\"baseline\": {\"url\": \"u\"}, \"candidates\": [{\"url\": \"v\"}, {\"url\": \"w\"}]}",
                "Several candidates without ids were accepted");
        rejects(dir, "{\"baseline\": {\"id\": \"a\", \"url\": \"u\"}, \"candidates\": [{\"id\": \"a\", \"url\": \"v\"}]}", "A duplicate id was accepted");
        rejects(dir, "{\"baseline\": {\"id\": \"a\", \"url\": \"u\"}, \"candidates\": {\"id\": \"b\", \"url\": \"v\"}}", "Candidates as an object were accepted");
        rejects(dir, "{\"baseline\": \"a\"}", "A baseline that is not a server was accepted");
        rejects(dir, "{\"baseline\": {\"id\": \"a b\", \"url\": \"u\"}}", "A bad id was accepted");
        rejects(dir, "{\"baseline\": {\"id\": null, \"url\": \"u\"}}", "A null id was accepted");
        rejects(dir, "{\"baseline\": {\"id\": 7, \"url\": \"u\"}}", "A numeric id was accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"url\": \"u\"}}", "Two kinds were accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"jvmArgs\": [\"-Xmx1g\"]}}", "jvmArgs outside a configuration were accepted");
        rejects(dir, "{\"baseline\": {\"url\": \"u\", \"configurations\": [{\"jvmArgs\": [\"-Xmx1g\"]}]}}", "jvmArgs of a url were accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"dockerArgs\": []}]}}", "dockerArgs of a dist were accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": []}}", "Empty configurations were accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"port\": 1}, {\"port\": 2}]}}", "Configurations without ids were accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"id\": \"a\"}, {\"id\": \"a\"}]}}", "Duplicate configurations were accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"heap\": 4}]}}", "An unknown configuration key was accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"port\": \"invalid\"}]}}", "A port that is not a number was accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"webPort\": 70000}]}}", "A webPort out of range was accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"startTimeoutSeconds\": 0}]}}", "A zero timeout was accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"java\": 11}]}}", "A java that is not a string was accepted");
        rejects(dir, "{\"baseline\": {\"git\": {\"repo\": \"r\"}}}", "A git without a ref was accepted");
        rejects(dir, "{\"baseline\": {\"git\": {\"repo\": \"r\", \"ref\": \"m\", \"tsk\": \"x\"}}}", "An unknown git key was accepted");
        rejects(dir, "{\"baseline\": {\"image\": \"i\", \"containerPort\": 70000}}", "A bad port was accepted");
        rejects(dir, "{\"baseline\": {\"image\": \"i\", \"configurations\": [{\"dockerArgs\": [\"-p\", \"1:2\"]}]}}", "A port option was accepted");
        rejects(dir, "{\"baseline\": {\"image\": \"i\", \"configurations\": [{\"dockerArgs\": [\"--name=x\"]}]}}", "A name option was accepted");
        rejects(dir, "{\"baseline\": {\"image\": \"i\", \"configurations\": [{\"dockerArgs\": [\"-e\", \"JAVA_OPTS=-Xmx1g\"]}]}}", "JAVA_OPTS was accepted");
        rejects(dir, "{\"baseline\": {\"dist\": \"d\", \"configurations\": [{\"jvmArgs\": \"-Xmx1g\"}]}}", "jvmArgs as a string were accepted");
        rejects(dir, "{\"client\": {\"configurations\": [{\"port\": 1}]}}", "A server key in a client configuration was accepted");
        rejects(dir, "{\"client\": {\"jvmArgs\": []}}", "Client settings outside a configuration were accepted");
        rejects(dir, "{\"allServers\": {}}", "The old allServers key was accepted");
    }

    @Test
    void clientWithoutServersLeavesThemToTheCommandLine(@TempDir Path dir) throws Exception {
        final Path file = write(dir, "{\"client\": {\"configurations\": [{\"prefix\": [\"taskset\", \"-c\", \"4-9\"]}]}}");
        assertNull(BenchFile.load(file).servers);

        final ObjectNode c = generate("--profile=latency", "--bench=" + file, "--baseline=dist:/tb");
        assertEquals("[\"taskset\",\"-c\",\"4-9\"]", c.path("clients").path("default").path("prefix").toString(), "The bench's client was lost");
        assertEquals("[\"-Xmx2g\",\"-Xms2g\"]", c.path("clients").path("default").path("jvmArgs").toString(), "The default client settings were lost");
    }

    @Test
    void serversOfTheBenchExcludeTheCommandLineOnes(@TempDir Path dir) throws Exception {
        final Path file = write(dir, "{\"baseline\": {\"id\": \"only\", \"url\": \"dxtick://h:1\"}}");

        final ObjectNode c = generate("--profile=latency", "--bench=" + file);
        assertEquals("latency-fast-one2three-only", c.path("campaign").asText());
        assertEquals(file.toAbsolutePath().normalize().toString(), c.path("benchFile").asText());
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--bench=" + file, "--baseline=dist:/tb"));
    }

    @Test
    void profileTakesNoServerOrClientSettings(@TempDir Path dir) throws Exception {
        final Path profile = dir.resolve("p.json");
        Files.writeString(profile, "{\"type\": \"throughput\", \"server\": {\"port\": 1}}");
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=" + profile, "--baseline=dist:/tb"));
    }

    @Test
    void exampleLoads() throws Exception {
        final BenchFile bench = BenchFile.load(Path.of("benches/example.json"));
        assertEquals(List.of("orig", "f2"), StreamSupport.stream(bench.servers.spliterator(), false)
                .map(s -> s.path("id").asText()).collect(Collectors.toList()));
    }

    @Test
    void distHashFollowsTheContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("timebase-server-1.jar"), "one");
        final String first = Runner.treeSha256(dir);
        Files.writeString(dir.resolve("timebase-server-1.jar"), "two");

        assertNotEquals(first, Runner.treeSha256(dir), "A replaced jar kept the hash");
    }
}
