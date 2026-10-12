package concurrency.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import concurrency.runtime.ExecutionEvent;
import concurrency.runtime.ProfileEvidence;
import concurrency.runtime.SchedulePlan;
import concurrency.runtime.SchedulingDecision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit adapter for the Hub contract catalog frozen at 1.0.1. */
public final class ContractJson {
    public static final String CATALOG_VERSION = "1.0.1";
    public static final String HUB_COMMIT = "ff569fdb97a7ad1f2a33665bd3eaceb1bcd28075";
    public static final String API_SHA256 = "a6b41f12ea80d2ea4872a34fd7c8b638ad6d4be19e14695ad0bd9dcb99a4365e";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContractJson() { }

    public static SchedulePlan readPlan(Path path) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        requireObject(root, "SchedulePlan", "schemaVersion", "planId", "tasks", "conditions", "deadlineEpochMs");
        requireCatalog(root);
        List<SchedulePlan.Task> tasks = new ArrayList<>();
        for (JsonNode task : array(root, "tasks")) {
            requireObject(task, "task", "taskId", "dependencies", "readResources", "writeResources", "action");
            JsonNode action = task.get("action");
            requireObject(action, "action", "serviceId", "operation", "arguments");
            List<Object> arguments = new ArrayList<>();
            for (JsonNode argument : array(action, "arguments")) arguments.add(JSON.convertValue(argument, Object.class));
            tasks.add(new SchedulePlan.Task(text(task, "taskId"), strings(task, "dependencies"),
                    strings(task, "readResources"), strings(task, "writeResources"),
                    new SchedulePlan.Action(text(action, "serviceId"), text(action, "operation"), arguments)));
        }
        List<SchedulePlan.Condition> conditions = new ArrayList<>();
        for (JsonNode condition : array(root, "conditions")) {
            requireObject(condition, "condition", "conditionId", "nodeId", "expected");
            conditions.add(new SchedulePlan.Condition(text(condition, "conditionId"),
                    text(condition, "nodeId"), JSON.convertValue(condition.get("expected"), Object.class)));
        }
        return new SchedulePlan(text(root, "planId"), root.get("deadlineEpochMs").longValue(), tasks, conditions);
    }

    public static ProfileEvidence readEvidence(Path path) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        requireObject(root, "ProfileEvidence", "schemaVersion", "profileId", "traceId", "modelVersion",
                "modelHash", "fixtureHash", "distance", "label", "confidence", "anomaly", "ambiguity",
                "patterns", "producedAt");
        requireCatalog(root);
        JsonNode anomaly = root.get("anomaly");
        JsonNode ambiguity = root.get("ambiguity");
        requireObject(anomaly, "anomaly", "detected", "reason");
        requireObject(ambiguity, "ambiguity", "detected", "candidateLabels");
        requireBoolean(anomaly, "detected");
        requireBoolean(ambiguity, "detected");
        text(anomaly, "reason");
        strings(ambiguity, "candidateLabels");
        requireNumber(root, "confidence");
        List<ProfileEvidence.Pattern> patterns = new ArrayList<>();
        for (JsonNode pattern : array(root, "patterns")) {
            requireObject(pattern, "pattern", "kind", "status", "details");
            patterns.add(new ProfileEvidence.Pattern(text(pattern, "kind"), text(pattern, "status")));
            text(pattern, "details");
        }
        return new ProfileEvidence(text(root, "profileId"), text(root, "modelVersion"),
                text(root, "modelHash"), text(root, "label"), root.get("confidence").doubleValue(),
                anomaly.get("detected").booleanValue(), ambiguity.get("detected").booleanValue(), patterns);
    }

    public static void writeDecisionAtomic(Path path, SchedulingDecision decision) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", CATALOG_VERSION);
        root.put("decisionId", decision.getDecisionId());
        root.put("planId", decision.getPlanId());
        root.put("profileEvidenceId", decision.getProfileEvidenceId());
        root.put("decision", decision.getDecision().name().toLowerCase());
        root.put("reason", decision.getReason());
        ArrayNode order = root.putArray("resolvedOrder");
        for (String task : decision.getResolvedOrder()) order.add(task);
        root.put("persistedAt", decision.getPersistedAt().toString());
        root.put("actionNotBefore", decision.getPersistedAt().toString());
        atomicWrite(path, root);
    }

    public static void writeEvents(Path path, List<ExecutionEvent> events) throws IOException {
        StringBuilder output = new StringBuilder();
        for (ExecutionEvent event : events) {
            output.append(JSON.writeValueAsString(eventJson(event))).append('\n');
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(path, output.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Persists one event before the caller proceeds to its next state or action. */
    public static void appendEvent(Path path, ExecutionEvent event) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] line = (JSON.writeValueAsString(eventJson(event)) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(absolute, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static ObjectNode eventJson(ExecutionEvent event) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", CATALOG_VERSION);
        root.put("executionId", event.getExecutionId());
        root.put("decisionId", event.getDecisionId());
        root.put("taskId", event.getTaskId());
        root.put("sequence", event.getSequence());
        root.put("state", event.getState().name().toLowerCase().replace('_', '-'));
        root.put("occurredAt", event.getOccurredAt().toString());
        if (!event.getErrorCode().isEmpty()) {
            ObjectNode error = root.putObject("error");
            error.put("code", event.getErrorCode());
            error.put("message", "See the local scheduler diagnostics for details.");
        }
        return root;
    }

    private static void atomicWrite(Path path, JsonNode value) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".decision-", ".json");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireCatalog(JsonNode root) {
        String version = text(root, "schemaVersion");
        if (!version.startsWith("1.")) throw new IllegalArgumentException("unsupported contract major version");
    }

    private static void requireObject(JsonNode node, String name, String... fields) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(name + " must be an object");
        Set<String> expected = new HashSet<>(Arrays.asList(fields));
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException(name + " has unsupported or missing fields");
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.textValue();
    }

    private static void requireBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
    }

    private static void requireNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(field + " must be numeric");
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isTextual()) throw new IllegalArgumentException(field + " entries must be text");
            values.add(value.textValue());
        }
        return values;
    }
}
