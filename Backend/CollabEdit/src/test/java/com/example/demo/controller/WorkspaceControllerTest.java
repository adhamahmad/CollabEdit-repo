package com.example.demo.controller;

import com.example.demo.DTO.WorkspaceResponse;
import com.example.demo.controller.exceptions.WorkspaceNotFoundException;
import com.example.demo.entity.Workspace;
import com.example.demo.mapper.WorkspaceMapper;
import com.example.demo.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * WorkspaceControllerTest — MockMvc slice tests for WorkspaceController.
 *
 * What is tested:
 *   GET /
 *     - 200 with id and empty updates list on fresh creation
 *     - id field is non-null and non-empty
 *
 *   GET /{id}
 *     - 200 with correct body when workspace exists
 *     - 404 when workspace does not exist
 *     - Path regex: IDs shorter than 16 chars → 404 (no handler match)
 *     - Path regex: IDs longer than 22 chars → 404 (no handler match)
 *     - Path regex: IDs with illegal chars → 404 (no handler match)
 *     - IDs at the 16-char and 22-char boundaries are accepted
 *     - updates list is present and correctly base64-encoded in response
 *
 *   POST /{id}/snapshot
 *     - 200 on valid request with base64-encoded snapshot
 *     - snapshot bytes are decoded and forwarded to service correctly
 *     - 404 when workspace does not exist
 *     - 400 when request body is missing / malformed
 *
 * Why valuable:
 *   The path regex {id:[A-Za-z0-9_-]{16,22}} is a security and routing
 *   boundary. IDs outside this range must never reach the service layer.
 *   The snapshot endpoint decodes user-supplied Base64 — malformed input
 *   must return 4xx, not 500.
 *
 */
@WebMvcTest(WorkspaceController.class)
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private WorkspaceService workspaceService;

    @MockitoBean
    private WorkspaceMapper workspaceMapper;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final String VALID_ID_16 = "abcdefghijklmnop";   // exactly 16
    private static final String VALID_ID_22 = "abcdefghijklmnopqrstuv"; // exactly 22
    private static final String VALID_ID_18 = "abcdefghijklmnopqr";  // 18 — typical

    private Workspace stubWorkspace(String id) {
        Workspace ws = new Workspace(id);
        WorkspaceResponse response = new WorkspaceResponse(id, List.of());
        when(workspaceService.getWorkspace(id)).thenReturn(ws);
        when(workspaceMapper.toResponse(ws)).thenReturn(response);
        return ws;
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET / — auto-create workspace")
    class AutoCreate {

        @Test
        @DisplayName("returns 200")
        void returns200() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            when(workspaceService.createWorkspace()).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, List.of()));

            mockMvc.perform(get("/"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("response body contains an id field")
        void responseHasId() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            when(workspaceService.createWorkspace()).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, List.of()));

            mockMvc.perform(get("/"))
                    .andExpect(jsonPath("$.id").value(VALID_ID_18));
        }

        @Test
        @DisplayName("response body contains an updates array")
        void responseHasUpdatesArray() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            when(workspaceService.createWorkspace()).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, List.of()));

            mockMvc.perform(get("/"))
                    .andExpect(jsonPath("$.updates").isArray());
        }

        @Test
        @DisplayName("updates array is empty for a fresh workspace")
        void freshWorkspaceHasEmptyUpdates() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            when(workspaceService.createWorkspace()).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, List.of()));

            mockMvc.perform(get("/"))
                    .andExpect(jsonPath("$.updates", hasSize(0)));
        }

        @Test
        @DisplayName("content-type is application/json")
        void contentTypeIsJson() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            when(workspaceService.createWorkspace()).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, List.of()));

            mockMvc.perform(get("/"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("delegates to workspaceService.createWorkspace()")
        void delegatesToService() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            when(workspaceService.createWorkspace()).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, List.of()));

            mockMvc.perform(get("/"));
            verify(workspaceService, times(1)).createWorkspace();
        }
    }

    // ── GET /{id} ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{id} — open existing workspace")
    class OpenWorkspace {

        @Test
        @DisplayName("returns 200 for a known workspace")
        void returns200ForKnownId() throws Exception {
            stubWorkspace(VALID_ID_18);
            mockMvc.perform(get("/" + VALID_ID_18))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns the workspace id in the response body")
        void responseContainsId() throws Exception {
            stubWorkspace(VALID_ID_18);
            mockMvc.perform(get("/" + VALID_ID_18))
                    .andExpect(jsonPath("$.id").value(VALID_ID_18));
        }

        @Test
        @DisplayName("returns 404 when workspace does not exist")
        void returns404ForUnknownId() throws Exception {
            when(workspaceService.getWorkspace(VALID_ID_18))
                    .thenReturn(null);

            mockMvc.perform(get("/" + VALID_ID_18))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns encoded updates in the response body")
        void responseContainsUpdates() throws Exception {
            Workspace ws = new Workspace(VALID_ID_18);
            List<String> encoded = List.of("AQID", "BAUG");
            when(workspaceService.getWorkspace(VALID_ID_18)).thenReturn(ws);
            when(workspaceMapper.toResponse(ws))
                    .thenReturn(new WorkspaceResponse(VALID_ID_18, encoded));

            mockMvc.perform(get("/" + VALID_ID_18))
                    .andExpect(jsonPath("$.updates", hasSize(2)))
                    .andExpect(jsonPath("$.updates[0]").value("AQID"))
                    .andExpect(jsonPath("$.updates[1]").value("BAUG"));
        }

        // ── Path regex boundary tests ─────────────────────────────────────────

        @Test
        @DisplayName("accepts ID of exactly 16 characters (lower boundary)")
        void accepts16CharId() throws Exception {
            stubWorkspace(VALID_ID_16);
            mockMvc.perform(get("/" + VALID_ID_16))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("accepts ID of exactly 22 characters (upper boundary)")
        void accepts22CharId() throws Exception {
            stubWorkspace(VALID_ID_22);
            mockMvc.perform(get("/" + VALID_ID_22))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("rejects ID of 15 characters (below lower boundary)")
        void rejects15CharId() throws Exception {
            mockMvc.perform(get("/abcdefghijklmno")) // 15 chars
                    .andExpect(status().isNotFound());
            verify(workspaceService, never()).getWorkspace(any());
        }

        @Test
        @DisplayName("rejects ID of 23 characters (above upper boundary)")
        void rejects23CharId() throws Exception {
            mockMvc.perform(get("/abcdefghijklmnopqrstuvw")) // 23 chars
                    .andExpect(status().isNotFound());
            verify(workspaceService, never()).getWorkspace(any());
        }

        @Test
        @DisplayName("rejects ID containing a space (illegal character)")
        void rejectsIdWithSpace() throws Exception {
            mockMvc.perform(get("/abcdefghijklmno p"))
                    .andExpect(status().isNotFound());
            verify(workspaceService, never()).getWorkspace(any());
        }

        @Test
        @DisplayName("rejects ID containing a dot (illegal character)")
        void rejectsIdWithDot() throws Exception {
            mockMvc.perform(get("/abcdefghijklmno."))
                    .andExpect(status().isNotFound());
            verify(workspaceService, never()).getWorkspace(any());
        }

        @Test
        @DisplayName("accepts ID containing underscore (valid URL-safe Base64 char)")
        void acceptsIdWithUnderscore() throws Exception {
            String idWithUnderscore = "abcdefghijk_mnop"; // 16 chars with _
            stubWorkspace(idWithUnderscore);
            mockMvc.perform(get("/" + idWithUnderscore))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("accepts ID containing hyphen (valid URL-safe Base64 char)")
        void acceptsIdWithHyphen() throws Exception {
            String idWithHyphen = "abcdefghijk-mnop"; // 16 chars with -
            stubWorkspace(idWithHyphen);
            mockMvc.perform(get("/" + idWithHyphen))
                    .andExpect(status().isOk());
        }
    }

    // ── POST /{id}/snapshot ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /{id}/snapshot — save compacted snapshot")
    class SaveSnapshot {

        private String snapshotBody(String base64) throws Exception {
            return objectMapper.writeValueAsString(Map.of("snapshot", base64));
        }

        @Test
        @DisplayName("returns 200 for a valid snapshot request")
        void returns200ForValidRequest() throws Exception {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5});
            doNothing().when(workspaceService).compact(eq(VALID_ID_18), any());

            mockMvc.perform(post("/" + VALID_ID_18 + "/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(snapshotBody(b64)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("decodes Base64 and forwards raw bytes to workspaceService.compact()")
        void decodesAndForwardsBytes() throws Exception {
            byte[] originalBytes = {10, 20, 30, 40, 50};
            String b64 = Base64.getEncoder().encodeToString(originalBytes);
            doNothing().when(workspaceService).compact(eq(VALID_ID_18), any());

            mockMvc.perform(post("/" + VALID_ID_18 + "/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(snapshotBody(b64)))
                    .andExpect(status().isOk());

            verify(workspaceService).compact(eq(VALID_ID_18), eq(originalBytes));
        }

        @Test
        @DisplayName("returns 404 when workspace does not exist")
        void returns404WhenWorkspaceNotFound() throws Exception {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            doThrow(new WorkspaceNotFoundException(VALID_ID_18))
                    .when(workspaceService).compact(eq(VALID_ID_18), any());

            mockMvc.perform(post("/" + VALID_ID_18 + "/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(snapshotBody(b64)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 400 when request body is empty")
        void returns400ForEmptyBody() throws Exception {
            mockMvc.perform(post("/" + VALID_ID_18 + "/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().is4xxClientError());
        }


        
        @Test
        @DisplayName("invalid Base64 snapshot returns 400")
        void invalidBase64Returns400() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("snapshot", "THIS IS !!! NOT VALID BASE64 @@@"));

            mockMvc.perform(post("/" + VALID_ID_18 + "/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest()); 
        }

        @Test
        @DisplayName("accepts an empty snapshot (zero-byte compaction)")
        void acceptsEmptySnapshot() throws Exception {
            String b64 = Base64.getEncoder().encodeToString(new byte[0]);
            doNothing().when(workspaceService).compact(eq(VALID_ID_18), any());

            mockMvc.perform(post("/" + VALID_ID_18 + "/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(snapshotBody(b64)))
                    .andExpect(status().isOk());

            verify(workspaceService).compact(eq(VALID_ID_18), eq(new byte[0]));
        }

        @Test
        @DisplayName("snapshot path also enforces the {16,22} ID regex")
        void snapshotPathEnforcesIdRegex() throws Exception {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            String body = snapshotBody(b64);

            // ID too short (15 chars)
            mockMvc.perform(post("/abcdefghijklmno/snapshot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isNotFound());

            verify(workspaceService, never()).compact(any(), any());
        }
    }
}