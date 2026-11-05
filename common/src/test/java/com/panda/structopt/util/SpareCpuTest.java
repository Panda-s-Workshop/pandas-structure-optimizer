package com.panda.structopt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpareCpuTest {
    @Test
    void goesOnAtOnceBelowTheLimit() {
        AtomicInteger looks = new AtomicInteger();
        assertTrue(SpareCpu.await(0.75, () -> { looks.incrementAndGet(); return 0.3; }, () -> false, 1));
        assertEquals(1, looks.get());
    }

    @Test
    void goesOnWhenTheLoadIsUnknown() {
        assertTrue(SpareCpu.await(0.75, () -> -1, () -> false, 1));
    }

    @Test
    void waitsWhileTheMachineIsBusy() {
        double[] loads = {0.9, 0.95, 0.8, 0.5};
        AtomicInteger looks = new AtomicInteger();
        assertTrue(SpareCpu.await(0.75, () -> loads[looks.getAndIncrement()], () -> false, 1));
        assertEquals(4, looks.get());
    }

    @Test
    void stopsWhileWaiting() {
        AtomicInteger looks = new AtomicInteger();
        assertFalse(SpareCpu.await(0.75, () -> 1.0, () -> looks.incrementAndGet() > 3, 1));
    }

    @Test
    void theSystemLoadIsReadable() {
        double load = SpareCpu.SYSTEM.getAsDouble();
        assertTrue(load <= 1.0, "load " + load);
    }
}
