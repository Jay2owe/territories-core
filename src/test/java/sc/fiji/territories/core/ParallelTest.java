package sc.fiji.territories.core;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ParallelTest {

    @Test
    public void workerCountFollowsTheParallelismProperty() {
        DensityParallelDeterminismTest.withParallelism("5", () -> {
            assertEquals(1, Parallel.workers(1));
            assertEquals(3, Parallel.workers(3));
            assertEquals(5, Parallel.workers(1000));
            return null;
        });
    }

    @Test
    public void everyIndexIsVisitedExactlyOnce() {
        for (String workers : new String[]{"1", "2", "3", "8"}) {
            for (int count : new int[]{1, 2, 7, 97, 1000}) {
                AtomicIntegerArray visits = new AtomicIntegerArray(count);
                DensityParallelDeterminismTest.withParallelism(workers, () -> {
                    Parallel.forRange(5, 5 + count, (from, to) -> {
                        for (int i = from; i < to; i++) visits.incrementAndGet(i - 5);
                    });
                    return null;
                });
                for (int i = 0; i < count; i++) {
                    assertEquals(workers + " workers, " + count + " items", 1, visits.get(i));
                }
            }
        }
    }

    @Test
    public void oneWorkerRunsTheWholeRangeOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        int[] calls = new int[1];
        DensityParallelDeterminismTest.withParallelism("1", () -> {
            Parallel.forRange(0, 50, (from, to) -> {
                assertSame(caller, Thread.currentThread());
                assertEquals(0, from);
                assertEquals(50, to);
                calls[0]++;
            });
            return null;
        });
        assertEquals(1, calls[0]);
    }

    @Test
    public void failureIsRethrownToTheCaller() {
        try {
            DensityParallelDeterminismTest.withParallelism("4", () -> {
                Parallel.forRange(0, 100, (from, to) -> {
                    if (from <= 60 && 60 < to) throw new IllegalArgumentException("item 60");
                });
                return null;
            });
            fail("expected the worker failure to reach the caller");
        } catch (IllegalArgumentException expected) {
            assertEquals("item 60", expected.getMessage());
        }
    }
}
