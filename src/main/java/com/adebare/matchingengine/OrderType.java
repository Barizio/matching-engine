package com.adebare.matchingengine;

/**
 * The type of an order.
 *
 * <ul>
 *   <li>{@link #LIMIT} — executes only at its limit price or better; any
 *       unfilled quantity rests on the book.</li>
 *   <li>{@link #MARKET} — executes immediately against the best available
 *       prices regardless of price; any unfilled quantity is cancelled (never
 *       rests on the book).</li>
 * </ul>
 */
public enum OrderType {
    LIMIT,
    MARKET
}
