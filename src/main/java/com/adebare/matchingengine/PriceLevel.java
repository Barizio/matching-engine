package com.adebare.matchingengine;

/**
 * A single price level in the order book: a FIFO queue of resting orders that
 * all share the same price.
 *
 * <p>The queue is implemented as an intrusive doubly-linked list whose nodes are
 * the {@link Order} objects themselves. This gives:
 * <ul>
 *   <li>O(1) append to the tail when an order rests (preserving time priority);</li>
 *   <li>O(1) removal from the head as orders are filled;</li>
 *   <li>O(1) removal of an arbitrary order by reference, which is what lets the
 *       book cancel by id in O(1) once the order has been located in a map.</li>
 * </ul>
 *
 * <p>A running {@link #totalQuantity()} is maintained incrementally so that book
 * depth can be reported without scanning the queue.
 */
final class PriceLevel {

    private final long price;

    private Order head;
    private Order tail;
    private int orderCount;
    private long totalQuantity;

    PriceLevel(long price) {
        this.price = price;
    }

    long price() {
        return price;
    }

    boolean isEmpty() {
        return head == null;
    }

    int orderCount() {
        return orderCount;
    }

    /** Aggregate open quantity resting at this level. */
    long totalQuantity() {
        return totalQuantity;
    }

    /** Returns the order at the front of the FIFO queue, or {@code null} if empty. */
    Order peek() {
        return head;
    }

    /** Appends an order to the tail of the queue, preserving time priority. */
    void add(Order order) {
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        orderCount++;
        totalQuantity += order.remainingQuantity();
    }

    /** Unlinks {@code order} from the queue in O(1). The order must belong to this level. */
    void remove(Order order) {
        Order p = order.prev;
        Order n = order.next;
        if (p == null) {
            head = n;
        } else {
            p.next = n;
        }
        if (n == null) {
            tail = p;
        } else {
            n.prev = p;
        }
        order.prev = null;
        order.next = null;
        orderCount--;
        totalQuantity -= order.remainingQuantity();
    }

    /**
     * Records that {@code quantity} of the given order (which must be resting at
     * this level) has been filled, keeping {@link #totalQuantity()} consistent.
     * Does not unlink the order even if it becomes fully filled; the caller is
     * responsible for calling {@link #remove(Order)} in that case.
     */
    void reduce(Order order, long quantity) {
        order.reduce(quantity);
        totalQuantity -= quantity;
    }
}
