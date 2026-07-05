package com.adebare.matchingengine;

/**
 * An immutable record of a single execution between an aggressing (incoming)
 * order and a resting (passive) order.
 *
 * <p>By convention the trade executes at the <em>resting</em> order's price.
 * This is the standard rule for continuous matching: the passive order was on
 * the book first and set the price, so a crossing aggressor receives any price
 * improvement. For example, if a sell limit rests at 100 and a buy limit
 * arrives at 105, the trade prints at 100.
 *
 * @param price            execution price in ticks (the resting order's price)
 * @param quantity         quantity executed
 * @param aggressorOrderId id of the incoming order that initiated the match
 * @param restingOrderId   id of the resting order that was matched against
 * @param aggressorSide    side of the aggressing order
 * @param timestamp        logical time of the execution
 */
public record Trade(
        long price,
        long quantity,
        long aggressorOrderId,
        long restingOrderId,
        Side aggressorSide,
        long timestamp) {

    public Trade {
        if (quantity <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive, was " + quantity);
        }
        if (price <= 0) {
            throw new IllegalArgumentException("trade price must be positive, was " + price);
        }
        if (aggressorSide == null) {
            throw new IllegalArgumentException("aggressorSide must be non-null");
        }
    }

    /** Notional value of the trade in ticks·units (price × quantity). */
    public long notional() {
        return price * quantity;
    }
}
