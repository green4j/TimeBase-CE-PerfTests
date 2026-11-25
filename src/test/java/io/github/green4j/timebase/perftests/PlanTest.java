package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest {

    private static JsonNode campaign(long seed) throws Exception {
        return Output.JSON.readTree("{"
                + "\"seed\": " + seed + ","
                + "\"servers\": [{\"id\": \"orig\"}, {\"id\": \"F2\"}],"
                + "\"suites\": [{\"name\": \"lat\", \"type\": \"latency\", \"repetitions\": 3,"
                + "  \"matrix\": {\"rate\": [1000, 200000], \"burst\": [1, 64]},"
                + "  \"params\": {\"measureSeconds\": 20}}]}");
    }

    @Test
    void expandsEveryScenarioRepetitionAndServer() throws Exception {
        final List<Plan.Run> runs = Plan.expand(campaign(1));

        assertEquals(2 * 2 * 3 * 2, runs.size(), "Wrong number of runs");
        final Set<String> ids = new HashSet<>();
        runs.forEach(r -> ids.add(r.id));
        assertEquals(runs.size(), ids.size(), "Run ids are not unique");
        for (Plan.Run r : runs)
            assertEquals("20", r.params.get("measureSeconds"), "A fixed parameter was lost");
    }

    @Test
    void serversOfOneBlockRunBackToBack() throws Exception {
        final List<Plan.Run> runs = Plan.expand(campaign(7));

        for (int i = 0; i < runs.size(); i += 2) {
            final Plan.Run a = runs.get(i);
            final Plan.Run b = runs.get(i + 1);
            assertEquals(a.params, b.params, "A block mixed scenarios");
            assertEquals(a.repetition, b.repetition, "A block mixed repetitions");
            assertTrue(!a.server.equals(b.server), "A block ran one server twice");
        }
    }

    @Test
    void orderDependsOnlyOnTheSeed() throws Exception {
        final List<Plan.Run> first = Plan.expand(campaign(42));
        final List<Plan.Run> second = Plan.expand(campaign(42));

        for (int i = 0; i < first.size(); i++)
            assertEquals(first.get(i).id, second.get(i).id, "The same seed gave a different order");
    }

    @Test
    void rejectsRunsThatWouldShareAnId() throws Exception {
        final JsonNode duplicateValues = Output.JSON.readTree("{\"servers\": [{\"id\": \"a\"}],"
                + "\"suites\": [{\"name\": \"s\", \"type\": \"latency\", \"matrix\": {\"rate\": [\"a/b\", \"a:b\"]}}]}");
        final JsonNode duplicateServers = Output.JSON.readTree("{\"servers\": [{\"id\": \"a\"}, {\"id\": \"a\"}],"
                + "\"suites\": [{\"name\": \"s\", \"type\": \"latency\"}]}");

        assertThrows(IllegalArgumentException.class, () -> Plan.expand(duplicateValues), "Colliding run ids were accepted");
        assertThrows(IllegalArgumentException.class, () -> Plan.expand(duplicateServers), "Duplicate server ids were accepted");
    }

    @Test
    void idNamesOnlyTheMatrixAxes() throws Exception {
        final Plan.Run run = Plan.expand(campaign(1)).get(0);

        assertTrue(run.id.startsWith("lat_rate-"), "Unexpected id " + run.id);
        assertTrue(!run.id.contains("measureSeconds"), "A fixed parameter leaked into the id " + run.id);
        assertEquals(Map.of("rate", run.params.get("rate"), "burst", run.params.get("burst"), "measureSeconds", "20"), run.params);
    }
}
