# Matching Engine

[![CI](https://github.com/Barizio/matching-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/Barizio/matching-engine/actions/workflows/ci.yml)

A production-quality **limit order-book matching engine** in Java 21, implementing
strict **price-time priority** (FIFO within each price level) for a single
instrument. It supports limit and market orders on both sides, partial fills,
O(1) cancel-by-id, and live top-of-book / depth queries.

This is a portfolio project: the goal is correctness, clarity, and the kind of
data-structure choices you would defend in an exchange or low-latency trading
context.

---

## What it does

- Accepts **LIMIT** and **MARKET** orders on the **BUY** and **SELL** sides.
- Matches an incoming order against the opposite side of the book while prices
  cross, emitting `Trade` events (price, quantity, aggressor id, resting id,
  side, timestamp).
- Enforces **price priority** (best price trades first) and, within a price,
  **time priority** (first-in, first-out).
- **Partial fills:** leftover *limit* quantity rests on the book; leftover
  *market* quantity is cancelled.
- **Cancel** any resting order by id in O(1).
- Reports **best bid / best ask / spread** and aggregated **depth**.

## Design decisions

### Prices as integer ticks (`long`), never `double`
A *tick* is the smallest price increment of the instrument. Prices are stored as
`long` tick counts (e.g. with a $0.01 tick, `$100.25` is `10025`). Floating-point
prices are unacceptable in a matching engine: `0.1 + 0.2 != 0.3` in IEEE-754, so
equality and crossing comparisons become unreliable and fills can be off by a
cent. Integer arithmetic makes crossing (`bidPrice >= askPrice`) exact,
deterministic, and fast.

### `TreeMap<Long, PriceLevel>` per side, with a FIFO queue per level
Each side of the book is a `TreeMap` keyed by price:

- **Bids** use `Collections.reverseOrder()` so the **highest** bid is `firstKey()`.
- **Asks** use natural order so the **lowest** ask is `firstKey()`.

A red-black `TreeMap` gives:

- **O(log P)** access to the best price (`firstEntry`), where `P` is the number
  of distinct price levels;
- **ordered traversal** for market-order sweeps across levels;
- **O(log P)** insertion / removal of a price level.

An alternative — an array indexed by price — offers O(1) best-price access but
wastes memory across wide price ranges and handles sparse or unbounded prices
poorly. The `TreeMap` is the pragmatic, robust choice; `P` is small in practice.

Each price maps to a **`PriceLevel`**: a FIFO queue of resting orders. Time
priority is simply insertion order in that queue.

### Intrusive doubly-linked list for O(1) cancel
`PriceLevel` is not backed by `ArrayDeque` (whose arbitrary `remove` is O(n)).
Instead each `Order` *is* a node in a doubly-linked list — it carries `prev`/`next`
links. Combined with a `HashMap<Long, Order>` from order id to order, this makes:

- resting an order **O(1)** (append to tail),
- filling from the front **O(1)** (unlink head),
- **cancelling an arbitrary order O(1)** (look up in the map, unlink the node).

This is the key structural decision that delivers the required O(1) cancel.

### Trades print at the resting price
When an aggressor crosses the spread, the trade executes at the **resting**
(passive) order's price — the standard continuous-matching rule. The passive
order was there first and set the price, so the aggressor receives any price
improvement. A buy limit at 105 hitting a resting ask at 100 trades at **100**.

### Deterministic logical clock
`MatchingEngine` assigns each order a unique id and a monotonically increasing
logical timestamp (a sequence number) rather than wall-clock time. Matching is
therefore fully deterministic and reproducible, which matters for testing and
event-stream replay. (Time priority itself does not depend on the timestamp
field — it is enforced by FIFO queue order — but the timestamp is carried on
trades for auditing.)

## Complexity summary

Let `P` = number of distinct price levels on a side, `k` = price levels a single
order sweeps through, `m` = resting orders it fully consumes.

| Operation                         | Cost |
|-----------------------------------|------|
| Submit, resting (no cross)        | O(log P) |
| Submit, matching                  | O(m + k·log P) — one unlink per consumed order, one tree removal per emptied level |
| Cancel by id                      | O(1) to unlink; O(log P) only if the level becomes empty and is removed from the tree |
| Best bid / best ask / spread      | O(1) (TreeMap caches `firstKey`) |
| Quantity at a price               | O(log P) |
| Depth (top N levels)              | O(N) |

## Project structure

```
src/main/java/com/adebare/matchingengine/
  Side.java            BUY / SELL
  OrderType.java       LIMIT / MARKET
  Order.java           order + intrusive linked-list node (integer-tick price)
  Trade.java           immutable execution record
  PriceLevel.java      FIFO queue of orders at one price (O(1) removal)
  OrderBook.java       the book: two TreeMaps + id index, matching logic
  MatchingEngine.java  facade: id/timestamp assignment, SubmitResult
  Main.java            scripted demo
  Benchmark.java       throughput + latency-percentile harness

src/test/java/com/adebare/matchingengine/
  OrderBookTest.java       price-time priority, partial fills, market sweeps,
                           cancels, no-match resting, crossing edge cases, depth
  MatchingEngineTest.java  id/timestamp assignment, SubmitResult summary
  PriceLevelTest.java      FIFO + O(1) arbitrary removal
  OrderTest.java           validation + fill bookkeeping
  TradeTest.java           validation + notional
  ScenarioTest.java        end-to-end session + randomized invariants
                           (quantity conservation, book never crossed at rest)
```

## Build, run, test

Requires **JDK 21**. Maven is optional: the repo ships a **Maven wrapper**
(`./mvnw`, or `mvnw.cmd` on Windows) that downloads the pinned Maven version on
first use, so you can build without installing Maven yourself. Substitute `mvn`
for `./mvnw` below if you have Maven installed.

```bash
# Run the full test suite
./mvnw test

# Build the jar
mvn -q package

# Run the scripted demo
java -cp target/matching-engine.jar com.adebare.matchingengine.Main

# Run the benchmark (default 1,000,000 measured operations; pass N to change)
java -cp target/matching-engine.jar com.adebare.matchingengine.Benchmark
java -cp target/matching-engine.jar com.adebare.matchingengine.Benchmark 5000000
```

## Benchmark

`Benchmark` replays a deterministic (seeded) stream of random orders around a
fixed mid-price, producing a realistic mix of crossing, resting, and cancels. It
warms up the JIT before measuring, then reports aggregate throughput and
per-operation latency percentiles.

> **Reading the numbers:** aggregate throughput is timed once around the whole
> run and is the reliable figure. The per-operation percentiles are measured with
> `System.nanoTime()` around each call, whose own overhead (tens of ns) is
> significant relative to a single match, so treat them as an upper bound.

Measured on the development machine (`java -cp target/matching-engine.jar
com.adebare.matchingengine.Benchmark`, 1,000,000 ops):

```
Matching engine benchmark
  warmup ops   : 200000
  measured ops : 1000000

Elapsed        : 0.705 s
Throughput     : 1,418,170 ops/sec

Per-operation latency (includes nanoTime overhead):
  p50          : 200 ns
  p90          : 700 ns
  p99          : 2,600 ns
  p99.9        : 8,000 ns
  max          : 9,529,500 ns   (a single GC / JIT pause on a warm run)
  mean         : 520.9 ns

Final book     : 77,547 resting orders, 21 bid levels, 17 ask levels
```

So ~**1.4 million orders/second** end-to-end, with a **p50 of ~200 ns** and
**p99 of ~2.6 µs** per operation — on a modest 2-core laptop. Faster hardware and
a larger heap (to reduce GC pauses on the resting book) would push this
materially higher; the point here is that the data-structure choices keep every
operation logarithmic-or-better rather than to chase a hardware record.

Environment: Intel Core i7-5600U (2 cores / 4 threads @ 2.6 GHz), Windows 10,
Temurin JDK 21.0.11, default heap. Numbers vary run-to-run and with hardware.

## Testing approach

The suite asserts behaviour, not implementation: exact price-time priority, that
trades print at the resting price, partial-fill remainders (rest vs. cancel),
market sweeps across levels, O(1) cancel semantics (including cancel-of-filled
and cancel-then-no-match), crossing edge cases (exact touch, one tick apart,
sweep-then-rest), and depth aggregation. `ScenarioTest` adds randomized
invariants over tens of thousands of orders: **quantity is conserved** (every
submitted unit is either resting or matched exactly once) and the **resting book
is never crossed**.

## Non-goals / possible extensions

Single instrument, single thread (an exchange shards one book per matching
thread). Not implemented here but natural next steps: iceberg / hidden orders,
IOC / FOK / post-only time-in-force, self-trade prevention, an event/replay log,
and a multi-symbol registry.

## License

MIT.
