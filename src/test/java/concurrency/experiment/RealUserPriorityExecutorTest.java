package concurrency.experiment;

import concurrency.experiment.RealUser.ThreadPool.RealUserPriorityExecutor;
import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealUserPriorityExecutorTest {
    @Test
    void lowerRuleIdHasHigherSchedulingPriority() {
        PriorityQueue<RealUserPriorityExecutor.PriorityRunnable> queue = new PriorityQueue<>();
        queue.add(new RealUserPriorityExecutor.PriorityRunnable(9, () -> { }));
        queue.add(new RealUserPriorityExecutor.PriorityRunnable(2, () -> { }));
        assertEquals(2, queue.remove().getPriority());
    }
}
