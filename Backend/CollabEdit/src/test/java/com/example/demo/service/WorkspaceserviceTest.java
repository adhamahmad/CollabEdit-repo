package com.example.demo.service;

import com.example.demo.controller.exceptions.WorkspaceNotFoundException;
import com.example.demo.entity.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WorkspaceServiceTest — service-layer unit tests.
 *
 * What is tested:
 *   - createWorkspace: delegates to generator, stores result, returns it
 *   - getWorkspace: returns workspace when present, null when absent
 *   - removeWorkspace: evicts from cache so subsequent get returns null
 *   - applyUpdate: delegates to workspace.addUpdate, returns updated count,
 *                  throws WorkspaceNotFoundException for unknown IDs
 *   - compact: delegates to workspace.compact,
 *              throws WorkspaceNotFoundException for unknown IDs
 *
 * Why valuable:
 *   The service is the transaction boundary for all workspace mutations.
 *   Verifying that it correctly throws WorkspaceNotFoundException (which
 *   maps to HTTP 404) vs. silently ignoring unknown IDs is critical — a
 *   silent NullPointerException would return a 500 instead of 404 and
 *   break the frontend's redirect-to-home logic.
 *
 * Design note:
 *   WorkspaceService builds its Caffeine cache inline at field initialisation,
 *   making it impossible to inject a mock cache. We therefore test the service
 *   through its public API and verify observable side-effects (return values,
 *   exceptions, subsequent getWorkspace calls) rather than asserting on cache
 *   internals. This is intentionally black-box: if the cache implementation
 *   changes the contract tests still hold.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private PublicUrlGenerator publicUrlGenerator;

    @InjectMocks
    private WorkspaceService workspaceService;

    // A fixed ID returned by the mock generator for deterministic tests
    private static final String FIXED_ID = "abcdefghijklmnop"; // 16 chars, valid format

    // ── createWorkspace ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("createWorkspace")
    class CreateWorkspace {

        @Test
        @DisplayName("returns a non-null Workspace")
        void returnsWorkspace() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            assertThat(workspaceService.createWorkspace()).isNotNull();
        }

        @Test
        @DisplayName("returned workspace has the ID from the generator")
        void idFromGenerator() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            Workspace ws = workspaceService.createWorkspace();
            assertThat(ws.getId()).isEqualTo(FIXED_ID);
        }

        @Test
        @DisplayName("returned workspace starts with zero updates")
        void startsWithNoUpdates() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            Workspace ws = workspaceService.createWorkspace();
            assertThat(ws.getUpdates()).isEmpty();
        }

        @Test
        @DisplayName("calls publicUrlGenerator.generate() exactly once per creation")
        void generatorCalledOnce() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            verify(publicUrlGenerator, times(1)).generate();
        }

        @Test
        @DisplayName("created workspace is immediately retrievable by ID")
        void immediatelyRetrievable() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            Workspace created = workspaceService.createWorkspace();
            Workspace fetched = workspaceService.getWorkspace(FIXED_ID);
            assertThat(fetched).isSameAs(created);
        }

        @Test
        @DisplayName("two creations with different IDs both stored independently")
        void twoWorkspacesStoredIndependently() {
            when(publicUrlGenerator.generate())
                    .thenReturn("aaaaaaaaaaaaaaaa")
                    .thenReturn("bbbbbbbbbbbbbbbb");

            Workspace ws1 = workspaceService.createWorkspace();
            Workspace ws2 = workspaceService.createWorkspace();

            assertThat(workspaceService.getWorkspace("aaaaaaaaaaaaaaaa")).isSameAs(ws1);
            assertThat(workspaceService.getWorkspace("bbbbbbbbbbbbbbbb")).isSameAs(ws2);
        }
    }

    // ── getWorkspace ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getWorkspace")
    class GetWorkspace {

        @Test
        @DisplayName("returns the workspace for a known ID")
        void returnsKnownWorkspace() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            Workspace created = workspaceService.createWorkspace();
            assertThat(workspaceService.getWorkspace(FIXED_ID)).isSameAs(created);
        }

        @Test
        @DisplayName("returns null for an unknown ID")
        void returnsNullForUnknown() {
            assertThat(workspaceService.getWorkspace("nonexistent-id-123")).isNull();
        }

        @Test
        @DisplayName("returns null for empty string ID")
        void returnsNullForEmptyString() {
            assertThat(workspaceService.getWorkspace("")).isNull();
        }

        @Test
        @DisplayName("repeated gets return the same instance (no re-creation)")
        void repeatedGetSameInstance() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            Workspace first  = workspaceService.getWorkspace(FIXED_ID);
            Workspace second = workspaceService.getWorkspace(FIXED_ID);
            assertThat(first).isSameAs(second);
        }
    }

    // ── removeWorkspace ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeWorkspace")
    class RemoveWorkspace {

        @Test
        @DisplayName("workspace is not found after removal")
        void notFoundAfterRemoval() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            workspaceService.removeWorkspace(FIXED_ID);
            assertThat(workspaceService.getWorkspace(FIXED_ID)).isNull();
        }

        @Test
        @DisplayName("removing a non-existent workspace does not throw")
        void removeNonExistentDoesNotThrow() {
            assertThatCode(() -> workspaceService.removeWorkspace("no-such-id"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("removing one workspace does not affect another")
        void removeOnePreservesOther() {
            when(publicUrlGenerator.generate())
                    .thenReturn("aaaaaaaaaaaaaaaa")
                    .thenReturn("bbbbbbbbbbbbbbbb");

            workspaceService.createWorkspace(); // id = aaaa...
            Workspace ws2 = workspaceService.createWorkspace(); // id = bbbb...

            workspaceService.removeWorkspace("aaaaaaaaaaaaaaaa");

            assertThat(workspaceService.getWorkspace("bbbbbbbbbbbbbbbb")).isSameAs(ws2);
        }
    }

    // ── applyUpdate ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("applyUpdate")
    class ApplyUpdate {

        @Test
        @DisplayName("returns 1 after the first update on a fresh workspace")
        void returnsOneAfterFirstUpdate() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            int count = workspaceService.applyUpdate(FIXED_ID, new byte[]{1, 2, 3});
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("count increments with each successive update")
        void countIncrementsSuccessively() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            workspaceService.applyUpdate(FIXED_ID, new byte[]{1});
            workspaceService.applyUpdate(FIXED_ID, new byte[]{2});
            int count = workspaceService.applyUpdate(FIXED_ID, new byte[]{3});
            assertThat(count).isEqualTo(3);
        }

        @Test
        @DisplayName("update bytes are persisted in the workspace")
        void updateBytesPersisted() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            byte[] payload = {10, 20, 30};
            workspaceService.applyUpdate(FIXED_ID, payload);

            Workspace ws = workspaceService.getWorkspace(FIXED_ID);
            assertThat(ws.getUpdates()).hasSize(1);
            assertThat(ws.getUpdates().get(0)).isEqualTo(payload);
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException for unknown workspace ID")
        void throwsForUnknownId() {
            assertThatThrownBy(() ->
                    workspaceService.applyUpdate("unknown-workspace-id", new byte[]{1}))
                    .isInstanceOf(WorkspaceNotFoundException.class)
                    .hasMessageContaining("unknown-workspace-id");
        }

        @Test
        @DisplayName("exception message contains the missing workspace ID")
        void exceptionMessageContainsId() {
            String missingId = "missing-workspace-xyz";
            assertThatThrownBy(() -> workspaceService.applyUpdate(missingId, new byte[]{1}))
                    .hasMessageContaining(missingId);
        }

        @Test
        @DisplayName("accepts an empty byte array without throwing")
        void acceptsEmptyPayload() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            assertThatCode(() -> workspaceService.applyUpdate(FIXED_ID, new byte[0]))
                    .doesNotThrowAnyException();
        }
    }

    // ── compact ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("compact")
    class CompactTests {

        @Test
        @DisplayName("compact replaces the update log with a single snapshot entry")
        void replacesLogWithSnapshot() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            workspaceService.applyUpdate(FIXED_ID, new byte[]{1});
            workspaceService.applyUpdate(FIXED_ID, new byte[]{2});

            byte[] snapshot = {99, 100, 101};
            workspaceService.compact(FIXED_ID, snapshot);

            Workspace ws = workspaceService.getWorkspace(FIXED_ID);
            assertThat(ws.getUpdates()).hasSize(1);
            assertThat(ws.getUpdates().get(0)).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("compact resets the updateCount to 1")
        void resetsUpdateCount() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            workspaceService.applyUpdate(FIXED_ID, new byte[]{1});
            workspaceService.compact(FIXED_ID, new byte[]{99});

            Workspace ws = workspaceService.getWorkspace(FIXED_ID);
            assertThat(ws.getUpdateCount()).isOne();
        }

        @Test
        @DisplayName("applyUpdate after compact counts from 1")
        void applyUpdateAfterCompactCountsFromOne() {
            when(publicUrlGenerator.generate()).thenReturn(FIXED_ID);
            workspaceService.createWorkspace();
            workspaceService.applyUpdate(FIXED_ID, new byte[]{1});
            workspaceService.applyUpdate(FIXED_ID, new byte[]{2});
            workspaceService.compact(FIXED_ID, new byte[]{99});

            int count = workspaceService.applyUpdate(FIXED_ID, new byte[]{3});
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException for unknown workspace ID")
        void throwsForUnknownId() {
            assertThatThrownBy(() ->
                    workspaceService.compact("no-such-workspace", new byte[]{99}))
                    .isInstanceOf(WorkspaceNotFoundException.class)
                    .hasMessageContaining("no-such-workspace");
        }
    }
}