package com.adebare.matchingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Validation and bookkeeping tests for {@link Order}. */
class OrderTest {

    @Test
    void tracksFilledAndRemainingQuantities() {
        Order o = new Order(1, Side.BUY, OrderType.LIMIT, 100, 10, 0);
        assertEquals(10, o.remainingQuantity());
        assertEquals(0, o.filledQuantity());
        assertFalse(o.isFilled());

        o.reduce(4);
        assertEquals(6, o.remainingQuantity());
        assertEquals(4, o.filledQuantity());
        assertFalse(o.isFilled());

        o.reduce(6);
        assertEquals(0, o.remainingQuantity());
        assertTrue(o.isFilled());
    }

    @Test
    void marketOrderIgnoresPrice() {
        Order o = new Order(1, Side.SELL, OrderType.MARKET, 999, 5, 0);
        assertEquals(0, o.price(), "market orders carry no meaningful price");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, Side.BUY, OrderType.LIMIT, 100, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, Side.BUY, OrderType.LIMIT, 100, -1, 0));
    }

    @Test
    void rejectsNonPositiveLimitPrice() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, Side.BUY, OrderType.LIMIT, 0, 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, Side.BUY, OrderType.LIMIT, -5, 10, 0));
    }

    @Test
    void rejectsNullSideOrType() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, null, OrderType.LIMIT, 100, 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, Side.BUY, null, 100, 10, 0));
    }

    @Test
    void rejectsOverfill() {
        Order o = new Order(1, Side.BUY, OrderType.LIMIT, 100, 5, 0);
        assertThrows(IllegalArgumentException.class, () -> o.reduce(6));
    }

    @Test
    void rejectsNonPositiveFill() {
        Order o = new Order(1, Side.BUY, OrderType.LIMIT, 100, 5, 0);
        assertThrows(IllegalArgumentException.class, () -> o.reduce(0));
        assertThrows(IllegalArgumentException.class, () -> o.reduce(-1));
    }

    @Test
    void oppositeSideIsCorrect() {
        assertEquals(Side.SELL, Side.BUY.opposite());
        assertEquals(Side.BUY, Side.SELL.opposite());
    }
}
