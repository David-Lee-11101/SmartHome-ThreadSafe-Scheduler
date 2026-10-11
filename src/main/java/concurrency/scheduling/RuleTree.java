package concurrency.scheduling;

import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Experimental scheduler with injectable randomness and timing. */
public class RuleTree {
    private final RootNode rootNode = new RootNode();
    private final SimulationConfig config;
    private final Random random;

    public RuleTree() {
        this(new SimulationConfig(2.0, 10_000L, 500L, 100L), new Random());
    }

    public RuleTree(SimulationConfig config, Random random) {
        if (config == null || random == null) throw new IllegalArgumentException("config and random are required");
        this.config = config;
        this.random = random;
    }

    public void createDevice(String deviceName, UUID deviceUUID) {
        rootNode.addDeviceNode(new DeviceNode(rootNode, deviceUUID, deviceName));
    }

    public TaskHandle createTask(String taskName, TreeSet<UUID> triggerDevices,
                                 TreeSet<UUID> actionDevices, TaskNode.SimpleExecFunc execFunc) {
        AtomicBoolean arrivalFlag = new AtomicBoolean(false);
        AtomicBoolean timeWindowFlag = new AtomicBoolean(false);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch dependenciesReady = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread taskThread = new Thread(() -> {
            try {
                new TaskNode(rootNode, UUID.randomUUID(), taskName, triggerDevices, actionDevices, execFunc)
                        .runTask(arrivalFlag, timeWindowFlag, config.pollIntervalMillis, dependenciesReady);
            } catch (Throwable taskFailure) {
                failure.set(taskFailure);
            } finally {
                dependenciesReady.countDown();
                completed.countDown();
            }
        }, "smart-rule-task-" + taskName);
        Thread listenerThread = new Thread(() -> {
            try {
                long delay;
                boolean arrives;
                synchronized (random) {
                    delay = config.windowMillis == 0 ? 0 : (long) (random.nextDouble() * config.windowMillis);
                    arrives = random.nextDouble() * 100.0 < config.arrivalPercent;
                }
                Thread.sleep(delay);
                arrivalFlag.set(arrives);
                if (!arrives) Thread.sleep(config.windowMillis - delay);
                timeWindowFlag.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                timeWindowFlag.set(true);
            }
        }, "smart-rule-listener-" + taskName);
        taskThread.start();
        try {
            if (!dependenciesReady.await(5, TimeUnit.SECONDS)) {
                timeWindowFlag.set(true);
                throw new IllegalStateException("task dependency initialization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            timeWindowFlag.set(true);
            throw new IllegalStateException("task dependency initialization was interrupted", interrupted);
        }
        if (config.listenerStartDelayMillis > 0) {
            try {
                Thread.sleep(config.listenerStartDelayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                timeWindowFlag.set(true);
            }
        }
        listenerThread.start();
        return new TaskHandle(arrivalFlag, timeWindowFlag, completed, taskThread, listenerThread, failure);
    }

    public static final class SimulationConfig {
        private final double arrivalPercent;
        private final long windowMillis;
        private final long listenerStartDelayMillis;
        private final long pollIntervalMillis;

        public SimulationConfig(double arrivalPercent, long windowMillis,
                                long listenerStartDelayMillis, long pollIntervalMillis) {
            if (Double.isNaN(arrivalPercent) || arrivalPercent < 0 || arrivalPercent > 100)
                throw new IllegalArgumentException("arrivalPercent must be between 0 and 100");
            if (windowMillis < 0 || listenerStartDelayMillis < 0 || pollIntervalMillis <= 0)
                throw new IllegalArgumentException("timing values must be non-negative and poll interval positive");
            this.arrivalPercent = arrivalPercent;
            this.windowMillis = windowMillis;
            this.listenerStartDelayMillis = listenerStartDelayMillis;
            this.pollIntervalMillis = pollIntervalMillis;
        }
    }

    public static final class TaskHandle {
        private final AtomicBoolean arrivalFlag;
        private final AtomicBoolean timeWindowFlag;
        private final CountDownLatch completed;
        private final Thread taskThread;
        private final Thread listenerThread;
        private final AtomicReference<Throwable> failure;

        private TaskHandle(AtomicBoolean arrivalFlag, AtomicBoolean timeWindowFlag,
                           CountDownLatch completed, Thread taskThread, Thread listenerThread,
                           AtomicReference<Throwable> failure) {
            this.arrivalFlag = arrivalFlag;
            this.timeWindowFlag = timeWindowFlag;
            this.completed = completed;
            this.taskThread = taskThread;
            this.listenerThread = listenerThread;
            this.failure = failure;
        }

        public boolean await(long timeoutMillis) throws InterruptedException {
            boolean finished = completed.await(timeoutMillis, TimeUnit.MILLISECONDS);
            if (finished) {
                taskThread.join(timeoutMillis);
                listenerThread.join(timeoutMillis);
                if (failure.get() != null) {
                    throw new TaskExecutionException("scheduled task failed", failure.get());
                }
            }
            return finished;
        }

        public boolean didActionArrive() { return arrivalFlag.get(); }
        public boolean didWindowExpire() { return timeWindowFlag.get() && !arrivalFlag.get(); }
    }

    public static final class TaskExecutionException extends IllegalStateException {
        private TaskExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
