package concurrency.adapter;

import concurrency.runtime.ProfileEvidence;
import concurrency.runtime.ExecutionEvent;
import concurrency.runtime.SchedulePlan;
import concurrency.runtime.SchedulingDecision;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ContractJsonTest {
    private static final Path FIXTURES = Paths.get("src", "test", "resources", "contracts", "v1");

    @Test
    void readsCanonicalPlanAndEvidenceFromFrozenHub() throws Exception {
        SchedulePlan plan = ContractJson.readPlan(FIXTURES.resolve("schedule-plan.json"));
        ProfileEvidence evidence = ContractJson.readEvidence(FIXTURES.resolve("profile-evidence.json"));
        assertEquals("plan.cooling.001", plan.getPlanId());
        assertEquals(1, plan.getConditions().size());
        assertEquals("profile.sensor.001", evidence.getProfileId());
        assertEquals("medoid.001", evidence.getModelVersion());
    }

    @Test
    void writesCatalogCompatibleDecisionFieldsAtomically() throws Exception {
        Path directory = Files.createTempDirectory("scheduler-contract-");
        Path output = directory.resolve("decision.json");
        SchedulingDecision decision = new SchedulingDecision("decision-demo", "plan-demo",
                "profile-demo", SchedulingDecision.Kind.PROCEED, "trusted-profile",
                Collections.singletonList("task-demo"), Instant.parse("2026-10-12T00:00:00Z"));
        ContractJson.writeDecisionAtomic(output, decision);
        String json = new String(Files.readAllBytes(output), "UTF-8");
        assertTrue(json.contains("\"schemaVersion\" : \"1.0.1\""));
        assertTrue(json.contains("\"profileEvidenceId\" : \"profile-demo\""));
        assertFalse(Files.exists(directory.resolve(".decision-.json")));
    }

    @Test
    void rejectsMalformedNestedEvidenceInsteadOfSilentlyCoercingIt() throws Exception {
        Path malformed = Files.createTempFile("profile-evidence-malformed-", ".json");
        String valid = new String(Files.readAllBytes(FIXTURES.resolve("profile-evidence.json")), "UTF-8");
        Files.write(malformed, valid.replace("\"detected\": false", "\"detected\": \"false\"")
                .getBytes("UTF-8"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ContractJson.readEvidence(malformed));
        assertTrue(failure.getMessage().contains("boolean"));
    }

    @Test
    void rejectsEveryMalformedEvidenceBoundary() throws Exception {
        String valid = new String(Files.readAllBytes(FIXTURES.resolve("profile-evidence.json")), "UTF-8");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String[] fields = {"traceId", "fixtureHash", "distance", "producedAt", "schemaVersion"};
        Object[] bad = {"private/path", "bad", -1, "yesterday", "1.bad"};
        for (int i = 0; i < fields.length; i++) {
            com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
            node.set(fields[i], mapper.valueToTree(bad[i]));
            Path file = Files.createTempFile("invalid-evidence-", ".json");
            mapper.writeValue(file.toFile(), node);
            assertThrows(IllegalArgumentException.class, () -> ContractJson.readEvidence(file), fields[i]);
        }
        for (String field : new String[]{"kind", "status"}) {
            com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("patterns").get(0)).put(field, "unsupported");
            Path file = Files.createTempFile("invalid-pattern-", ".json");
            mapper.writeValue(file.toFile(), node);
            assertThrows(IllegalArgumentException.class, () -> ContractJson.readEvidence(file));
        }
    }

    @Test
    void rejectsMalformedTypedValuesAndDeadlineCoercion() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String malformed : new String[]{"true", "{\"type\":\"boolean\",\"value\":\"true\"}",
                "{\"type\":\"integer\",\"value\":1.5}", "{\"type\":\"null\",\"value\":0}",
                "{\"type\":\"boolean\",\"value\":true,\"extra\":1}"}) {
            com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(FIXTURES.resolve("schedule-plan.json").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("conditions").get(0))
                    .set("expected", mapper.readTree(malformed));
            Path file = Files.createTempFile("invalid-plan-", ".json");
            mapper.writeValue(file.toFile(), node);
            assertThrows(IllegalArgumentException.class, () -> ContractJson.readPlan(file));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("conditions").get(0))
                    .set("expected", mapper.readTree("{\"type\":\"boolean\",\"value\":true}"));
            ((com.fasterxml.jackson.databind.node.ArrayNode) node.get("tasks").get(0).get("action").get("arguments"))
                    .add(mapper.readTree(malformed));
            mapper.writeValue(file.toFile(), node);
            assertThrows(IllegalArgumentException.class, () -> ContractJson.readPlan(file));
        }
        com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(FIXTURES.resolve("schedule-plan.json").toFile());
        node.put("deadlineEpochMs", "1000");
        Path file = Files.createTempFile("invalid-deadline-", ".json");
        mapper.writeValue(file.toFile(), node);
        assertThrows(IllegalArgumentException.class, () -> ContractJson.readPlan(file));
    }

    @Test
    void rejectsDeepRecordsAndDuplicateKeys() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(FIXTURES.resolve("schedule-plan.json").toFile());
        com.fasterxml.jackson.databind.JsonNode value = mapper.readTree("{\"type\":\"boolean\",\"value\":true}");
        for (int i = 0; i < 5; i++) {
            com.fasterxml.jackson.databind.node.ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "record");
            wrapper.putObject("value").set("field", value);
            value = wrapper;
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("conditions").get(0)).set("expected", value);
        Path file = Files.createTempFile("invalid-depth-", ".json");
        mapper.writeValue(file.toFile(), root);
        assertThrows(IllegalArgumentException.class, () -> ContractJson.readPlan(file));
        String valid = new String(Files.readAllBytes(FIXTURES.resolve("schedule-plan.json")), "UTF-8");
        Files.write(file, ("{\"schemaVersion\":\"1.0.1\"," + valid.substring(1)).getBytes("UTF-8"));
        assertThrows(java.io.IOException.class, () -> ContractJson.readPlan(file));
    }

    @Test
    void rejectsUnsupportedCatalogAndInconsistentEvidenceStates() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String version : new String[]{"1.", "1.bad", "2.0.0"}) {
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(FIXTURES.resolve("schedule-plan.json").toFile());
            root.put("schemaVersion", version);
            Path file = Files.createTempFile("invalid-version-", ".json");
            mapper.writeValue(file.toFile(), root);
            assertThrows(IllegalArgumentException.class, () -> ContractJson.readPlan(file));
        }
        for (String state : new String[]{"anomaly", "ambiguity"}) {
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(FIXTURES.resolve("profile-evidence.json").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(state)).put("detected", true);
            Path file = Files.createTempFile("invalid-state-", ".json");
            mapper.writeValue(file.toFile(), root);
            assertThrows(IllegalArgumentException.class, () -> ContractJson.readEvidence(file));
        }
    }

    @Test
    void unsupportedAtomicPersistencePreservesDecisionAndPreventsAction() throws Exception {
        Path directory = Files.createTempDirectory("scheduler-atomic-failure-");
        Path output = directory.resolve("decision.json");
        Files.write(output, "previous-decision".getBytes("UTF-8"));
        SchedulePlan plan = ContractJson.readPlan(FIXTURES.resolve("schedule-plan.json"));
        ProfileEvidence evidence = ContractJson.readEvidence(FIXTURES.resolve("profile-evidence.json"));
        java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), java.time.ZoneOffset.UTC);
        concurrency.runtime.SchedulerEngine engine = new concurrency.runtime.SchedulerEngine(
                new concurrency.runtime.EvidencePolicy(
                        new java.util.HashSet<>(Collections.singletonList(evidence.getModelHash())),
                        new java.util.HashSet<>(Collections.singletonList(evidence.getModelVersion())), 0.8, clock),
                new concurrency.runtime.ResourceLockManager(), clock);
        java.util.concurrent.atomic.AtomicInteger actions = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.nio.file.AtomicMoveNotSupportedException.class, () -> engine.execute(
                plan, evidence, (activePlan, task) -> true, (action, cancellation) -> actions.incrementAndGet(),
                decision -> ContractJson.writeDecisionAtomic(output, decision, (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported");
                }), event -> { }, () -> false));
        assertEquals(0, actions.get());
        assertEquals("previous-decision", new String(Files.readAllBytes(output), "UTF-8"));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count(), "temporary decision must be removed");
        }
    }

    @Test
    void appendsSanitizedExecutionEventsInSequence() throws Exception {
        Path output = Files.createTempFile("execution-events-", ".jsonl");
        Files.delete(output);
        ContractJson.appendEvent(output, new ExecutionEvent("execution-demo", "decision-demo", "task-demo",
                0, ExecutionEvent.State.FAILED, "ACTION_CALLBACK_FAILED",
                Instant.parse("2026-10-12T00:00:01Z")));
        String line = new String(Files.readAllBytes(output), "UTF-8");
        assertTrue(line.contains("\"sequence\":0"));
        assertTrue(line.contains("\"code\":\"ACTION_CALLBACK_FAILED\""));
        assertFalse(line.contains("endpoint"));
        assertEquals(1, Files.readAllLines(output).size());
    }
}
