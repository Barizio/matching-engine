package com.adebare.matchingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the intrusive FIFO queue in {@link PriceLevel}, including the
 * O(1) arbitrary removal that underpins fast cancellation.
 */
class PriceLevelTest {

    private Order order(long id, long qty) {
        return new Order(id, Side.BUY, OrderType.LIMIT, 100, qty, id);
    }

    @Test
    void addsToTailAndPeeksHeadInFifoOrder() {
        PriceLevel level = new PriceLevel(100);
        Order a = order(1, 5);
        Order b = order(2, 7);
        level.add(a);
        level.add(b);

        assertSame(a, level.peek(), "head is the earliest order");
        assertEquals(2, level.orderCount());
        assertEquals(12, level.totalQuantity());
    }

    @Test
    void removingHeadAdvancesToNext() {
        PriceLevel level = new PriceLevel(100);
        Order a = order(1, 5);
        Order b = order(2, 7);
        level.add(a);
        level.add(b);

        level.remove(a);

        assertSame(b, level.peek());
        assertEquals(1, level.orderCount());
        assertEquals(7, level.totalQuantity());
    }

    @Test
    void removingMiddleOrderRelinksNeighbours() {
        PriceLevel level = new PriceLevel(100);
        Order a = order(1, 1);
        Order b = order(2, 2);
        Order c = order(3, 3);
        level.add(a);
        level.add(b);
        level.add(c);

        level.remove(b); // remove the middle node

        assertEquals(2, level.orderCount());
        assertEquals(4, level.totalQuantity());
        // Order preserved: a then c.
        assertSame(a, level.peek());
        level.remove(a);
        assertSame(c, level.peek());
    }

    @Test
    void removingLastOrderEmptiesLevel() {
        PriceLevel level = new PriceLevel(100);
        Order a = order(1, 5);
        level.add(a);

        level.remove(a);

        assertTrue(level.isEmpty());
        assertNull(level.peek());
        assertEquals(0, level.orderCount());
        assertEquals(0, level.totalQuantity());
    }

    @Test
    void reduceKeepsTotalQuantityConsistent() {
        PriceLevel level = new PriceLevel(100);
        Order a = order(1, 10);
        level.add(a);

        level.reduce(a, 4);

        assertEquals(6, level.totalQuantity());
        assertEquals(6, a.remainingQuantity());
    }
}
