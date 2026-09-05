package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the gate that lets {@code drainCompletedEntries} skip its O(queue) walk. The walk is what
 * showed up in the profile, but skipping it must never strand a finished build in the queue, so the
 * cases below mirror the sequence the dispatch cycle actually performs.
 */
class ChunkDispatchDrainGateTest {

    private static final long GENERATION = 5L;
    private static final long TIMEOUT_NANOS = 5_000_000_000L;

    @Test
    void freshSessionAlwaysScansOnce() {
        PlayerSession session = readySession();

        assertTrue(ChunkDispatchService.needsDrainScan(session, GENERATION));
    }

    @Test
    void quietQueueIsNotRescanned() {
        PlayerSession session = disarmedSession();
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        enqueue(session, 1L, future);

        assertFalse(ChunkDispatchService.needsDrainScan(session, GENERATION));
        assertFalse(ChunkDispatchService.needsDrainScan(session, GENERATION, System.nanoTime(), TIMEOUT_NANOS));
    }

    @Test
    void completedBuildRearmsTheScan() {
        PlayerSession session = disarmedSession();
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        enqueue(session, 1L, future);
        // Same wiring the dispatch cycle installs after a successful enqueue.
        future.whenComplete((payload, throwable) -> session.markDrainDirty());
        assertFalse(ChunkDispatchService.needsDrainScan(session, GENERATION));

        future.complete(null);

        assertTrue(ChunkDispatchService.needsDrainScan(session, GENERATION));
    }

    @Test
    void alreadyCompletedBuildRearmsTheScanImmediately() {
        PlayerSession session = disarmedSession();
        CompletableFuture<ByteBuf> future = CompletableFuture.completedFuture(null);
        enqueue(session, 1L, future);
        future.whenComplete((payload, throwable) -> session.markDrainDirty());

        assertTrue(ChunkDispatchService.needsDrainScan(session, GENERATION));
    }

    @Test
    void cacheInvalidationRearmsTheScan() {
        PlayerSession session = disarmedSession();
        enqueue(session, 1L, new CompletableFuture<>());

        assertTrue(ChunkDispatchService.needsDrainScan(session, GENERATION + 1L));
    }

    @Test
    void stalledHeadEntryRearmsTheScanAfterTheBuildTimeout() {
        PlayerSession session = disarmedSession();
        ChunkSendQueueEntry entry = enqueue(session, 1L, new CompletableFuture<>());

        long justBeforeTimeout = entry.queuedAtNanos() + TIMEOUT_NANOS;
        assertFalse(ChunkDispatchService.needsDrainScan(session, GENERATION, justBeforeTimeout, TIMEOUT_NANOS));

        long pastTimeout = entry.queuedAtNanos() + TIMEOUT_NANOS + 1L;
        assertTrue(ChunkDispatchService.needsDrainScan(session, GENERATION, pastTimeout, TIMEOUT_NANOS));
    }

    /**
     * Replays the drain/enqueue/complete sequence the dispatch cycle actually performs, over many
     * ticks and with builds finishing out of order, because the gate is only safe if it holds
     * across the whole sequence: every finished build must still be drained, the O(1) counter must
     * stay equal to the real deque size, and the queue must end up empty.
     */
    @Test
    void repeatedDispatchCyclesNeverStrandAQueuedBuild() {
        int maxSendPerCycle = 6;
        int maxInflight = 9;
        int maxQueueSize = 24;
        PlayerSession session = readySession();
        Random random = new Random(20260905L);
        List<CompletableFuture<ByteBuf>> pending = new ArrayList<>();
        long chunkKey = 0L;
        int enqueued = 0;
        int drained = 0;

        for (int tick = 0; tick < 500; tick++) {
            int[] sent = {0};
            int[] removed = {0};
            boolean[] deferred = {false};
            int inFlight;

            if (!ChunkDispatchService.needsDrainScan(session, GENERATION)) {
                inFlight = session.chunkQueue().peekFirst() == null ? 0 : session.chunkQueueSize();
            } else {
                session.drainDirty(false);
                session.drainCacheGeneration(GENERATION);
                inFlight = session.drainQueue(entry -> {
                    if (!entry.buildFuture().isDone()) {
                        return false;
                    }
                    if (sent[0] >= maxSendPerCycle) {
                        deferred[0] = true;
                        return false;
                    }
                    sent[0]++;
                    removed[0]++;
                    return true;
                });
                if (deferred[0]) {
                    session.markDrainDirty();
                }
            }
            drained += removed[0];

            assertEquals(session.chunkQueue().size(), session.chunkQueueSize(), "counter drifted on tick " + tick);
            assertEquals(session.chunkQueue().size(), inFlight, "in-flight count wrong on tick " + tick);

            // Enqueue phase, only while there is head-room, exactly as processQueue admits chunks.
            int queueSize = inFlight;
            int admitted = 0;
            while (inFlight < maxInflight && queueSize < maxQueueSize && admitted < maxSendPerCycle && tick < 400) {
                CompletableFuture<ByteBuf> future = new CompletableFuture<>();
                ChunkSendQueueEntry entry = new ChunkSendQueueEntry(
                    ++chunkKey,
                    session.worldId(),
                    session.epoch(),
                    GENERATION,
                    future
                );
                assertTrue(session.enqueueChunk(entry, session.worldId(), session.epoch()));
                future.whenComplete((payload, throwable) -> session.markDrainDirty());
                pending.add(future);
                enqueued++;
                inFlight++;
                queueSize++;
                admitted++;
            }

            // Builds land asynchronously and out of order.
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (random.nextInt(6) == 0) {
                    pending.remove(i).complete(null);
                }
            }
        }

        for (CompletableFuture<ByteBuf> future : pending) {
            future.complete(null);
        }
        pending.clear();

        // A few more cycles must fully flush the queue.
        for (int tick = 0; tick < 32; tick++) {
            if (!ChunkDispatchService.needsDrainScan(session, GENERATION)) {
                continue;
            }
            session.drainDirty(false);
            session.drainCacheGeneration(GENERATION);
            int[] sent = {0};
            boolean[] deferred = {false};
            session.drainQueue(entry -> {
                if (!entry.buildFuture().isDone()) {
                    return false;
                }
                if (sent[0] >= maxSendPerCycle) {
                    deferred[0] = true;
                    return false;
                }
                sent[0]++;
                return true;
            });
            drained += sent[0];
            if (deferred[0]) {
                session.markDrainDirty();
            }
        }

        assertTrue(enqueued > 0);
        assertEquals(enqueued, drained, "a completed build was stranded in the queue");
        assertEquals(0, session.chunkQueue().size());
        assertEquals(0, session.chunkQueueSize());
    }

    private static ChunkSendQueueEntry enqueue(PlayerSession session, long chunkKey, CompletableFuture<ByteBuf> future) {
        ChunkSendQueueEntry entry = new ChunkSendQueueEntry(
            chunkKey,
            session.worldId(),
            session.epoch(),
            GENERATION,
            future
        );
        assertTrue(session.enqueueChunk(entry, session.worldId(), session.epoch()));
        return entry;
    }

    private static PlayerSession disarmedSession() {
        PlayerSession session = readySession();
        session.drainDirty(false);
        session.drainCacheGeneration(GENERATION);
        return session;
    }

    private static PlayerSession readySession() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
        session.setChunkPos(0, 0);
        session.updateDistance(3);
        session.enabled(true);
        return session;
    }
}
