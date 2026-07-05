package com.adebare.matchingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.adebare.matchingengine.MatchingEngine.SubmitResult;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end scenarios and invariants that exercise several operations in
 * sequence through the {@link MatchingEngine} facade.
 */
class ScenarioTest {

    @Test
    @DisplayName("A full session: rest, cross, sweep, cancel, and settle")
    void multiStepSession() {
        MatchingEngine engine = new MatchingEngine();

        engine.submitLimit(Side.SELL, 101, 5);
        engine.submitLimit(Side.SELL, 102, 5);
        engine.submitLimit(Side.BUY, 99, 5);
        SubmitResult toCancel = engine.submitLimit(Side.BUY, 98, 5);

        // Cross the spread: buy 8 @ 101 fills the 101 ask (5) and rests 3 at 101 bid.
        SubmitResult cross = engine.submitLimit(Side.BUY, 101, 8);
        assertEquals(5, cross.filledQuantity());
        assertEquals(3, cross.remainingQuantity());
        assertEquals(101, engine.book().bestBid().getAsLong());
        assertEquals(102, engine.book().bestAsk().getAsLong());

        // Market sell 4 hits the 101 bid (3) then the 99 bid (1).
        SubmitResult mkt = engine.submitMarket(Side.SELL, 4);
        assertEquals(4, mkt.filledQuantity());
        assertEquals(2, mkt.trades().size());
        assertEquals(101, mkt.trades().get(0).price());
        assertEquals(99, mkt.trades().get(1).price());

        // Cancel a still-resting bid.
        assertTrue(engine.cancel(toCancel.orderId()));

        // Remaining book: 4 @ 99 bid (5 - 1 filled), and the 102 ask.
        assertEquals(99, engine.book().bestBid().getAsLong());
        assertEquals(4, engine.book().quantityAt(Side.BUY, 99));
        assertEquals(5, engine.book().quantityAt(Side.SELL, 102));
    }

    @Test
    @DisplayName("Conservation of quantity: submitted == filled (both sides) + resting + cancelled market")
    void quantityIsConserved() {
        MatchingEngine engine = new MatchingEngine();
        Random rng = new Random(7);

        long submittedQty = 0;
        // Each trade fills q on the aggressor AND q on a resting order, so summing
        // fills across all orders double-counts each traded unit: 2 * sum(trade qty).
        long filledUnitsBothSides = 0;
        // Market-order remainder is discarded (not resting, not filled).
        long cancelledMarketQty = 0;

        for (int i = 0; i < 20_000; i++) {
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + rng.nextInt(50);
            submittedQty += qty;

            SubmitResult r;
            if (rng.nextInt(100) < 15) {
                r = engine.submitMarket(side, qty);
                cancelledMarketQty += r.remainingQuantity();
            } else {
                long price = 1000 + rng.nextInt(21) - 10;
                r = engine.submitLimit(side, price, qty);
            }
            for (Trade t : r.trades()) {
                filledUnitsBothSides += 2L * t.quantity();
            }
        }

        long restingQty = 0;
        for (OrderBook.BookLevel l : engine.book().depth(Side.BUY, Integer.MAX_VALUE)) {
            restingQty += l.quantity();
        }
        for (OrderBook.BookLevel l : engine.book().depth(Side.SELL, Integer.MAX_VALUE)) {
            restingQty += l.quantity();
        }

        // Every submitted unit ends up in exactly one bucket: matched (counted on
        // both the aggressor and resting side), still resting, or a cancelled
        // market remainder.
        assertEquals(submittedQty, filledUnitsBothSides + restingQty + cancelledMarketQty);
    }

    @Test
    @DisplayName("Book never reports a crossed market (best bid < best ask) at rest")
    void bookIsNeverCrossedAtRest() {
        MatchingEngine engine = new MatchingEngine();
        Random rng = new Random(11);

        for (int i = 0; i < 20_000; i++) {
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + rng.nextInt(50);
            if (rng.nextInt(100) < 15) {
                engine.submitMarket(side, qty);
            } else {
                long price = 1000 + rng.nextInt(21) - 10;
                engine.submitLimit(side, price, qty);
            }

            if (engine.book().bestBid().isPresent() && engine.book().bestAsk().isPresent()) {
                assertTrue(engine.book().bestBid().getAsLong() < engine.book().bestAsk().getAsLong(),
                        "a resting book must not be crossed");
            }
        }
    }
}
