package concurrency.scheduling;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RuleTreeTest {
    @Test
    void rejectsArrivalPercentOutsideZeroToOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleTree.SimulationConfig(-0.1, 10, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleTree.SimulationConfig(100.1, 10, 0, 1));
    }

    @Test
    void zeroPercentTimesOutWithoutExecutingAction() throws Exception {
        CopyOnWriteArrayList<String> executions = new CopyOnWriteArrayList<>();
        RuleTree tree = tree(0.0, 20, 7L);

        RuleTree.TaskHandle handle = task(tree, "never-arrives", executions, "never");

        assertTrue(handle.await(1000));
        assertFalse(handle.didActionArrive());
        assertTrue(handle.didWindowExpire());
        assertTrue(executions.isEmpty());
    }

    @Test
    void hundredPercentAlwaysExecutesAction() throws Exception {
        CopyOnWriteArrayList<String> executions = new CopyOnWriteArrayList<>();
        RuleTree tree = tree(100.0, 20, 7L);

        RuleTree.TaskHandle handle = task(tree, "always-arrives", executions, "arrived");

        assertTrue(handle.await(1000));
        assertTrue(handle.didActionArrive());
        assertEquals(Arrays.asList("arrived"), executions);
    }

    @Test
    void seededSimulationIsRepeatable() throws Exception {
        assertEquals(runDecisionSequence(42L), runDecisionSequence(42L));
    }

    @Test
    void concurrentTasksComplete() throws Exception {
        CopyOnWriteArrayList<String> executions = new CopyOnWriteArrayList<>();
        RuleTree tree = tree(100.0, 20, 11L);

        RuleTree.TaskHandle first = task(tree, "first", executions, "first");
        RuleTree.TaskHandle second = task(tree, "second", executions, "second");

        assertTrue(first.await(1000));
        assertTrue(second.await(1000));
        assertEquals(2, executions.size());
    }

    @Test
    void tasksWritingTheSameDeviceFollowCreationOrder() throws Exception {
        CopyOnWriteArrayList<String> executions = new CopyOnWriteArrayList<>();
        RuleTree tree = tree(100.0, 20, 13L);
        UUID action = UUID.nameUUIDFromBytes("shared-action".getBytes());
        UUID firstTrigger = UUID.nameUUIDFromBytes("first-trigger".getBytes());
        UUID secondTrigger = UUID.nameUUIDFromBytes("second-trigger".getBytes());
        tree.createDevice("shared action", action);
        tree.createDevice("first trigger", firstTrigger);
        tree.createDevice("second trigger", secondTrigger);

        RuleTree.TaskHandle first = tree.createTask("first", new TreeSet<>(Arrays.asList(firstTrigger)),
                new TreeSet<>(Arrays.asList(action)),
                new TaskNode.SimpleExecFunc("first", "first", 20, null, () -> executions.add("first")));
        RuleTree.TaskHandle second = tree.createTask("second", new TreeSet<>(Arrays.asList(secondTrigger)),
                new TreeSet<>(Arrays.asList(action)),
                new TaskNode.SimpleExecFunc("second", "second", 0, null, () -> executions.add("second")));

        assertTrue(first.await(1000));
        assertTrue(second.await(1000));
        assertEquals(Arrays.asList("first", "second"), executions);
    }

    @Test
    void surfacesCompletionActionFailureFromTaskHandle() throws Exception {
        RuleTree tree = tree(100.0, 20, 17L);
        UUID trigger = UUID.nameUUIDFromBytes("failing-trigger".getBytes());
        UUID action = UUID.nameUUIDFromBytes("failing-action".getBytes());
        tree.createDevice("failing trigger", trigger);
        tree.createDevice("failing action", action);

        RuleTree.TaskHandle handle = tree.createTask("failing-task",
                new TreeSet<>(Arrays.asList(trigger)),
                new TreeSet<>(Arrays.asList(action)),
                new TaskNode.SimpleExecFunc("failing-task", "failure", 0, null,
                        () -> { throw new IllegalStateException("action failed"); }));

        RuleTree.TaskExecutionException failure = assertThrows(
                RuleTree.TaskExecutionException.class, () -> handle.await(1000));
        assertEquals("action failed", failure.getCause().getMessage());
    }

    private static RuleTree tree(double percent, long windowMillis, long seed) {
        return new RuleTree(new RuleTree.SimulationConfig(percent, windowMillis, 0, 1), new Random(seed));
    }

    private static RuleTree.TaskHandle task(RuleTree tree, String name,
                                            CopyOnWriteArrayList<String> executions, String marker) {
        UUID trigger = UUID.nameUUIDFromBytes((name + "-trigger").getBytes());
        UUID action = UUID.nameUUIDFromBytes((name + "-action").getBytes());
        tree.createDevice(name + " trigger", trigger);
        tree.createDevice(name + " action", action);
        return tree.createTask(name, new TreeSet<>(Arrays.asList(trigger)),
                new TreeSet<>(Arrays.asList(action)),
                new TaskNode.SimpleExecFunc(name, marker, 0, null, () -> executions.add(marker)));
    }

    private static String runDecisionSequence(long seed) throws Exception {
        CopyOnWriteArrayList<String> executions = new CopyOnWriteArrayList<>();
        RuleTree tree = tree(50.0, 5, seed);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            RuleTree.TaskHandle handle = task(tree, "rule-" + i, executions, String.valueOf(i));
            assertTrue(handle.await(1000));
            result.append(handle.didActionArrive() ? '1' : '0');
        }
        return result.toString();
    }
}
