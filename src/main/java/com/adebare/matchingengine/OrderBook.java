package com.adebare.matchingengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A limit order book for a single instrument, enforcing strict price-time
 * priority.
 *
 * <h2>Data structures</h2>
 * <ul>
 *   <li>Two {@link TreeMap}s keyed by price in ticks: {@code bids} sorted
 *       descending (best/highest bid first) and {@code asks} sorted ascending
 *       (best/lowest ask first). Each maps a price to a {@link PriceLevel}. The
 *       tree gives O(log P) access to the best price and ordered traversal for
 *       market sweeps, where P is the number of distinct price levels.</li>
 *   <li>A {@code HashMap} from order id to the resting {@link Order}, giving
 *       O(1) location of any order for cancellation.</li>
 * </ul>
 *
 * <h2>Matching rules</h2>
 * <ul>
 *   <li>An incoming order matches against the opposite side while prices cross
 *       (for a limit order) or while any liquidity remains (for a market order).</li>
 *   <li>Within a price level, orders are filled in FIFO (time-priority) order.</li>
 *   <li>Each execution prints at the <em>resting</em> order's price.</li>
 *   <li>Leftover limit quantity rests on the book; leftover market quantity is
 *       cancelled.</li>
 * </ul>
 *
 * <p>This class is not thread-safe; a single instrument book is designed to be
 * driven by a single matching thread.
 */
public final class OrderBook {

    /** Bids: highest price first. */
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
    /** Asks: lowest price first. */
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    /** All resting orders, keyed by id, for O(1) cancellation. */
    private final Map<Long, Order> restingOrders = new HashMap<>();

    /**
     * Submits an order to the book, matching it against the opposite side and
     * resting any remainder (for limit orders).
     *
     * @param order the incoming order; must not already be resting on this book
     * @return the trades produced, in execution order (possibly empty)
     */
    public List<Trade> submit(Order order) {
        if (restingOrders.containsKey(order.id())) {
            throw new IllegalArgumentException("duplicate order id: " + order.id());
        }
        List<Trade> trades = new ArrayList<>();
        TreeMap<Long, PriceLevel> opposite = order.side() == Side.BUY ? asks : bids;

        match(order, opposite, trades);

        if (order.remainingQuantity() > 0 && order.type() == OrderType.LIMIT) {
            rest(order);
        }
        // Leftover MARKET quantity is simply discarded (cancelled).
        return trades;
    }

    /** Matches {@code incoming} against the opposite book while prices cross. */
    private void match(Order incoming, TreeMap<Long, PriceLevel> opposite, List<Trade> trades) {
        while (incoming.remainingQuantity() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel> bestEntry = opposite.firstEntry();
            long restingPrice = bestEntry.getKey();

            if (!crosses(incoming, restingPrice)) {
                break;
            }

            PriceLevel level = bestEntry.getValue();
            while (incoming.remainingQuantity() > 0 && !level.isEmpty()) {
                Order resting = level.peek();
                long fillQty = Math.min(incoming.remainingQuantity(), resting.remainingQuantity());

                trades.add(new Trade(
                        restingPrice,
                        fillQty,
                        incoming.id(),
                        resting.id(),
                        incoming.side(),
                        incoming.timestamp()));

                incoming.reduce(fillQty);
                level.reduce(resting, fillQty);

                if (resting.isFilled()) {
                    level.remove(resting);
                    restingOrders.remove(resting.id());
                }
            }

            if (level.isEmpty()) {
                opposite.remove(restingPrice);
            }
        }
    }

    /**
     * Whether an incoming order should trade against a resting price. Market
     * orders always cross; limit orders cross only when the prices meet.
     */
    private boolean crosses(Order incoming, long restingPrice) {
        if (incoming.type() == OrderType.MARKET) {
            return true;
        }
        return incoming.side() == Side.BUY
                ? incoming.price() >= restingPrice
                : incoming.price() <= restingPrice;
    }

    /** Rests a limit order on its own side of the book. */
    private void rest(Order order) {
        TreeMap<Long, PriceLevel> ownSide = order.side() == Side.BUY ? bids : asks;
        ownSide.computeIfAbsent(order.price(), PriceLevel::new).add(order);
        restingOrders.put(order.id(), order);
    }

    /**
     * Cancels the resting order with the given id.
     *
     * @return {@code true} if an order was found and removed; {@code false} if no
     *         such resting order exists (e.g. it already fully filled or was
     *         never resting)
     */
    public boolean cancel(long orderId) {
        Order order = restingOrders.remove(orderId);
        if (order == null) {
            return false;
        }
        TreeMap<Long, PriceLevel> ownSide = order.side() == Side.BUY ? bids : asks;
        PriceLevel level = ownSide.get(order.price());
        level.remove(order);
        if (level.isEmpty()) {
            ownSide.remove(order.price());
        }
        return true;
    }

    /** The best (highest) bid price in ticks, if any bids rest. */
    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    /** The best (lowest) ask price in ticks, if any asks rest. */
    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /**
     * The bid-ask spread in ticks, present only when both sides have liquidity.
     */
    public OptionalLong spread() {
        if (bids.isEmpty() || asks.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(asks.firstKey() - bids.firstKey());
    }

    /** Total open quantity resting at the given price, or 0 if none. */
    public long quantityAt(Side side, long price) {
        PriceLevel level = (side == Side.BUY ? bids : asks).get(price);
        return level == null ? 0L : level.totalQuantity();
    }

    /** Number of distinct price levels currently resting on the given side. */
    public int levelCount(Side side) {
        return (side == Side.BUY ? bids : asks).size();
    }

    /** Whether the given order id is currently resting on the book. */
    public boolean contains(long orderId) {
        return restingOrders.containsKey(orderId);
    }

    /** Total number of resting orders across both sides. */
    public int restingOrderCount() {
        return restingOrders.size();
    }

    /**
     * Aggregated depth for one side, best price first, up to {@code maxLevels}
     * levels. Useful for rendering a book snapshot.
     */
    public List<BookLevel> depth(Side side, int maxLevels) {
        TreeMap<Long, PriceLevel> book = side == Side.BUY ? bids : asks;
        List<BookLevel> out = new ArrayList<>(Math.min(maxLevels, book.size()));
        for (PriceLevel level : book.values()) {
            if (out.size() == maxLevels) {
                break;
            }
            out.add(new BookLevel(level.price(), level.totalQuantity(), level.orderCount()));
        }
        return out;
    }

    /**
     * A snapshot of one aggregated price level, used for reporting depth.
     *
     * @param price       price in ticks
     * @param quantity    total open quantity at the level
     * @param orderCount  number of orders resting at the level
     */
    public record BookLevel(long price, long quantity, int orderCount) {
    }
}
