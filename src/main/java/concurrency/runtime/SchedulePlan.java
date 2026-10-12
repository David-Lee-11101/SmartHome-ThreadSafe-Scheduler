package concurrency.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SchedulePlan {
    private final String planId;
    private final long deadlineEpochMs;
    private final List<Task> tasks;
    private final List<Condition> conditions;

    public SchedulePlan(String planId, long deadlineEpochMs, List<Task> tasks) {
        this(planId, deadlineEpochMs, tasks, Collections.<Condition>emptyList());
    }

    public SchedulePlan(String planId, long deadlineEpochMs, List<Task> tasks, List<Condition> conditions) {
        this.planId = identifier(planId, "planId");
        if (deadlineEpochMs <= 0) throw new IllegalArgumentException("deadlineEpochMs must be positive");
        if (tasks == null || tasks.isEmpty() || tasks.size() > 256) {
            throw new IllegalArgumentException("tasks must contain between 1 and 256 entries");
        }
        this.deadlineEpochMs = deadlineEpochMs;
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        if (conditions == null || conditions.size() > 256) throw new IllegalArgumentException("conditions are invalid");
        this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        validate();
    }

    private void validate() {
        Set<String> ids = new HashSet<>();
        for (Task task : tasks) {
            if (!ids.add(task.getTaskId())) throw new IllegalArgumentException("task ids must be unique");
        }
        for (Task task : tasks) {
            for (String dependency : task.getDependencies()) {
                if (!ids.contains(dependency)) throw new IllegalArgumentException("unknown dependency: " + dependency);
                if (dependency.equals(task.getTaskId())) throw new IllegalArgumentException("task cannot depend on itself");
            }
        }
        Set<String> complete = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (Task task : tasks) {
                if (!complete.contains(task.getTaskId()) && complete.containsAll(task.getDependencies())) {
                    complete.add(task.getTaskId());
                    changed = true;
                }
            }
        } while (changed);
        if (complete.size() != tasks.size()) throw new IllegalArgumentException("task dependencies contain a cycle");
    }

    static String identifier(String value, String field) {
        if (value == null || value.length() < 3 || value.length() > 128 || !Character.isLetter(value.charAt(0))) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        for (char c : value.toCharArray()) {
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == ':' || c == '-')) {
                throw new IllegalArgumentException(field + " contains unsupported characters");
            }
        }
        return value;
    }

    public String getPlanId() { return planId; }
    public long getDeadlineEpochMs() { return deadlineEpochMs; }
    public List<Task> getTasks() { return tasks; }
    public List<Condition> getConditions() { return conditions; }

    public static final class Condition {
        private final String conditionId;
        private final String nodeId;
        private final Object expected;

        public Condition(String conditionId, String nodeId, Object expected) {
            this.conditionId = identifier(conditionId, "conditionId");
            this.nodeId = identifier(nodeId, "nodeId");
            if (expected == null) throw new IllegalArgumentException("expected typed value is required");
            this.expected = expected;
        }
        public String getConditionId() { return conditionId; }
        public String getNodeId() { return nodeId; }
        public Object getExpected() { return expected; }
    }

    public static final class Task {
        private final String taskId;
        private final List<String> dependencies;
        private final List<String> readResources;
        private final List<String> writeResources;
        private final Action action;

        public Task(String taskId, List<String> dependencies, List<String> readResources,
                    List<String> writeResources, Action action) {
            this.taskId = identifier(taskId, "taskId");
            this.dependencies = immutableIdentifiers(dependencies, "dependency");
            this.readResources = immutableIdentifiers(readResources, "read resource");
            this.writeResources = immutableIdentifiers(writeResources, "write resource");
            Set<String> overlap = new HashSet<>(this.readResources);
            overlap.retainAll(this.writeResources);
            if (!overlap.isEmpty()) throw new IllegalArgumentException("resource cannot be both read and written");
            this.action = action;
            if (action == null) throw new IllegalArgumentException("action is required");
        }

        private static List<String> immutableIdentifiers(List<String> values, String field) {
            if (values == null || values.size() > 256) throw new IllegalArgumentException(field + " list is invalid");
            List<String> copy = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (String value : values) {
                String checked = identifier(value, field);
                if (!unique.add(checked)) throw new IllegalArgumentException(field + " values must be unique");
                copy.add(checked);
            }
            return Collections.unmodifiableList(copy);
        }

        public String getTaskId() { return taskId; }
        public List<String> getDependencies() { return dependencies; }
        public List<String> getReadResources() { return readResources; }
        public List<String> getWriteResources() { return writeResources; }
        public Action getAction() { return action; }
    }

    public static final class Action {
        private final String serviceId;
        private final String operation;
        private final List<Object> arguments;

        public Action(String serviceId, String operation, List<Object> arguments) {
            this.serviceId = identifier(serviceId, "serviceId");
            this.operation = identifier(operation, "operation");
            if (arguments == null || arguments.size() > 64) throw new IllegalArgumentException("arguments are invalid");
            this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        }

        public String getServiceId() { return serviceId; }
        public String getOperation() { return operation; }
        public List<Object> getArguments() { return arguments; }
    }
}
