package sc.fiji.territories.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The core's one worker-count rule and a small range splitter.
 *
 * <p>Callers only hand {@link #forRange} work whose results do not depend on
 * how the range is split or which thread runs which part: every output
 * element is written by exactly one chunk, with the same arithmetic in the
 * same order as the serial loop. Outputs are therefore bit-identical at any
 * worker count. {@code -Dterritories.parallelism=1} runs the whole range on
 * the calling thread.
 */
final class Parallel {

    /** Chunks per worker, so uneven chunks still balance across threads. */
    private static final int CHUNKS_PER_WORKER = 4;
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    /** Body of a parallel loop over the half-open index range {@code [from, to)}. */
    interface Range {
        void run(int from, int to);
    }

    private Parallel() {
    }

    /**
     * Worker threads for {@code tasks} independent tasks: the
     * {@code territories.parallelism} system property when positive, otherwise
     * the available processors capped at 8, never more than the task count.
     */
    static int workers(int tasks) {
        if (tasks < 2) return 1;
        int configured = Integer.getInteger("territories.parallelism", 0).intValue();
        int available = Runtime.getRuntime().availableProcessors();
        int desired = configured > 0 ? configured : Math.min(available, 8);
        return Math.max(1, Math.min(tasks, desired));
    }

    /**
     * Runs {@code body} over contiguous chunks covering {@code [from, to)}.
     * With one worker the body runs once over the whole range on the calling
     * thread. Otherwise the first failure, by chunk position, is rethrown
     * after all started chunks finish; remaining chunks are skipped.
     */
    static void forRange(int from, int to, final Range body) {
        final int count = to - from;
        if (count <= 0) return;
        int workers = workers(count);
        if (workers == 1) {
            body.run(from, to);
            return;
        }
        final int chunks = Math.min(count, workers * CHUNKS_PER_WORKER);
        final int start = from;
        final AtomicInteger next = new AtomicInteger();
        final AtomicBoolean failed = new AtomicBoolean();
        final Throwable[] failures = new Throwable[chunks];
        ExecutorService executor = Executors.newFixedThreadPool(workers, THREADS);
        try {
            List<Future<Void>> futures = new ArrayList<Future<Void>>(workers);
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        int chunk;
                        while (!failed.get() && (chunk = next.getAndIncrement()) < chunks) {
                            try {
                                body.run(
                                        start + chunkStart(chunk, count, chunks),
                                        start + chunkStart(chunk + 1, count, chunks));
                            } catch (Throwable failure) {
                                failures[chunk] = failure;
                                failed.set(true);
                            }
                        }
                        return null;
                    }
                }));
            }
            for (Future<Void> future : futures) await(future);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        for (Throwable failure : failures) {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            if (failure != null) throw new IllegalStateException("Parallel task failed.", failure);
        }
    }

    /** First index of {@code chunk} when {@code count} items are split into {@code chunks}. */
    static int chunkStart(int chunk, int count, int chunks) {
        return (int) ((long) chunk * count / chunks);
    }

    private static void await(Future<Void> future) {
        try {
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Territories computation was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            // Worker bodies catch everything themselves; this is unreachable.
            throw new IllegalStateException("Parallel task failed.", failed.getCause());
        }
    }

    private static final ThreadFactory THREADS = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable, "territories-worker-" + THREAD_NUMBER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    };
}
