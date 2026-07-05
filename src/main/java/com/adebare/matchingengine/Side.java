package com.adebare.matchingengine;

/**
 * The side of an order.
 *
 * <p>A {@code BUY} order expresses willingness to buy at or below its limit
 * price; a {@code SELL} order expresses willingness to sell at or above its
 * limit price.
 */
public enum Side {
    BUY,
    SELL;

    /** Returns the opposite side, i.e. the side an order of this side matches against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
