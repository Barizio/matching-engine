package com.adebare.matchingengine;

import java.util.Arrays;
import java.util.Random;

/**
 * A standalone throughput/latency benchmark for the matching engine.
 *
 * <p>It replays a deterministic stream of random orders around a fixed mid-price
 * so that a healthy mix of crossing (matching) and resting occurs, plus periodic
 * cancels to exercise that path and keep the book bounded. A warmup phase lets
 * the JIT compile the hot paths before measurement begins.
 *
 * <p>Usage:
 * <pre>{@code
 *   mvn -q package
 *   java -cp target/matching-engine.jar com.adebare.matchingengine.Benchmark [N]
 * }</pre>
 * where {@code N} is the number of measured operations (default 1,000,000).
 *
 * <p>Measurement caveat: each operation's latency is timed with
 * {@link System#nanoTime()}, whose call overhead (tens of nanoseconds) is
 * non-trivial relative to a single match. The reported percentiles therefore
 * include timer overhead and should be read as an upper bound; aggregate
 * throughput, timed once around the whole run, is the more reliable figure.
 */
public final class Benchmark {

    private static final long BASE_PRICE = 100_000L; // mid price in ticks
    private static final int PRICE_BAND = 50;         // orders land within +/- this many ticks
    private static final int MAX_QTY = 100;
    private static final long SEED = 42L;

    public static void main(String[] args) {
        int measured = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        int warmup = Math.min(measured, 200_000);

        System.out.println("Matching engine benchmark");
        System.out.println("  warmup ops   : " + warmup);
        System.out.println("  measured ops : " + measured);
        System.out.println();

        // Warmup: run the same workload untimed so the JIT warms the hot paths.
        run(new MatchingEngine(), warmup, new long[0]);

        long[] latencies = new long[measured];
        MatchingEngine engine = new MatchingEngine();

        long start = System.nanoTime();
        run(engine, measured, latencies);
        long elapsedNanos = System.nanoTime() - start;

        report(measured, elapsedNanos, latencies, engine);
    }

    /**
     * Drives {@code count} operations against {@code engine}. When
     * {@code latencies.length == count} each operation is individually timed;
     * otherwise (warmup) timing is skipped.
     */
    private static void run(MatchingEngine engine, int count, long[] latencies) {
        boolean timed = latencies.length == count;
        Random rng = new Random(SEED);
        // Ring buffer of recently-rested order ids, used as cancel candidates.
        long[] restingIds = new long[4096];
        int ringPos = 0;

        for (int i = 0; i < count; i++) {
            int roll = rng.nextInt(100);

            if (roll < 5) {
                // ~5% cancels of a recently seen order id (may be a no-op if filled).
                long candidate = restingIds[rng.nextInt(restingIds.length)];
                long opStart = timed ? System.nanoTime() : 0L;
                engine.cancel(candidate);
                if (timed) {
                    latencies[i] = System.nanoTime() - opStart;
                }
                continue;
            }

            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + rng.nextInt(MAX_QTY);

            long opStart = timed ? System.nanoTime() : 0L;
            MatchingEngine.SubmitResult result;
            if (roll < 20) {
                // ~15% market orders.
                result = engine.submitMarket(side, qty);
            } else {
                long price = BASE_PRICE + (rng.nextInt(2 * PRICE_BAND + 1) - PRICE_BAND);
                result = engine.submitLimit(side, price, qty);
            }
            if (timed) {
                latencies[i] = System.nanoTime() - opStart;
            }

            if (result.restsOnBook()) {
                restingIds[ringPos] = result.orderId();
                ringPos = (ringPos + 1) & (restingIds.length - 1);
            }
        }
    }

    private static void report(int ops, long elapsedNanos, long[] latencies, MatchingEngine engine) {
        double elapsedSec = elapsedNanos / 1e9;
        double throughput = ops / elapsedSec;

        long[] sorted = latencies.clone();
        Arrays.sort(sorted);

        System.out.printf("Elapsed        : %.3f s%n", elapsedSec);
        System.out.printf("Throughput     : %,.0f ops/sec%n", throughput);
        System.out.println();
        System.out.println("Per-operation latency (includes nanoTime overhead):");
        System.out.printf("  p50          : %,d ns%n", percentile(sorted, 0.50));
        System.out.printf("  p90          : %,d ns%n", percentile(sorted, 0.90));
        System.out.printf("  p99          : %,d ns%n", percentile(sorted, 0.99));
        System.out.printf("  p99.9        : %,d ns%n", percentile(sorted, 0.999));
        System.out.printf("  max          : %,d ns%n", sorted[sorted.length - 1]);
        System.out.printf("  mean         : %,.1f ns%n", mean(sorted));
        System.out.println();
        System.out.printf("Final book     : %,d resting orders, %d bid levels, %d ask levels%n",
                engine.book().restingOrderCount(),
                engine.book().levelCount(Side.BUY),
                engine.book().levelCount(Side.SELL));
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.round(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double mean(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    private Benchmark() {
    }
}
