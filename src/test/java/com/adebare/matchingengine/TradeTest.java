package com.adebare.matchingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Validation and derived-value tests for the {@link Trade} record. */
class TradeTest {

    @Test
    void computesNotional() {
        Trade t = new Trade(100, 7, 1, 2, Side.BUY, 0);
        assertEquals(700, t.notional());
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new Trade(100, 0, 1, 2, Side.BUY, 0));
    }

    @Test
    void rejectsNonPositivePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> new Trade(0, 5, 1, 2, Side.BUY, 0));
    }

    @Test
    void rejectsNullAggressorSide() {
        assertThrows(IllegalArgumentException.class,
                () -> new Trade(100, 5, 1, 2, null, 0));
    }
}
