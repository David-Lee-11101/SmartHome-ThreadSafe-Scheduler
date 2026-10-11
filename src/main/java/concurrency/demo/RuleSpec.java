package concurrency.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable in-memory representation of the shared RuleSpec v0 fixture. */
public final class RuleSpec {
    private final String id;
    private final String description;
    private final List<DeviceState> triggers;
    private final List<DeviceState> conditions;
    private final List<DeviceState> actions;

    public RuleSpec(String id, String description, List<DeviceState> triggers,
                    List<DeviceState> conditions, List<DeviceState> actions) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");
        if (description == null || description.isEmpty())
            throw new IllegalArgumentException("description is required");
        this.id = id;
        this.description = description;
        this.triggers = immutableRequired(triggers, "triggers");
        this.conditions = immutable(conditions);
        this.actions = immutableRequired(actions, "actions");
    }

    private static List<DeviceState> immutable(List<DeviceState> values) {
        if (values == null) throw new IllegalArgumentException("RuleSpec arrays are required");
        if (values.contains(null)) throw new IllegalArgumentException("RuleSpec arrays require device states");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<DeviceState> immutableRequired(List<DeviceState> values, String field) {
        List<DeviceState> copy = immutable(values);
        if (copy.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return copy;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public List<DeviceState> getTriggers() { return triggers; }
    public List<DeviceState> getConditions() { return conditions; }
    public List<DeviceState> getActions() { return actions; }

    public static final class DeviceState {
        private final String device;
        private final String state;

        public DeviceState(String device, String state) {
            if (device == null || device.isEmpty()) throw new IllegalArgumentException("device is required");
            if (state == null || state.isEmpty()) throw new IllegalArgumentException("state is required");
            this.device = device;
            this.state = state;
        }

        public String getDevice() { return device; }
        public String getState() { return state; }
    }
}
