package com.adebare.matchingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.adebare.matchingengine.MatchingEngine.SubmitResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link MatchingEngine} facade: id/timestamp assignment and the
 * {@link SubmitResult} summary it returns.
 */
class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    @Test
    void assignsUniqueMonotonicallyIncreasingIds() {
        SubmitResult a = engine.submitLimit(Side.BUY, 100, 1);
        SubmitResult b = engine.submitLimit(Side.SELL, 200, 1);
        SubmitResult c = engine.submitMarket(Side.BUY, 1);

        assertEquals(1, a.orderId());
        assertEquals(2, b.orderId());
        assertEquals(3, c.orderId());
    }

    @Test
    void restingLimitReportsRemainderOnBook() {
        SubmitResult r = engine.submitLimit(Side.BUY, 100, 10);

        assertTrue(r.trades().isEmpty());
        assertEquals(10, r.remainingQuantity());
        assertEquals(0, r.filledQuantity());
        assertFalse(r.fullyFilled());
        assertTrue(r.restsOnBook());
    }

    @Test
    void crossingLimitReportsFillsAndSummary() {
        engine.submitLimit(Side.SELL, 100, 4);
        SubmitResult r = engine.submitLimit(Side.BUY, 100, 10);

        assertEquals(1, r.trades().size());
        assertEquals(4, r.filledQuantity());
        assertEquals(6, r.remainingQuantity());
        assertFalse(r.fullyFilled());
        assertTrue(r.restsOnBook(), "6 rest at 100");
    }

    @Test
    void marketOrderNeverRestsEvenWithRemainder() {
        engine.submitLimit(Side.SELL, 100, 5);
        SubmitResult r = engine.submitMarket(Side.BUY, 8);

        assertEquals(5, r.filledQuantity());
        assertEquals(3, r.remainingQuantity());
        assertFalse(r.restsOnBook(), "market remainder is cancelled, not rested");
        assertEquals(0, engine.book().restingOrderCount());
    }

    @Test
    void cancelDelegatesToBook() {
        SubmitResult r = engine.submitLimit(Side.BUY, 100, 5);
        assertTrue(engine.book().contains(r.orderId()));

        assertTrue(engine.cancel(r.orderId()));
        assertFalse(engine.cancel(r.orderId()), "second cancel is a no-op");
        assertFalse(engine.book().contains(r.orderId()));
    }

    @Test
    void fullyFilledLimitDoesNotRest() {
        engine.submitLimit(Side.SELL, 100, 10);
        SubmitResult r = engine.submitLimit(Side.BUY, 100, 10);

        assertTrue(r.fullyFilled());
        assertFalse(r.restsOnBook());
        assertEquals(0, engine.book().restingOrderCount());
    }
}
