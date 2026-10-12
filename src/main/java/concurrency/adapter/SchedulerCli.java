package concurrency.adapter;

import concurrency.runtime.EvidencePolicy;
import concurrency.runtime.ProfileEvidence;
import concurrency.runtime.ResourceLockManager;
import concurrency.runtime.SchedulePlan;
import concurrency.runtime.SchedulerEngine;
import concurrency.runtime.SchedulerPorts;
import concurrency.runtime.SchedulingDecision;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** File adapter for Hub integration. Execution requires explicit real callback classes. */
public final class SchedulerCli {
    private SchedulerCli() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path planPath = requiredPath(options, "--plan");
        Path evidencePath = requiredPath(options, "--evidence");
        Path decisionPath = requiredPath(options, "--decision");
        String trustedHash = required(options, "--trusted-model-hash");
        String trustedVersion = required(options, "--trusted-model-version");
        SchedulePlan plan = ContractJson.readPlan(planPath);
        ProfileEvidence evidence = ContractJson.readEvidence(evidencePath);
        EvidencePolicy policy = new EvidencePolicy(new HashSet<>(Collections.singletonList(trustedHash)),
                new HashSet<>(Collections.singletonList(trustedVersion)), 0.8, Clock.systemUTC());

        if (options.containsKey("--policy-only")) {
            ContractJson.writeDecisionAtomic(decisionPath, policy.decide(plan, evidence));
            return;
        }
        String handlerClass = required(options, "--handler-class");
        String conditionClass = required(options, "--condition-class");
        Path eventsPath = requiredPath(options, "--events");
        SchedulerPorts.ActionHandler handler = instantiate(handlerClass, SchedulerPorts.ActionHandler.class);
        SchedulerPorts.ConditionEvaluator conditions = instantiate(conditionClass,
                SchedulerPorts.ConditionEvaluator.class);
        Files.deleteIfExists(eventsPath);
        SchedulerPorts.EventSink durableEvents = event -> {
            try {
                ContractJson.appendEvent(eventsPath, event);
            } catch (IOException failure) {
                throw new UncheckedIOException("execution event persistence failed", failure);
            }
        };
        SchedulerEngine engine = new SchedulerEngine(policy, new ResourceLockManager(), Clock.systemUTC());
        SchedulerEngine.Result result = engine.execute(plan, evidence, conditions, handler,
                decision -> ContractJson.writeDecisionAtomic(decisionPath, decision),
                durableEvents, () -> Thread.currentThread().isInterrupted());
        if (result.getDecision().allowsAction() && result.getCompletedActions() == 0) {
            throw new IllegalStateException("NO_ACTION_COMPLETED");
        }
    }

    private static Map<String, String> parse(String[] arguments) {
        Map<String, String> options = new HashMap<>();
        List<String> flags = Arrays.asList("--policy-only");
        for (int index = 0; index < arguments.length; index++) {
            String key = arguments[index];
            if (flags.contains(key)) {
                options.put(key, "true");
            } else {
                if (!key.startsWith("--") || index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("invalid CLI argument: " + key);
                }
                options.put(key, arguments[++index]);
            }
        }
        return options;
    }

    private static Path requiredPath(Map<String, String> options, String key) {
        return Paths.get(required(options, key));
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static <T> T instantiate(String className, Class<T> expected) throws Exception {
        Object value = Class.forName(className).getDeclaredConstructor().newInstance();
        if (!expected.isInstance(value)) throw new IllegalArgumentException(className + " does not implement " + expected.getName());
        return expected.cast(value);
    }
}
