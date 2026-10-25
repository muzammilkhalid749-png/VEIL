package com.m3.veil;

import com.m3.veil.manager.VeilMessagesController;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Production Unit Tests for Veil Vector Clocks & Lamport Monotonic Ordering (#204).
 * Verifies distributed causality tracking, peer tick progression, monotonic clock updates,
 * and multi-node event serialization without central timestamps.
 */
public class VeilVectorClockTest {

    @Test
    public void testVectorClock_tickAndGet() {
        VeilMessagesController.VectorClock clock = new VeilMessagesController.VectorClock();

        assertEquals("Uninitialized peer clock should default to 0", 0L, clock.get("peer_alice"));

        clock.tick("peer_alice");
        assertEquals(1L, clock.get("peer_alice"));

        clock.tick("peer_alice");
        clock.tick("peer_alice");
        assertEquals(3L, clock.get("peer_alice"));

        // Independent peer
        assertEquals(0L, clock.get("peer_bob"));
        clock.tick("peer_bob");
        assertEquals(1L, clock.get("peer_bob"));
        assertEquals(3L, clock.get("peer_alice"));
    }

    @Test
    public void testVectorClock_monotonicUpdate() {
        VeilMessagesController.VectorClock clock = new VeilMessagesController.VectorClock();

        clock.update("peer_carol", 100L);
        assertEquals(100L, clock.get("peer_carol"));

        // Higher timestamp updates
        clock.update("peer_carol", 150L);
        assertEquals(150L, clock.get("peer_carol"));

        // Stale or lower timestamp must NOT regress the clock
        clock.update("peer_carol", 80L);
        assertEquals("Monotonic vector clock must never regress on older timestamps", 150L, clock.get("peer_carol"));
    }

    @Test
    public void testLamportMonotonicClockProgression() {
        // Test Lamport logical clock synchronization algorithm
        AtomicLong localLamport = new AtomicLong(1000L);

        // Simulate observing a remote message timestamp from peer with higher clock
        long remoteClock = 1500L;
        long current;
        do {
            current = localLamport.get();
            if (remoteClock <= current) break;
        } while (!localLamport.compareAndSet(current, remoteClock + 1));

        assertEquals("Local Lamport clock should jump to remote + 1", 1501L, localLamport.get());

        // Simulate observing an older remote message timestamp
        long olderRemote = 1200L;
        do {
            current = localLamport.get();
            if (olderRemote <= current) break;
        } while (!localLamport.compareAndSet(current, olderRemote + 1));

        assertEquals("Local Lamport clock must remain at 1501 when observing older remote timestamp", 1501L, localLamport.get());
    }

    @Test
    public void testMultiPeerDistributedCausalityOrdering() {
        VeilMessagesController.VectorClock nodeA = new VeilMessagesController.VectorClock();
        VeilMessagesController.VectorClock nodeB = new VeilMessagesController.VectorClock();

        // Node A sends message 1
        nodeA.tick("nodeA");
        long nodeA_msg1 = nodeA.get("nodeA");

        // Node B receives message 1 from Node A
        nodeB.update("nodeA", nodeA_msg1);
        // Node B replies (sends message 2)
        nodeB.tick("nodeB");
        long nodeB_msg2 = nodeB.get("nodeB");

        // Verify causality: Node B knows about Node A's tick
        assertEquals(1L, nodeB.get("nodeA"));
        assertEquals(1L, nodeB_msg2);

        // Node A receives message 2 from Node B
        nodeA.update("nodeB", nodeB_msg2);
        assertEquals(1L, nodeA.get("nodeB"));
        assertEquals(1L, nodeA.get("nodeA"));
    }
}
