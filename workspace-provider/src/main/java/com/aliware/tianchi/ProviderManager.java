package com.aliware.tianchi;

import org.apache.dubbo.rpc.Invoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Viber
 * @version 1.0
 * @apiNote 可以使用个Map保存不同Invoker对应的ProviderManager, 这里就先使用单独的
 * @since 2021/9/10 14:32
 */
public class ProviderManager {
    private final static Logger logger = LoggerFactory.getLogger(ProviderManager.class);
    private static SystemInfo si = new SystemInfo();
    private static HardwareAbstractionLayer hal = si.getHardware();
    private static ScheduledExecutorService scheduledExecutor;

    public static Value weight = new Value(50);
    public static final AtomicInteger active = new AtomicInteger(0);
    public static Value executeTime = new Value(10);
    private static final long windowSize = 10;
    static final long littleMillis = TimeUnit.MILLISECONDS.toNanos(1) / 100;
    static final int levelCount = 100; //能够支持统计tps的请求数
    static final int counterLength = 7;//奇数,应该可以使用url的参数形式传递
    static final int counterDelta = counterLength - 1;
    private static final Counter<SumCounter[]> counters = new Counter<>(l -> { //统计middle周围的tps数据
        SumCounter[] sumCounters = new SumCounter[counterLength];
        for (int i = 0; i < counterLength; i++) {
            sumCounters[i] = new SumCounter();
        }
        return sumCounters;
    });
    private static final long timeInterval = TimeUnit.MILLISECONDS.toNanos(100);//200?

    static {
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutor.scheduleWithFixedDelay(new CalculateTask(), 1000,
                200, TimeUnit.MILLISECONDS);
    }

    private static void resetWeight(long w) {
        weight.value = w;
    }

    private static void resetExecuteTime(int et) {
        executeTime.value = et;
    }

    public static void time(long duration, int concurrent) {
        int delta = (int) (weight.value - concurrent);
        if (delta >= 0 && delta <= counterDelta) {
            long offset = offset();
            SumCounter[] sumCounters = counters.get(offset);
            SumCounter sumCounter = sumCounters[counterDelta - delta];
            sumCounter.getTotal().add(1);
            sumCounter.getDuration().add(duration);
        }
    }

    private static class CalculateTask implements Runnable {
        @Override
        public void run() {
            long high = offset();
            long low = high - windowSize;

            Collection<SumCounter[]> sub = counters.sub(low, high);
            int[] counts = new int[counterLength];
            long[] durations = new long[counterLength];
            if (!sub.isEmpty()) {
                sub.forEach(state -> {
                    SumCounter counter;
                    for (int i = 0; i < counterLength; i++) {
                        counter = state[i];
                        counts[i] += counter.getTotal().sum();
                        durations[i] += counter.getDuration().sum();
                    }
                });
            }
            long toKey = high - (windowSize << 1);
            int middle = counterLength / 2;
            if (counts[middle] > levelCount) {
                long v = weight.value;
                long[] weights = new long[counterLength];
//              int[] weights = {v - 6, v - 5, v - 4, v - 3, v - 2, v - 1, v};
                for (int i = 0, j = counterLength - 1; i < counterLength; i++, j--) {
                    weights[i] = v - j;
                }
                long[] tps = new long[counterLength];
                long maxTps = 0;
                int targetTime = 0;
                int maxIndex = 0;
                for (int i = 0; i < counterLength; i++) {
                    if (counts[i] > levelCount) {
                        //简单实现, 保证1.xx的时间
                        double avgTime = Math.max(1.0, ((int) (((durations[i] / counts[i]) / littleMillis))) / 100.0);
                        long t = (long) ((1000.0 / avgTime) * weights[i]);//1s时间的tps
                        tps[i] = t;
                        if (maxTps < t) {
                            maxTps = t;
                            maxIndex = i;
                        }
                        targetTime = Math.max(targetTime, (int) (Math.ceil(1.7 * avgTime)));
                    }
                }
                long curTps = tps[middle];
                if (maxIndex > middle) {
                    if (halfGreaterThan(tps, middle + 1, counterLength, curTps)) {
                        resetWeight(v + 1);
                        toKey = high;
                    }
                } else if (maxIndex < middle) {
                    if (halfGreaterThan(tps, 0, middle, curTps)) {
                        resetWeight(v - 1);
                        toKey = high;
                    }
                }
                //存放合适的超时时间
                resetExecuteTime(targetTime);
            }
            counters.clean(toKey);
        }

        /**
         * 是否一半以上的大于curTps
         */
        private boolean halfGreaterThan(long[] tps, int l, int r, long curTps) {
            int total = 0;
            int most = 0;
            for (int i = l; i < r; i++) {
                if (tps[i] > 0) {
                    total++;
                    if (tps[i] >= curTps) {
                        most++;
                    }
                }
            }
            return most * 1.0 / total >= 0.5;
        }

    }

    public static long offset() {
        return System.nanoTime() / timeInterval;
    }

    private static double calculateMemory() {
        GlobalMemory memory = hal.getMemory();
        long total = memory.getTotal();
        return (total - memory.getAvailable()) * 1.0 / total;
    }

    private static double calculateCPURatio() {
        CentralProcessor processor = hal.getProcessor();
        long[] ticks = processor.getSystemCpuLoadTicks();
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()] + ticks[CentralProcessor.TickType.IOWAIT.getIndex()];
        long total = 0;
        for (long tick : ticks) {
            total += tick;
        }
        return total > 0L && idle >= 0L ? (double) (total - idle) / (double) total : 0.0D;
    }
}
