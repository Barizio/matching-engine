package com.adebare.matchingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link OrderBook}, driving it with explicit order ids so
 * that price-time priority and fill order can be asserted precisely.
 */
class OrderBookTest {

    private final OrderBook book = new OrderBook();
    private final AtomicLong ids = new AtomicLong(1);
    private final AtomicLong clock = new AtomicLong(1);

    private Order limit(Side side, long price, long qty) {
        return new Order(ids.getAndIncrement(), side, OrderType.LIMIT, price, qty, clock.getAndIncrement());
    }

    private Order market(Side side, long qty) {
        return new Order(ids.getAndIncrement(), side, OrderType.MARKET, 0, qty, clock.getAndIncrement());
    }

    @Nested
    @DisplayName("Resting with no match")
    class Resting {

        @Test
        void nonCrossingLimitOrdersRestAndSetTopOfBook() {
            List<Trade> t1 = book.submit(limit(Side.BUY, 99, 10));
            List<Trade> t2 = book.submit(limit(Side.SELL, 101, 5));

            assertTrue(t1.isEmpty());
            assertTrue(t2.isEmpty());
            assertEquals(99, book.bestBid().getAsLong());
            assertEquals(101, book.bestAsk().getAsLong());
            assertEquals(2, book.spread().getAsLong());
            assertEquals(2, book.restingOrderCount());
        }

        @Test
        void bestBidIsHighestAndBestAskIsLowest() {
            book.submit(limit(Side.BUY, 98, 1));
            book.submit(limit(Side.BUY, 100, 1));
            book.submit(limit(Side.BUY, 99, 1));
            book.submit(limit(Side.SELL, 105, 1));
            book.submit(limit(Side.SELL, 103, 1));
            book.submit(limit(Side.SELL, 104, 1));

            assertEquals(100, book.bestBid().getAsLong());
            assertEquals(103, book.bestAsk().getAsLong());
        }

        @Test
        void emptyBookHasNoBestPricesOrSpread() {
            assertTrue(book.bestBid().isEmpty());
            assertTrue(book.bestAsk().isEmpty());
            assertTrue(book.spread().isEmpty());
        }
    }

    @Nested
    @DisplayName("Basic matching")
    class Matching {

        @Test
        void fullFillAtRestingPriceGivesAggressorPriceImprovement() {
            book.submit(limit(Side.SELL, 100, 10)); // resting ask, id 1
            List<Trade> trades = book.submit(limit(Side.BUY, 105, 10)); // aggressor willing to pay 105

            assertEquals(1, trades.size());
            Trade t = trades.get(0);
            assertEquals(100, t.price(), "trade prints at resting price, not aggressor's 105");
            assertEquals(10, t.quantity());
            assertEquals(2, t.aggressorOrderId());
            assertEquals(1, t.restingOrderId());
            assertEquals(Side.BUY, t.aggressorSide());
            assertEquals(0, book.restingOrderCount(), "both orders fully consumed");
            assertTrue(book.bestAsk().isEmpty());
        }

        @Test
        void exactPriceTouchCrosses() {
            book.submit(limit(Side.SELL, 100, 5));
            List<Trade> trades = book.submit(limit(Side.BUY, 100, 5));
            assertEquals(1, trades.size());
            assertEquals(100, trades.get(0).price());
        }

        @Test
        void oneTickApartDoesNotCross() {
            book.submit(limit(Side.SELL, 101, 5));
            List<Trade> trades = book.submit(limit(Side.BUY, 100, 5));
            assertTrue(trades.isEmpty());
            assertEquals(100, book.bestBid().getAsLong());
            assertEquals(101, book.bestAsk().getAsLong());
        }
    }

    @Nested
    @DisplayName("Partial fills")
    class PartialFills {

        @Test
        void incomingLargerThanRestingLeavesRemainderOnBook() {
            book.submit(limit(Side.SELL, 100, 4));
            List<Trade> trades = book.submit(limit(Side.BUY, 100, 10));

            assertEquals(1, trades.size());
            assertEquals(4, trades.get(0).quantity());
            // 6 remain and rest on the bid side.
            assertEquals(100, book.bestBid().getAsLong());
            assertEquals(6, book.quantityAt(Side.BUY, 100));
            assertTrue(book.bestAsk().isEmpty());
        }

        @Test
        void incomingSmallerThanRestingConsumesPartOfResting() {
            book.submit(limit(Side.SELL, 100, 10));
            List<Trade> trades = book.submit(limit(Side.BUY, 100, 3));

            assertEquals(1, trades.size());
            assertEquals(3, trades.get(0).quantity());
            // 7 of the ask remain resting.
            assertEquals(7, book.quantityAt(Side.SELL, 100));
            assertTrue(book.bestBid().isEmpty());
        }
    }

    @Nested
    @DisplayName("Price-time priority (FIFO within a level)")
    class PriceTimePriority {

        @Test
        void ordersAtSamePriceFillInArrivalOrder() {
            Order first = limit(Side.SELL, 100, 5);  // id 1, arrives first
            Order second = limit(Side.SELL, 100, 5); // id 2, arrives second
            book.submit(first);
            book.submit(second);

            List<Trade> trades = book.submit(limit(Side.BUY, 100, 6)); // id 3

            assertEquals(2, trades.size());
            assertEquals(first.id(), trades.get(0).restingOrderId(), "earliest order fills first");
            assertEquals(5, trades.get(0).quantity());
            assertEquals(second.id(), trades.get(1).restingOrderId());
            assertEquals(1, trades.get(1).quantity());
            assertEquals(4, book.quantityAt(Side.SELL, 100), "second order has 4 left");
        }

        @Test
        void betterPriceFillsBeforeWorsePriceRegardlessOfTime() {
            Order early = limit(Side.SELL, 102, 5);  // worse price, earlier
            Order late = limit(Side.SELL, 100, 5);   // better price, later
            book.submit(early);
            book.submit(late);

            List<Trade> trades = book.submit(limit(Side.BUY, 105, 5));

            assertEquals(1, trades.size());
            assertEquals(late.id(), trades.get(0).restingOrderId(), "best price wins over time");
            assertEquals(100, trades.get(0).price());
        }
    }

    @Nested
    @DisplayName("Market orders")
    class MarketOrders {

        @Test
        void marketBuySweepsMultipleAskLevels() {
            book.submit(limit(Side.SELL, 100, 3));
            book.submit(limit(Side.SELL, 101, 3));
            book.submit(limit(Side.SELL, 102, 3));

            List<Trade> trades = book.submit(market(Side.BUY, 7));

            assertEquals(3, trades.size());
            assertEquals(100, trades.get(0).price());
            assertEquals(3, trades.get(0).quantity());
            assertEquals(101, trades.get(1).price());
            assertEquals(3, trades.get(1).quantity());
            assertEquals(102, trades.get(2).price());
            assertEquals(1, trades.get(2).quantity());
            assertEquals(2, book.quantityAt(Side.SELL, 102), "1 of the 102 level consumed");
        }

        @Test
        void marketOrderRemainderIsCancelledNotRested() {
            book.submit(limit(Side.SELL, 100, 5));

            List<Trade> trades = book.submit(market(Side.BUY, 20));

            assertEquals(1, trades.size());
            assertEquals(5, trades.get(0).quantity());
            // The unfilled 15 does not rest; the bid side stays empty.
            assertTrue(book.bestBid().isEmpty());
            assertEquals(0, book.restingOrderCount());
        }

        @Test
        void marketOrderAgainstEmptyBookProducesNoTradesAndDoesNotRest() {
            List<Trade> trades = book.submit(market(Side.BUY, 10));
            assertTrue(trades.isEmpty());
            assertEquals(0, book.restingOrderCount());
        }
    }

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        @Test
        void cancelRemovesRestingOrderAndCollapsesEmptyLevel() {
            Order resting = limit(Side.BUY, 100, 10);
            book.submit(resting);
            assertTrue(book.contains(resting.id()));

            assertTrue(book.cancel(resting.id()));

            assertFalse(book.contains(resting.id()));
            assertTrue(book.bestBid().isEmpty());
            assertEquals(0, book.levelCount(Side.BUY));
        }

        @Test
        void cancelLeavesOtherOrdersAtSameLevelIntact() {
            Order a = limit(Side.BUY, 100, 5);
            Order b = limit(Side.BUY, 100, 7);
            book.submit(a);
            book.submit(b);

            assertTrue(book.cancel(a.id()));

            assertEquals(7, book.quantityAt(Side.BUY, 100));
            assertEquals(1, book.levelCount(Side.BUY));
            // Remaining order still matches correctly and in place.
            List<Trade> trades = book.submit(limit(Side.SELL, 100, 7));
            assertEquals(1, trades.size());
            assertEquals(b.id(), trades.get(0).restingOrderId());
        }

        @Test
        void cancelUnknownIdReturnsFalse() {
            assertFalse(book.cancel(999));
        }

        @Test
        void cancelAlreadyFilledOrderReturnsFalse() {
            Order resting = limit(Side.SELL, 100, 5);
            book.submit(resting);
            book.submit(limit(Side.BUY, 100, 5)); // fully fills it

            assertFalse(book.contains(resting.id()));
            assertFalse(book.cancel(resting.id()));
        }

        @Test
        void cancelledOrderNoLongerParticipatesInMatching() {
            Order resting = limit(Side.SELL, 100, 5);
            book.submit(resting);
            book.cancel(resting.id());

            List<Trade> trades = book.submit(limit(Side.BUY, 100, 5));
            assertTrue(trades.isEmpty(), "cancelled liquidity must not trade");
            assertEquals(100, book.bestBid().getAsLong());
        }
    }

    @Nested
    @DisplayName("Crossing-book edge cases")
    class CrossingEdges {

        @Test
        void aggressorSweepsThenRestsRemainderAtItsLimit() {
            book.submit(limit(Side.SELL, 100, 2));
            book.submit(limit(Side.SELL, 101, 2));

            // Buyer wants 10 @ 101: takes 2@100 and 2@101, remaining 6 rests at 101 bid.
            List<Trade> trades = book.submit(limit(Side.BUY, 101, 10));

            assertEquals(2, trades.size());
            assertEquals(101, book.bestBid().getAsLong());
            assertEquals(6, book.quantityAt(Side.BUY, 101));
            assertTrue(book.bestAsk().isEmpty());
        }

        @Test
        void limitStopsAtItsPriceLeavingBetterPricedRestingUntouched() {
            book.submit(limit(Side.SELL, 100, 2));
            book.submit(limit(Side.SELL, 103, 2)); // above the buyer's limit

            List<Trade> trades = book.submit(limit(Side.BUY, 100, 10));

            assertEquals(1, trades.size(), "only the 100 level is marketable");
            assertEquals(100, trades.get(0).price());
            assertEquals(8, book.quantityAt(Side.BUY, 100), "remainder rests at 100");
            assertEquals(103, book.bestAsk().getAsLong(), "103 ask untouched");
        }

        @Test
        void duplicateOrderIdRejected() {
            book.submit(new Order(7, Side.BUY, OrderType.LIMIT, 100, 5, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> book.submit(new Order(7, Side.SELL, OrderType.LIMIT, 200, 5, 2)));
        }
    }

    @Nested
    @DisplayName("Depth reporting")
    class Depth {

        @Test
        void depthReturnsAggregatedLevelsBestFirstUpToLimit() {
            book.submit(limit(Side.BUY, 100, 3));
            book.submit(limit(Side.BUY, 100, 2)); // same level aggregates to 5
            book.submit(limit(Side.BUY, 99, 4));
            book.submit(limit(Side.BUY, 98, 1));

            List<OrderBook.BookLevel> depth = book.depth(Side.BUY, 2);

            assertEquals(2, depth.size());
            assertEquals(100, depth.get(0).price());
            assertEquals(5, depth.get(0).quantity());
            assertEquals(2, depth.get(0).orderCount());
            assertEquals(99, depth.get(1).price());
            assertEquals(4, depth.get(1).quantity());
        }
    }
}
