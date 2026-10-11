package concurrency.demo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import concurrency.scheduling.RuleTree;
import concurrency.scheduling.TaskNode;

import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Runs a deterministic, in-process simulation over RuleSpec v0 JSON. */
public final class SmartRuleSimulationDemo {
    private static final Set<String> RULE_FIELDS = new HashSet<>(Arrays.asList(
            "id", "description", "triggers", "conditions", "actions"));
    private static final Set<String> STATE_FIELDS = new HashSet<>(Arrays.asList("device", "state"));

    private SmartRuleSimulationDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: SmartRuleSimulationDemo <rulespec-v0.json>");
            System.exit(2);
        }
        try (FileReader reader = new FileReader(args[0])) {
            System.out.println(new Gson().toJson(run(parseRules(reader))));
        }
    }

    static List<RuleSpec> parseRules(Reader reader) {
        JsonElement document = JsonParser.parseReader(reader);
        if (!document.isJsonArray() || document.getAsJsonArray().size() == 0) {
            throw new IllegalArgumentException("RuleSpec v0 fixture must be a non-empty array");
        }
        List<RuleSpec> rules = new ArrayList<>();
        for (JsonElement entry : document.getAsJsonArray()) rules.add(parseRule(entry));
        return rules;
    }

    private static RuleSpec parseRule(JsonElement entry) {
        if (!entry.isJsonObject() || !entry.getAsJsonObject().keySet().equals(RULE_FIELDS)) {
            throw new IllegalArgumentException(
                    "RuleSpec v0 requires exactly id, description, triggers, conditions, and actions");
        }
        JsonObject value = entry.getAsJsonObject();
        return new RuleSpec(
                string(value.get("id"), "id"),
                string(value.get("description"), "description"),
                states(value.get("triggers"), "triggers", true),
                states(value.get("conditions"), "conditions", false),
                states(value.get("actions"), "actions", true));
    }

    private static List<RuleSpec.DeviceState> states(JsonElement element, String field, boolean required) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        JsonArray values = element.getAsJsonArray();
        if (required && values.size() == 0) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        List<RuleSpec.DeviceState> result = new ArrayList<>();
        for (JsonElement entry : values) {
            if (!entry.isJsonObject() || !entry.getAsJsonObject().keySet().equals(STATE_FIELDS)) {
                throw new IllegalArgumentException("each device state must contain exactly device and state");
            }
            JsonObject value = entry.getAsJsonObject();
            result.add(new RuleSpec.DeviceState(
                    string(value.get("device"), "device"), string(value.get("state"), "state")));
        }
        return result;
    }

    private static String string(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.getAsString();
    }

    public static Map<String, Object> run(List<RuleSpec> rules) throws InterruptedException {
        RuleTree tree = new RuleTree(new RuleTree.SimulationConfig(100.0, 20L, 0L, 1L), new Random(42L));
        Map<String, UUID> devices = new LinkedHashMap<>();
        for (RuleSpec rule : rules) {
            collectDevices(rule.getTriggers(), devices);
            collectDevices(rule.getActions(), devices);
        }
        for (Map.Entry<String, UUID> device : devices.entrySet()) tree.createDevice(device.getKey(), device.getValue());

        CopyOnWriteArrayList<String> executed = new CopyOnWriteArrayList<>();
        List<RuleTree.TaskHandle> handles = new ArrayList<>();
        for (RuleSpec rule : rules) {
            TreeSet<UUID> triggers = ids(rule.getTriggers(), devices);
            TreeSet<UUID> actions = ids(rule.getActions(), devices);
            handles.add(tree.createTask(rule.getId(), triggers, actions,
                    new TaskNode.SimpleExecFunc(rule.getId(), rule.getDescription(), 0L, null,
                            () -> executed.add(rule.getId()), false)));
        }
        for (RuleTree.TaskHandle handle : handles) {
            if (!handle.await(2_000L)) throw new IllegalStateException("simulation task did not complete");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "deterministic-simulation");
        result.put("ruleCount", rules.size());
        List<String> completedRuleIds = new ArrayList<>(executed);
        Collections.sort(completedRuleIds);
        result.put("completedRuleIds", completedRuleIds);
        result.put("conditionsApplied", false);
        return result;
    }

    private static void collectDevices(List<RuleSpec.DeviceState> states, Map<String, UUID> devices) {
        for (RuleSpec.DeviceState state : states) {
            devices.putIfAbsent(state.getDevice(), UUID.nameUUIDFromBytes(state.getDevice().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static TreeSet<UUID> ids(List<RuleSpec.DeviceState> states, Map<String, UUID> devices) {
        TreeSet<UUID> result = new TreeSet<>();
        for (RuleSpec.DeviceState state : states) result.add(devices.get(state.getDevice()));
        return result;
    }

}
