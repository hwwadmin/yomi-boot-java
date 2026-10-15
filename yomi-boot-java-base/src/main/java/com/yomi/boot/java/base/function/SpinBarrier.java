package com.yomi.boot.java.base.function;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 自旋等待：在超时时间内周期性调用条件，直到返回 true 或超时/中断。
 *
 * @author zwei
 */
public class SpinBarrier {

    /** 两次轮询之间的休眠间隔（毫秒） */
    private static final long SPIN_INTERVAL_MILLIS = 100L;

    /**
     * 在 {@code timeout} 毫秒内轮询 {@code supplier}，返回 true 表示条件已满足；
     * {@code timeout <= 0} 时只调用一次；超时或中断返回 false。
     */
    public static boolean spin(Supplier<Boolean> supplier, long timeout) {
        if (timeout <= 0L) return supplier.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (Boolean.TRUE.equals(supplier.get())) return true;
                if (System.nanoTime() - deadline >= 0) break;
                Thread.sleep(SPIN_INTERVAL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

}
