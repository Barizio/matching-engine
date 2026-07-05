package com.adebare.matchingengine;

/**
 * A single order in the matching engine.
 *
 * <p>Prices are represented as integer <em>ticks</em> ({@code long}) rather than
 * floating-point values. A tick is the smallest price increment of the
 * instrument (for example, if the tick size is $0.01 then a price of $100.25 is
 * the tick value {@code 10025}). Integer arithmetic makes matching exact and
 * deterministic and avoids the rounding errors inherent in {@code double}.
 *
 * <p>For a {@link OrderType#MARKET} order the {@link #price()} field is not
 * meaningful and is stored as {@code 0}.
 *
 * <p>An {@code Order} doubles as a node in the intrusive doubly-linked list
 * maintained by {@link PriceLevel}. The {@code prev}/{@code next} links are
 * package-private and are managed exclusively by {@link PriceLevel}; keeping the
 * links on the order itself is what allows cancellation to unlink an order in
 * O(1) without scanning the price level.
 */
public final class Order {

    private final long id;
    private final Side side;
    private final OrderType type;
    private final long price;
    private final long originalQuantity;
    private final long timestamp;

    /** Quantity still open (not yet filled or cancelled). Mutated during matching. */
    private long remainingQuantity;

    // Intrusive doubly-linked-list pointers, owned by the containing PriceLevel.
    Order prev;
    Order next;

    /**
     * Creates an order.
     *
     * @param id        unique identifier, assigned by the caller (typically the engine)
     * @param side      buy or sell
     * @param type      limit or market
     * @param price     limit price in ticks; ignored (pass {@code 0}) for market orders
     * @param quantity  order size; must be positive
     * @param timestamp logical arrival time used only for auditing; time priority
     *                  itself is enforced by FIFO insertion order within a price level
     */
    public Order(long id, Side side, OrderType type, long price, long quantity, long timestamp) {
        if (side == null || type == null) {
            throw new IllegalArgumentException("side and type must be non-null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        if (type == OrderType.LIMIT && price <= 0) {
            throw new IllegalArgumentException("limit price must be positive, was " + price);
        }
        this.id = id;
        this.side = side;
        this.type = type;
        this.price = type == OrderType.MARKET ? 0L : price;
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.timestamp = timestamp;
    }

    public long id() {
        return id;
    }

    public Side side() {
        return side;
    }

    public OrderType type() {
        return type;
    }

    public long price() {
        return price;
    }

    public long originalQuantity() {
        return originalQuantity;
    }

    public long remainingQuantity() {
        return remainingQuantity;
    }

    public long filledQuantity() {
        return originalQuantity - remainingQuantity;
    }

    public long timestamp() {
        return timestamp;
    }

    public boolean isFilled() {
        return remainingQuantity == 0;
    }

    /**
     * Reduces the remaining quantity by {@code quantity}.
     *
     * @throws IllegalArgumentException if {@code quantity} is non-positive or
     *                                  exceeds the remaining quantity
     */
    void reduce(long quantity) {
        if (quantity <= 0 || quantity > remainingQuantity) {
            throw new IllegalArgumentException(
                    "invalid fill quantity " + quantity + " for remaining " + remainingQuantity);
        }
        remainingQuantity -= quantity;
    }

    @Override
    public String toString() {
        return "Order{id=" + id
                + ", side=" + side
                + ", type=" + type
                + ", price=" + price
                + ", remaining=" + remainingQuantity
                + "/" + originalQuantity
                + '}';
    }
}
