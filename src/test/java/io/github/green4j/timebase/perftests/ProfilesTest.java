package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilesTest {

    private static ObjectNode generate(String... args) throws Exception {
        return Profiles.generate(Args.parse(args));
    }

    @Test
    void latencyFastOneToSix() throws Exception {
        final ObjectNode c = generate("--profile=latency", "--depth=fast", "--topology=one2six",
                "--baseline=dist:/tb", "--candidate=dist:/tb+overlay:/p.jar");

        assertEquals("latency-fast-one2six-orig-vs-patched", c.path("campaign").asText());
        assertEquals("orig", c.path("baseline").asText());
        final JsonNode suite = c.path("suites").get(0);
        assertEquals("latency", suite.path("type").asText());
        assertEquals(3, suite.path("repetitions").asInt());
        assertEquals(6, suite.path("params").path("readers").asInt(), "one2six did not set six readers");
        assertEquals(200, suite.path("params").path("payloadBytes").asInt(), "Wrong default payload");
        assertEquals("[\"LOW_LATENCY\",\"MIN_CPU_USAGE\"]", suite.path("matrix").path("mode").toString());
        assertEquals("[1000,50000,200000]", suite.path("matrix").path("rate").toString(), "Wrong default loads");
        assertEquals("rate", suite.path("matrix").fieldNames().next(), "The load is not the first axis");
        assertEquals("[1]", suite.path("matrix").path("burst").toString());
        assertEquals("[1,1000]", suite.path("matrix").path("symbols").toString(), "Wrong default symbols");
        assertEquals(72, Plan.expand(c).size(), "Wrong number of runs");
    }

    @Test
    void depthAndTopologyHaveDefaults() throws Exception {
        final ObjectNode c = generate("--profile=throughput", "--depth=medium", "--topology=one2one", "--baseline=dist:/tb");

        assertEquals(1, c.path("suites").get(0).path("params").path("readers").asInt(), "one2one did not set one reader");
        assertEquals(3, generate("--profile=latency", "--topology=one2three", "--baseline=dist:/tb")
                .path("suites").get(0).path("params").path("readers").asInt(), "one2three did not set three readers");
        final ObjectNode defaults = generate("--profile=throughput", "--baseline=dist:/tb");
        assertEquals("fast", defaults.path("profile").path("depth").asText(), "The default depth is not fast");
        assertEquals("one2three", defaults.path("profile").path("topology").asText(), "The default topology is not one2three");
    }

    @Test
    void setReplacesAnAxisOrAParameter() throws Exception {
        final ObjectNode c = generate("--profile=latency", "--baseline=dist:/tb",
                "--set=burst=1,16", "--set=rate=50000", "--set=readers=1,6", "--set=repetitions=5");
        final JsonNode suite = c.path("suites").get(0);

        assertEquals("[1,16]", suite.path("matrix").path("burst").toString());
        assertFalse(suite.path("matrix").has("rate"), "A single value stayed an axis");
        assertEquals(50000, suite.path("params").path("rate").asInt());
        assertEquals("[1,6]", suite.path("matrix").path("readers").toString(), "Several values did not become an axis");
        assertFalse(suite.path("params").has("readers"));
        assertEquals(5, suite.path("repetitions").asInt());
        assertEquals(4, c.path("profile").path("overrides").size(), "Overrides were not recorded");
    }

    @Test
    void scopedSetTouchesOneSuite() throws Exception {
        final ObjectNode c = generate("--profile=sanity", "--baseline=dist:/tb", "--set=latency.burst=64");

        assertFalse(c.path("suites").get(0).path("params").has("burst"), "The correctness suite was changed");
        assertEquals(64, c.path("suites").get(1).path("params").path("burst").asInt());
    }

    @Test
    void loadsChooseTheRates() throws Exception {
        final ObjectNode c = generate("--profile=latency", "--depth=medium", "--load=heavy,light", "--baseline=dist:/tb");

        assertEquals("[1000,200000]", c.path("suites").get(0).path("matrix").path("rate").toString(), "Loads are not sorted by rate");
        assertEquals("latency-medium-light-heavy-one2three-orig", c.path("campaign").asText());
        assertEquals("[500000]", generate("--profile=latency", "--depth=full", "--load=saturation", "--baseline=dist:/tb")
                .path("suites").get(0).path("matrix").path("rate").toString());
        assertEquals("[1000,10000]", generate("--profile=latency", "--baseline=dist:/tb", "--set=rate=1000,10000")
                .path("suites").get(0).path("matrix").path("rate").toString(), "--set did not replace the loads");
    }

    @Test
    void sanityRunsEverySuiteType() throws Exception {
        final ObjectNode c = generate("--profile=sanity", "--baseline=dist:/tb", "--candidate=dist:/tb2");

        assertEquals("sanity-one2three-orig-vs-patched", c.path("campaign").asText());
        final List<String> types = List.of(c.path("suites").get(0).path("type").asText(),
                c.path("suites").get(1).path("type").asText(), c.path("suites").get(2).path("type").asText());
        assertEquals(List.of("correctness", "latency", "throughput"), types);
        assertFalse(c.path("suites").get(0).path("params").has("payloadBytes"), "A key correctness does not take leaked into it");
        assertEquals(12, Plan.expand(c).size());
    }

    @Test
    void correctnessTakesNoTopology() throws Exception {
        final ObjectNode c = generate("--profile=correctness", "--baseline=dist:/tb");

        assertEquals("correctness-fast-orig", c.path("campaign").asText(), "A profile without readers got a topology in its name");
        assertFalse(c.path("profile").has("topology"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=correctness", "--topology=one2one", "--baseline=dist:/tb"));
    }

    @Test
    void fileProfileWithoutDepthsIsOneDepth(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("mine.json");
        Files.writeString(file, "{\"type\": \"throughput\", \"topology\": \"one2one\", \"repetitions\": 2,"
                + " \"seed\": 7, \"matrix\": {\"symbols\": [1, 10]}, \"params\": {\"warmupSeconds\": 1, \"measureSeconds\": 2}}");

        final ObjectNode c = generate("--profile=" + file, "--baseline=dist:/tb");

        assertEquals("mine-one2one-orig", c.path("campaign").asText(), "The file name did not name the profile");
        assertEquals(7, c.path("seed").asInt());
        assertEquals(4, Plan.expand(c).size());
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=" + file, "--depth=fast", "--baseline=dist:/tb"));
    }

    @Test
    void laterLevelReplacesAKeyOfEitherKind(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("layers.json");
        Files.writeString(file, "{\"type\": \"throughput\", \"matrix\": {\"symbols\": [1, 10]}, \"params\": {\"payloadBytes\": 8},"
                + " \"depths\": {\"one\": {\"params\": {\"symbols\": 5}, \"suites\": [{\"matrix\": {\"payloadBytes\": [16, 32]}}]}}}");

        final JsonNode suite = generate("--profile=" + file, "--baseline=dist:/tb").path("suites").get(0);

        assertEquals(5, suite.path("params").path("symbols").asInt(), "A depth parameter did not replace a profile axis");
        assertFalse(suite.path("matrix").has("symbols"));
        assertEquals("[16,32]", suite.path("matrix").path("payloadBytes").toString(), "A suite axis did not replace a profile parameter");
        assertFalse(suite.path("params").has("payloadBytes"));

        Files.writeString(file, "{\"type\": \"throughput\", \"matrix\": {\"symbols\": [1, 10]}, \"params\": {\"symbols\": 5}}");
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=" + file, "--baseline=dist:/tb"),
                "A key given twice at one level was accepted");
        Files.writeString(file, "{\"type\": \"throughput\", \"params\": {\"symbls\": 5}}");
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=" + file, "--baseline=dist:/tb"),
                "An unknown key was accepted");
    }

    @Test
    void exampleProfileGenerates() throws Exception {
        final ObjectNode c = generate("--profile=profiles/example.json", "--baseline=dist:/tb", "--candidate=dist:/x");

        assertEquals("example-one2one-orig-vs-patched", c.path("campaign").asText());
        assertEquals(40, Plan.expand(c).size());
    }

    @Test
    void rejectsWhatIsNotUnderstood() {
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=nope", "--baseline=dist:/tb"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--depth=quick", "--baseline=dist:/tb"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--topology=one2many", "--baseline=dist:/tb"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--baseline=dist:/tb", "--set=bursts=1"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--baseline=dist:/tb", "--set=total=5"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=throughput", "--load=light", "--baseline=dist:/tb"),
                "A load was accepted by a profile without loads");
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--load=light,light", "--baseline=dist:/tb"));
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--baseline=dist:/tb",
                "--candidate=dist:/a", "--candidate=dist:/b"));
    }

    @Test
    void baselineAloneMeasuresOneServer() throws Exception {
        final ObjectNode c = generate("--profile=latency", "--baseline=mine=dist:/tb");

        assertEquals("latency-fast-one2three-mine", c.path("campaign").asText());
        assertEquals(1, c.path("servers").size());
        assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency"), "A profile without a server was accepted");
    }

    @Test
    void repetitionsMustBeOnePositiveInteger() {
        for (String bad : new String[] {"5,10", "0", "-1", "x"})
            assertThrows(IllegalArgumentException.class, () -> generate("--profile=latency", "--baseline=dist:/tb",
                    "--set=repetitions=" + bad), "repetitions=" + bad + " was accepted");
    }

    @Test
    void readersOverrideRenamesTheTopology() throws Exception {
        final ObjectNode c = generate("--profile=latency", "--baseline=dist:/tb", "--set=readers=1,16");

        assertEquals("latency-fast-readers1-16-orig", c.path("campaign").asText());
        assertEquals("readers1-16", c.path("profile").path("topology").asText());
    }

    @Test
    void parsesServerSpecs() {
        assertThrows(IllegalArgumentException.class, () -> Profiles.server("dist:+overlay:/p.jar", "x"), "An empty dist was accepted");
        assertThrows(IllegalArgumentException.class, () -> Profiles.server("dist:/tb+overlay:", "x"), "An empty overlay was accepted");
        assertEquals("{\"id\":\"orig\",\"dist\":\"/tb\"}", Profiles.server("dist:/tb", "orig").toString());
        assertEquals("{\"id\":\"f2\",\"dist\":\"/tb\",\"overlay\":\"/p.jar\"}", Profiles.server("f2=dist:/tb+overlay:/p.jar", null).toString());
        assertEquals("{\"id\":\"b\",\"git\":{\"repo\":\"~/TimeBase-CE\",\"ref\":\"live-reader-notify-6.2\"}}",
                Profiles.server("b=git:~/TimeBase-CE@live-reader-notify-6.2", null).toString());
        assertEquals("{\"id\":\"i\",\"image\":\"finos/timebase-ce-server:6.2.34\"}", Profiles.server("i=image:finos/timebase-ce-server:6.2.34", null).toString());
        assertEquals("{\"id\":\"u\",\"url\":\"dxtick://host:8011\"}", Profiles.server("u=url:dxtick://host:8011", null).toString());
        assertThrows(IllegalArgumentException.class, () -> Profiles.server("git:/repo", "x"), "A git spec without a ref was accepted");
        assertThrows(IllegalArgumentException.class, () -> Profiles.server("ftp:/x", "x"));
    }

    @Test
    void bundledProfilesGenerateValidPlans() throws Exception {
        for (String name : Profiles.NAMES) {
            final JsonNode p = Profiles.load(name);
            p.path("depths").fieldNames().forEachRemaining(depth -> {
                for (String topology : "correctness".equals(name) ? List.of("") : List.copyOf(Profiles.TOPOLOGIES.keySet())) {
                    try {
                        final ObjectNode c = topology.isEmpty()
                                ? generate("--profile=" + name, "--depth=" + depth, "--baseline=dist:/tb", "--candidate=dist:/x")
                                : generate("--profile=" + name, "--depth=" + depth, "--topology=" + topology, "--baseline=dist:/tb", "--candidate=dist:/x");
                        assertTrue(Plan.expand(c).size() > 0, "Empty plan for " + name + " " + depth);
                    } catch (Exception e) {
                        throw new AssertionError("Profile " + name + " " + depth + " " + topology + " did not generate", e);
                    }
                }
            });
        }
    }

    @Test
    void misspelledKeysAndOptionsAreRejected(@TempDir Path dir) throws Exception {
        for (String json : new String[] {
                "{\"type\": \"throughput\", \"repetiton\": 2}",
                "{\"type\": \"throughput\", \"server\": {}}",
                "{\"type\": \"throughput\", \"depths\": {\"fast\": {\"suite\": [{}]}}}",
                "{\"type\": \"throughput\", \"suites\": [{\"parms\": {}}]}",
                "{\"type\": \"throughput\", \"depths\": {\"fast\": {\"suites\": [{}]}, \"full\": {\"suites\": [{\"params\": {\"rat\": 1}}]}}}",
                "{\"type\": \"throughput\", \"depths\": {\"fast\": {\"suites\": [{}]}, \"full\": {\"suites\": [{\"type\": \"latenc\"}]}}}",
                "{\"type\": \"throughput\", \"repetitions\": 2, \"depths\": {\"fast\": {\"suites\": [{}]}}}"}) {
            final Path file = dir.resolve("p.json");
            Files.writeString(file, json);
            assertThrows(IllegalArgumentException.class, () -> generate("--profile=" + file, "--baseline=dist:/tb"), "Accepted: " + json);
        }
        assertThrows(IllegalArgumentException.class, () -> Args.parse(new String[] {"--deph=full"}).only("run", Main.RUN_OPTIONS),
                "A misspelled option was accepted");
        Args.parse(new String[] {"--profile=latency", "--set=a=1", "--set=b=2", "--dryRun"}).only("run", Main.RUN_OPTIONS);
    }
}
