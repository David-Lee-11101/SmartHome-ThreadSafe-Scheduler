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
