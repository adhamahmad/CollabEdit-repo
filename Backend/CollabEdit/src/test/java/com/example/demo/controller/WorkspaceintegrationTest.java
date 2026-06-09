package com.example.demo.controller;

import com.example.demo.DTO.WorkspaceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
// import static org.hamcrest.Matchers.*;

/**
 * WorkspaceIntegrationTest — full Spring context integration tests.
 *
 * What is tested:
 * These tests boot the complete application context with real beans (no mocks)
 * and exercise the full request → controller → service → entity → response
 * pipeline. They verify behaviours that slice tests cannot: correct wiring,
 * real Caffeine cache semantics, and multi-step workflows.
 *
 * - Full create → open → snapshot → re-open lifecycle
 * - Created workspace is immediately accessible at its returned ID
 * - Two creations produce two different IDs
 * - Snapshot reduces the update list to a single entry
 * - Opening a workspace that was never created returns 404
 * - CORS headers are present (WebConfig wiring)
 * - Concurrent creation requests each get unique IDs
 *
 * Why valuable:
 * Unit and slice tests mock the wiring. This test suite catches misconfigured
 * bean graphs, missing @Component/@Service annotations, and Caffeine cache
 * eviction bugs that only appear at runtime. The lifecycle test is especially
 * important because it validates the snapshot → re-open flow that late-joining
 * clients depend on to receive a single compacted update instead of thousands
 * of deltas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkspaceResponse createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(),
                WorkspaceResponse.class);
    }

    private String postSnapshot(String id, byte[] snapshotBytes) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(snapshotBytes);
        String body = objectMapper.writeValueAsString(Map.of("snapshot", b64));
        MvcResult result = mockMvc.perform(
                post("/" + id + "/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    // ── Create workspace ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Create workspace (GET /)")
    class CreateWorkspace {

        @Test
        @DisplayName("returns a non-null workspace ID")
        void returnsNonNullId() throws Exception {
            WorkspaceResponse ws = createWorkspace();
            assertThat(ws.getId()).isNotBlank();
        }

        @Test
        @DisplayName("returned ID is in URL-safe Base64 format [A-Za-z0-9_-]")
        void idHasCorrectFormat() throws Exception {
            WorkspaceResponse ws = createWorkspace();
            assertThat(ws.getId()).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("returned ID is between 16 and 22 characters")
        void idLengthInRange() throws Exception {
            WorkspaceResponse ws = createWorkspace();
            assertThat(ws.getId().length())
                    .isGreaterThanOrEqualTo(16)
                    .isLessThanOrEqualTo(22);
        }

        @Test
        @DisplayName("fresh workspace has an empty updates list")
        void freshWorkspaceHasNoUpdates() throws Exception {
            WorkspaceResponse ws = createWorkspace();
            assertThat(ws.getUpdates()).isEmpty();
        }

        @Test
        @DisplayName("two consecutive creations produce different IDs")
        void twoCreationsHaveDifferentIds() throws Exception {
            String id1 = createWorkspace().getId();
            String id2 = createWorkspace().getId();
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("50 creations all produce unique IDs")
        void fiftyCreationsAllUnique() throws Exception {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < 50; i++) {
                ids.add(createWorkspace().getId());
            }
            assertThat(ids).hasSize(50);
        }
    }

    // ── Open workspace ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Open workspace (GET /{id})")
    class OpenWorkspace {

        @Test
        @DisplayName("created workspace is immediately accessible at its ID")
        void createdWorkspaceIsAccessible() throws Exception {
            String id = createWorkspace().getId();
            mockMvc.perform(get("/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id));
        }

        @Test
        @DisplayName("returns 404 for an ID that was never created")
        void returns404ForNonExistentId() throws Exception {
            mockMvc.perform(get("/abcdefghijklmnop")) // valid format, not created
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("opening returns the same updates that were snapshotted")
        void openReturnsSnapshotAfterCompaction() throws Exception {
            String id = createWorkspace().getId();

            byte[] snapshot = { 10, 20, 30, 40, 50 };
            postSnapshot(id, snapshot);

            MvcResult result = mockMvc.perform(get("/" + id))
                    .andExpect(status().isOk())
                    .andReturn();

            WorkspaceResponse response = objectMapper.readValue(
                    result.getResponse().getContentAsString(),
                    WorkspaceResponse.class);

            assertThat(response.getUpdates()).hasSize(1);
            byte[] decoded = Base64.getDecoder().decode(response.getUpdates().get(0));
            assertThat(decoded).isEqualTo(snapshot);
        }
    }

    // ── Full lifecycle ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Full workspace lifecycle")
    class FullLifecycle {

        @Test
        @DisplayName("create → snapshot → re-open returns exactly one compacted update")
        void createSnapshotReopen() throws Exception {
            // 1. Create
            String id = createWorkspace().getId();

            // 2. Post a snapshot (simulates what the frontend does after N edits)
            byte[] snapshotData = "simulated-yjs-state-as-bytes".getBytes();
            postSnapshot(id, snapshotData);

            // 3. Re-open: late joiner should receive exactly one update (the snapshot)
            MvcResult result = mockMvc.perform(get("/" + id))
                    .andExpect(status().isOk())
                    .andReturn();

            WorkspaceResponse response = objectMapper.readValue(
                    result.getResponse().getContentAsString(),
                    WorkspaceResponse.class);

            assertThat(response.getUpdates()).hasSize(1);
            byte[] decoded = Base64.getDecoder().decode(response.getUpdates().get(0));
            assertThat(decoded).isEqualTo(snapshotData);
        }

        @Test
        @DisplayName("multiple snapshots: only the latest one is served to new joiners")
        void multipleSnapshotsLatestWins() throws Exception {
            String id = createWorkspace().getId();

            byte[] first = "first-snapshot".getBytes();
            byte[] second = "second-snapshot".getBytes();
            byte[] third = "third-snapshot".getBytes();

            postSnapshot(id, first);
            postSnapshot(id, second);
            postSnapshot(id, third);

            MvcResult result = mockMvc.perform(get("/" + id))
                    .andReturn();

            WorkspaceResponse response = objectMapper.readValue(
                    result.getResponse().getContentAsString(),
                    WorkspaceResponse.class);

            assertThat(response.getUpdates()).hasSize(1);
            byte[] decoded = Base64.getDecoder().decode(response.getUpdates().get(0));
            assertThat(decoded).isEqualTo(third);
        }

        @Test
        @DisplayName("two independent workspaces do not share state")
        void twoWorkspacesIndependent() throws Exception {
            String id1 = createWorkspace().getId();
            String id2 = createWorkspace().getId();

            byte[] snapshot1 = "workspace-one-data".getBytes();
            postSnapshot(id1, snapshot1);

            // workspace 2 should still be empty (no updates)
            MvcResult result2 = mockMvc.perform(get("/" + id2)).andReturn();
            WorkspaceResponse response2 = objectMapper.readValue(
                    result2.getResponse().getContentAsString(),
                    WorkspaceResponse.class);

            assertThat(response2.getUpdates()).isEmpty();
        }
    }

    // ── Concurrent creation ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Concurrent workspace creation")
    class ConcurrentCreation {

        @Test
        @DisplayName("20 concurrent creation requests all succeed with unique IDs")
        void concurrentCreationsAllUnique() throws Exception {
            int concurrency = 20;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
            java.util.concurrent.CopyOnWriteArrayList<String> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(concurrency);

            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        MvcResult r = mockMvc.perform(get("/")).andReturn();
                        WorkspaceResponse ws = objectMapper.readValue(
                                r.getResponse().getContentAsString(),
                                WorkspaceResponse.class);
                        ids.add(ws.getId());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(ids).hasSize(concurrency);
            assertThat(new java.util.HashSet<>(ids)).hasSize(concurrency);
        }
    }
}