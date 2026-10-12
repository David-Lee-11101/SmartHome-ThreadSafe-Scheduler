package concurrency.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Stable-order read/write lock acquisition with reverse release. */
public final class ResourceLockManager {
    private final Map<String, ReentrantReadWriteLock> locks = new TreeMap<>();

    public Lease acquire(List<String> reads, List<String> writes, long timeoutMillis,
                         SchedulerPorts.CancellationToken cancellation) throws InterruptedException {
        List<Request> requests = new ArrayList<>();
        for (String resource : reads) requests.add(new Request(resource, false));
        for (String resource : writes) requests.add(new Request(resource, true));
        Collections.sort(requests, Comparator.comparing(Request::getResource));
        List<Lock> acquired = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        try {
            for (Request request : requests) {
                if (cancellation.isCancelled()) throw new WaitAbortedException("cancelled while waiting for resources");
                ReentrantReadWriteLock resourceLock;
                synchronized (locks) {
                    resourceLock = locks.computeIfAbsent(request.resource,
                            ignored -> new ReentrantReadWriteLock(true));
                }
                Lock lock = request.write ? resourceLock.writeLock() : resourceLock.readLock();
                while (true) {
                    if (cancellation.isCancelled()) {
                        throw new WaitAbortedException("cancelled while waiting for resources");
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new WaitAbortedException("resource acquisition deadline exceeded");
                    }
                    if (lock.tryLock(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                            TimeUnit.NANOSECONDS)) {
                        acquired.add(lock);
                        break;
                    }
                }
            }
            return new Lease(acquired);
        } catch (InterruptedException failure) {
            unlockReverse(acquired);
            throw failure;
        } catch (RuntimeException failure) {
            unlockReverse(acquired);
            throw failure;
        }
    }

    private static void unlockReverse(List<Lock> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) locks.get(index).unlock();
    }

    private static final class Request {
        private final String resource;
        private final boolean write;
        private Request(String resource, boolean write) { this.resource = resource; this.write = write; }
        private String getResource() { return resource; }
    }

    /** Token cancellation or deadline expiry, distinct from an external thread interrupt. */
    public static final class WaitAbortedException extends InterruptedException {
        private WaitAbortedException(String message) { super(message); }
    }

    public static final class Lease implements AutoCloseable {
        private final List<Lock> acquired;
        private boolean closed;
        private Lease(List<Lock> acquired) { this.acquired = acquired; }
        @Override public void close() {
            if (!closed) {
                unlockReverse(acquired);
                closed = true;
            }
        }
    }
}
