package com.adebare.matchingengine;

import java.util.List;

/**
 * A thin convenience facade over an {@link OrderBook} for a single instrument.
 *
 * <p>The engine owns identity and time: it assigns each incoming order a unique,
 * monotonically increasing id and a logical timestamp (a sequence number). Using
 * a logical clock rather than wall-clock time keeps matching deterministic and
 * reproducible, which matters for testing and for replaying an event stream.
 *
 * <p>Callers submit orders through {@link #submitLimit} / {@link #submitMarket}
 * and receive a {@link SubmitResult} carrying the assigned id and the resulting
 * trades. Cancellation is delegated straight to the book.
 *
 * <p>Not thread-safe: intended to be driven by a single matching thread.
 */
public final class MatchingEngine {

    private final OrderBook book = new OrderBook();
    private long nextOrderId = 1L;
    private long logicalClock = 0L;

    /**
     * Submits a limit order.
     *
     * @param side  buy or sell
     * @param price limit price in ticks (must be positive)
     * @param quantity order size (must be positive)
     * @return the assigned order id and the trades produced
     */
    public SubmitResult submitLimit(Side side, long price, long quantity) {
        return submit(new Order(nextOrderId++, side, OrderType.LIMIT, price, quantity, logicalClock++));
    }

    /**
     * Submits a market order, which executes against the best available prices
     * and cancels any unfilled remainder.
     *
     * @param side buy or sell
     * @param quantity order size (must be positive)
     * @return the assigned order id and the trades produced
     */
    public SubmitResult submitMarket(Side side, long quantity) {
        return submit(new Order(nextOrderId++, side, OrderType.MARKET, 0L, quantity, logicalClock++));
    }

    private SubmitResult submit(Order order) {
        List<Trade> trades = book.submit(order);
        return new SubmitResult(order.id(), trades, order.remainingQuantity(), order.type());
    }

    /**
     * Cancels a resting order by id.
     *
     * @return {@code true} if an order was removed, {@code false} otherwise
     */
    public boolean cancel(long orderId) {
        return book.cancel(orderId);
    }

    /** The underlying book, exposed read-mostly for inspection and reporting. */
    public OrderBook book() {
        return book;
    }

    /**
     * The outcome of submitting an order.
     *
     * @param orderId            the id assigned to the submitted order
     * @param trades             executions produced by the submission
     * @param remainingQuantity  quantity left after matching (resting for a limit
     *                           order, cancelled for a market order)
     * @param type               the submitted order's type
     */
    public record SubmitResult(long orderId, List<Trade> trades, long remainingQuantity, OrderType type) {

        /** Total quantity executed across all trades. */
        public long filledQuantity() {
            long sum = 0;
            for (Trade t : trades) {
                sum += t.quantity();
            }
            return sum;
        }

        public boolean fullyFilled() {
            return remainingQuantity == 0;
        }

        /** Whether any quantity remains resting on the book (limit orders only). */
        public boolean restsOnBook() {
            return type == OrderType.LIMIT && remainingQuantity > 0;
        }
    }
}
