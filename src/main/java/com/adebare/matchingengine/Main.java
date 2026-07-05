package com.adebare.matchingengine;

import java.util.List;

/**
 * A scripted demonstration of the matching engine.
 *
 * <p>Run with {@code mvn -q exec:java} is not configured to avoid extra
 * dependencies; instead run the compiled class directly, e.g.
 * <pre>{@code
 *   mvn -q package
 *   java -cp target/matching-engine.jar com.adebare.matchingengine.Main
 * }</pre>
 *
 * <p>The script walks through resting liquidity, a partial fill, a market-order
 * sweep across multiple price levels, a cancel, and price-time priority, printing
 * the trades and the book state after each step. Prices are shown in ticks.
 */
public final class Main {

    public static void main(String[] args) {
        MatchingEngine engine = new MatchingEngine();

        header("1. Seed the book with resting liquidity");
        // Asks (sell side). Two orders rest at 102 (ids 2 and 3) for the FIFO demo below.
        print("SELL 5 @ 101", engine.submitLimit(Side.SELL, 101, 5));   // id 1
        print("SELL 3 @ 102", engine.submitLimit(Side.SELL, 102, 3));   // id 2
        print("SELL 4 @ 102", engine.submitLimit(Side.SELL, 102, 4));   // id 3
        // Bids (buy side), three levels.
        print("BUY  6 @ 99 ", engine.submitLimit(Side.BUY, 99, 6));     // id 4
        print("BUY  4 @ 98 ", engine.submitLimit(Side.BUY, 98, 4));     // id 5
        print("BUY  2 @ 97 ", engine.submitLimit(Side.BUY, 97, 2));     // id 6
        printBook(engine);

        header("2. Crossing limit buy takes price improvement (prints at resting 101)");
        // Buyer willing to pay 101 lifts the 101 ask (id 1) entirely; trade prints at 101.
        print("BUY  5 @ 101", engine.submitLimit(Side.BUY, 101, 5));    // id 7
        printBook(engine);

        header("3. Price-time priority within the 102 level (id 2 fills before id 3)");
        // The best ask is now 102, holding id 2 (3) ahead of id 3 (4). A market buy of 5
        // fills id 2 fully then id 3 partially — FIFO at a price level.
        print("MARKET BUY 5", engine.submitMarket(Side.BUY, 5));        // id 8
        printBook(engine);

        header("4. Partial fill: incoming sell rests after consuming part of a bid");
        // Sell 9 @ 99 hits the 6 @ 99 bid (id 4); 6 trade, and the leftover 3 rests as an
        // ask at 99.
        print("SELL 9 @ 99 ", engine.submitLimit(Side.SELL, 99, 9));    // id 9
        printBook(engine);

        header("5. Market sell sweeps across multiple bid levels, remainder cancelled");
        // Remaining bids are 4 @ 98 (id 5) and 2 @ 97 (id 6). A market sell of 100 takes
        // all 6 across both levels, then cancels the unfilled 94 (market orders never rest).
        print("MARKET SELL 100", engine.submitMarket(Side.SELL, 100));  // id 10
        printBook(engine);

        header("6. Cancel a resting order by id");
        MatchingEngine.SubmitResult rested = engine.submitLimit(Side.BUY, 95, 7); // id 11
        System.out.println("Placed resting BUY 7 @ 95 as order id " + rested.orderId());
        boolean cancelled = engine.cancel(rested.orderId());
        System.out.println("cancel(" + rested.orderId() + ") -> " + cancelled);
        System.out.println("book contains order " + rested.orderId() + " -> "
                + engine.book().contains(rested.orderId()));
        printBook(engine);
    }

    private static void print(String label, MatchingEngine.SubmitResult result) {
        System.out.printf("%-16s -> id=%d, filled=%d, remaining=%d%n",
                label, result.orderId(), result.filledQuantity(), result.remainingQuantity());
        for (Trade t : result.trades()) {
            System.out.printf("      TRADE %d @ %d  (aggressor #%d vs resting #%d)%n",
                    t.quantity(), t.price(), t.aggressorOrderId(), t.restingOrderId());
        }
    }

    private static void printBook(MatchingEngine engine) {
        OrderBook book = engine.book();
        System.out.println("  ---- Book ----");
        List<OrderBook.BookLevel> asks = book.depth(Side.SELL, 5);
        for (int i = asks.size() - 1; i >= 0; i--) {
            OrderBook.BookLevel l = asks.get(i);
            System.out.printf("   ASK %6d  x %4d  (%d orders)%n", l.price(), l.quantity(), l.orderCount());
        }
        System.out.println("   ----------------  spread: "
                + (book.spread().isPresent() ? book.spread().getAsLong() + " ticks" : "n/a"));
        for (OrderBook.BookLevel l : book.depth(Side.BUY, 5)) {
            System.out.printf("   BID %6d  x %4d  (%d orders)%n", l.price(), l.quantity(), l.orderCount());
        }
        System.out.println();
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    private Main() {
    }
}
