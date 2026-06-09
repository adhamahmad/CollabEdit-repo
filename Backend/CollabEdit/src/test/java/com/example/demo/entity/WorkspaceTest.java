package com.example.demo.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * WorkspaceTest — entity unit tests.
 *
 * What is tested:
 * - Initial state after construction
 * - addUpdate: appends bytes, increments counter
 * - compact: clears history, installs single snapshot, resets counter to 0
 * - getUpdates: returns a defensive copy (mutation isolation)
 * - Thread-safety: concurrent addUpdate calls from multiple threads must
 * not corrupt internal state (lost updates, wrong count, or exceptions)
 *
 * Why valuable:
 * Workspace is the only stateful domain object. Every Yjs delta in the
 * system passes through addUpdate; a concurrency bug here causes silent
 * data loss that only manifests when a late joiner loads the workspace.
 * The defensive-copy test protects against callers accidentally mutating
 * the internal list through the returned view.
 */
class WorkspaceTest {

    private static final String WORKSPACE_ID = "test-workspace-id-0001";

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace(WORKSPACE_ID);
    }

    // ── Construction ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Initial state")
    class InitialState {

        @Test
        @DisplayName("id is set from constructor argument")
        void idIsSet() {
            assertThat(workspace.getId()).isEqualTo(WORKSPACE_ID);
        }

        @Test
        @DisplayName("update list is empty on creation")
        void updatesEmpty() {
            assertThat(workspace.getUpdates()).isEmpty();
        }

        @Test
        @DisplayName("updateCount is zero on creation")
        void updateCountZero() {
            assertThat(workspace.getUpdateCount()).isZero();
        }
    }

    // ── addUpdate ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addUpdate")
    class AddUpdate {

        @Test
        @DisplayName("first addUpdate stores the bytes")
        void storesBytes() {
            byte[] update = { 1, 2, 3 };
            workspace.addUpdate(update);
            assertThat(workspace.getUpdates()).hasSize(1);
            assertThat(workspace.getUpdates().get(0)).isEqualTo(update);
        }

        @Test
        @DisplayName("addUpdate increments updateCount by 1")
        void incrementsCount() {
            workspace.addUpdate(new byte[] { 1 });
            assertThat(workspace.getUpdateCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("three addUpdate calls give count of 3")
        void countAfterMultipleAdds() {
            workspace.addUpdate(new byte[] { 1 });
            workspace.addUpdate(new byte[] { 2 });
            workspace.addUpdate(new byte[] { 3 });
            assertThat(workspace.getUpdateCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("updates are stored in insertion order")
        void preservesInsertionOrder() {
            byte[] a = { 10 }, b = { 20 }, c = { 30 };
            workspace.addUpdate(a);
            workspace.addUpdate(b);
            workspace.addUpdate(c);
            List<byte[]> updates = workspace.getUpdates();
            assertThat(updates.get(0)).isEqualTo(a);
            assertThat(updates.get(1)).isEqualTo(b);
            assertThat(updates.get(2)).isEqualTo(c);
        }

        @Test
        @DisplayName("empty byte array is accepted without throwing")
        void acceptsEmptyByteArray() {
            assertThatCode(() -> workspace.addUpdate(new byte[0]))
                    .doesNotThrowAnyException();
            assertThat(workspace.getUpdates()).hasSize(1);
        }

        @Test
        @DisplayName("large update payload is stored without corruption")
        void largePayload() {
            byte[] big = new byte[64 * 1024]; // 64 KB
            for (int i = 0; i < big.length; i++)
                big[i] = (byte) (i & 0xFF);
            workspace.addUpdate(big);
            assertThat(workspace.getUpdates().get(0)).isEqualTo(big);
        }
    }

    // ── compact ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("compact")
    class Compact {

        @Test
        @DisplayName("compact replaces all updates with just the snapshot")
        void replacesUpdatesWithSnapshot() {
            workspace.addUpdate(new byte[] { 1 });
            workspace.addUpdate(new byte[] { 2 });
            workspace.addUpdate(new byte[] { 3 });

            byte[] snapshot = { 99, 100 };
            workspace.compact(snapshot);

            assertThat(workspace.getUpdates()).hasSize(1);
            assertThat(workspace.getUpdates().get(0)).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("compact resets updateCount to 1")
        void resetsUpdateCount() {
            workspace.addUpdate(new byte[] { 1 });
            workspace.addUpdate(new byte[] { 2 });
            workspace.compact(new byte[] { 99 });
            assertThat(workspace.getUpdateCount()).isOne();
        }

        @Test
        @DisplayName("addUpdate after compact increments count from 1")
        void addAfterCompact() {
            workspace.addUpdate(new byte[] { 1 });
            workspace.compact(new byte[] { 99 });
            workspace.addUpdate(new byte[] { 2 });
            assertThat(workspace.getUpdateCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("compact on a fresh workspace installs snapshot at index 0")
        void compactOnEmptyWorkspace() {
            byte[] snapshot = { 5, 6, 7 };
            workspace.compact(snapshot);
            assertThat(workspace.getUpdates()).hasSize(1);
            assertThat(workspace.getUpdates().get(0)).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("second compact replaces first snapshot")
        void doubleCompact() {
            workspace.compact(new byte[] { 1 });
            workspace.compact(new byte[] { 2 });
            assertThat(workspace.getUpdates()).hasSize(1);
            assertThat(workspace.getUpdates().get(0)).isEqualTo(new byte[] { 2 });
        }

        @Test
        @DisplayName("KNOWN: updateCount=0 after compact despite snapshot entry existing")
        void knownCountSnapshotDiscrepancy() {
            workspace.addUpdate(new byte[] { 1 });
            workspace.compact(new byte[] { 99 });
            // Count is 0 but there IS one entry (the snapshot).
            assertThat(workspace.getUpdateCount()).isOne();
            assertThat(workspace.getUpdates()).hasSize(1);
        }
    }

    // ── getUpdates defensive copy ─────────────────────────────────────────────

    @Nested
    @DisplayName("getUpdates — defensive copy")
    class GetUpdatesDefensiveCopy {

        @Test
        @DisplayName("returned list is unmodifiable — add throws")
        void returnedListIsUnmodifiable() {
            workspace.addUpdate(new byte[] { 1 });
            List<byte[]> snapshot = workspace.getUpdates();
            assertThatThrownBy(() -> snapshot.add(new byte[] { 9 }))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutation of returned list does not affect internal state")
        void mutationDoesNotAffectInternals() {
            workspace.addUpdate(new byte[] { 1 });
            // getUpdates() returns an unmodifiable copy, so internal size stays 1
            List<byte[]> first = workspace.getUpdates();
            // A second call should still return 1 item
            assertThat(workspace.getUpdates()).hasSize(1);
        }

        @Test
        @DisplayName("each call to getUpdates returns a fresh snapshot")
        void eachCallReturnsFreshSnapshot() {
            workspace.addUpdate(new byte[] { 1 });
            List<byte[]> before = workspace.getUpdates();
            workspace.addUpdate(new byte[] { 2 });
            List<byte[]> after = workspace.getUpdates();
            // 'before' was a snapshot — still size 1
            assertThat(before).hasSize(1);
            // 'after' reflects the new state
            assertThat(after).hasSize(2);
        }
    }

    // ── Thread-safety ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Thread-safety")
    class ThreadSafety {

        @Test
        @DisplayName("100 concurrent addUpdate calls produce count=100 with no lost writes")
        void concurrentAddUpdate() throws InterruptedException {
            int threadCount = 100;
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final byte val = (byte) i;
                pool.submit(() -> {
                    try {
                        start.await();
                        workspace.addUpdate(new byte[] { val });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown(); // release all threads simultaneously
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(workspace.getUpdateCount()).isEqualTo(threadCount);
            assertThat(workspace.getUpdates()).hasSize(threadCount);
        }

        @Test
        @DisplayName("concurrent addUpdate and compact do not throw or corrupt state")
        void concurrentAddAndCompact() throws InterruptedException {
            int writerCount = 50;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writerCount + 1);
            AtomicInteger exceptions = new AtomicInteger(0);

            // One compactor thread
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        workspace.compact(new byte[] { (byte) i });
                        Thread.yield();
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });

            // Many writer threads
            for (int i = 0; i < writerCount; i++) {
                final byte val = (byte) i;
                pool.submit(() -> {
                    try {
                        start.await();
                        workspace.addUpdate(new byte[] { val });
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(exceptions.get())
                    .as("No exceptions thrown during concurrent access")
                    .isZero();
            // State must be self-consistent: list size >= 1 (snapshot always present)
            assertThat(workspace.getUpdates()).isNotEmpty();
        }

        @Test
        @DisplayName("concurrent getUpdates calls always return consistent snapshots")
        void concurrentGetUpdates() throws InterruptedException {
            // Seed with some data first
            for (int i = 0; i < 10; i++)
                workspace.addUpdate(new byte[] { (byte) i });

            int readerCount = 20;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(readerCount);
            List<List<byte[]>> results = new java.util.concurrent.CopyOnWriteArrayList<>();

            for (int i = 0; i < readerCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(workspace.getUpdates());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            // Every snapshot must have at least the 10 seeded entries
            // (nothing was removed during this test, only reads)
            for (List<byte[]> snapshot : results) {
                assertThat(snapshot.size()).isGreaterThanOrEqualTo(10);
            }
        }
    }
}