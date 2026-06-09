package com.example.demo.controller;

import com.example.demo.controller.WorkSpaceWebSocketController.EditMessage;
import com.example.demo.controller.exceptions.WorkspaceNotFoundException;
import com.example.demo.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkSpaceWebSocketControllerTest — unit tests for the edit message handler.
 *
 * What is tested:
 *   - broadcastEdit decodes the Base64 update and passes raw bytes to service
 *   - broadcastEdit stamps the returned updateCount onto the message
 *   - The same message object is returned (broadcast back to /topic/doc/{id})
 *   - docId and update fields are preserved in the returned message
 *   - WorkspaceNotFoundException propagates when the workspace is missing
 *   - Empty update (empty Base64 string) is handled without throwing
 *   - Multi-byte, high-value bytes round-trip correctly through Base64
 *
 * Why valuable:
 *   broadcastEdit is the hot path for every keystroke in every open document.
 *   The Base64 decode step is the only transformation the backend performs on
 *   Yjs deltas — if it uses the wrong decoder (URL-safe vs standard) or fails
 *   to forward the raw bytes, every client diverges silently. The updateCount
 *   stamp controls when clients trigger compaction; an off-by-one here causes
 *   premature or missing snapshots.
 */
@ExtendWith(MockitoExtension.class)
class WorkSpaceWebSocketControllerTest {

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private WorkSpaceWebSocketController controller;

    private static final String WORKSPACE_ID = "test-workspace-abcd";

    private EditMessage makeMessage(String docId, String base64Update) {
        EditMessage msg = new EditMessage();
        msg.setDocId(docId);
        msg.setUpdate(base64Update);
        return msg;
    }

    // ── broadcastEdit — happy path ────────────────────────────────────────────

    @Nested
    @DisplayName("broadcastEdit — happy path")
    class BroadcastEditHappyPath {

        @Test
        @DisplayName("decodes Base64 and passes raw bytes to workspaceService.applyUpdate()")
        void decodesAndForwardsRawBytes() {
            byte[] rawBytes = {1, 2, 3, 4, 5};
            String b64 = Base64.getEncoder().encodeToString(rawBytes);
            when(workspaceService.applyUpdate(eq(WORKSPACE_ID), any())).thenReturn(1);

            controller.broadcastEdit(WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64));

            ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(workspaceService).applyUpdate(eq(WORKSPACE_ID), bytesCaptor.capture());
            assertThat(bytesCaptor.getValue()).isEqualTo(rawBytes);
        }

        @Test
        @DisplayName("stamps the updateCount returned by the service onto the message")
        void stampsUpdateCount() {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            when(workspaceService.applyUpdate(eq(WORKSPACE_ID), any())).thenReturn(42);

            EditMessage result = controller.broadcastEdit(
                    WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64));

            assertThat(result.getUpdateCount()).isEqualTo(42);
        }

        @Test
        @DisplayName("returns the same message object that was passed in (in-place mutation)")
        void returnsSameMessageObject() {
            EditMessage original = makeMessage(WORKSPACE_ID,
                    Base64.getEncoder().encodeToString(new byte[]{1}));
            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);

            EditMessage returned = controller.broadcastEdit(WORKSPACE_ID, original);

            assertThat(returned).isSameAs(original);
        }

        @Test
        @DisplayName("preserves docId in the returned message")
        void preservesDocId() {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);

            EditMessage result = controller.broadcastEdit(
                    WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64));

            assertThat(result.getDocId()).isEqualTo(WORKSPACE_ID);
        }

        @Test
        @DisplayName("preserves the original Base64 update string in the returned message")
        void preservesBase64Update() {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{10, 20, 30});
            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);

            EditMessage result = controller.broadcastEdit(
                    WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64));

            assertThat(result.getUpdate()).isEqualTo(b64);
        }

        @Test
        @DisplayName("calls applyUpdate with the correct workspaceId (from path variable)")
        void usesPathVariableId() {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);

            // Message docId differs from path variable — path variable must win
            EditMessage msg = makeMessage("message-doc-id", b64);
            controller.broadcastEdit("path-var-workspace-id", msg);

            verify(workspaceService).applyUpdate(eq("path-var-workspace-id"), any());
        }
    }

    // ── broadcastEdit — update count boundary values ──────────────────────────

    @Nested
    @DisplayName("broadcastEdit — updateCount boundary values")
    class UpdateCountBoundaries {

        @Test
        @DisplayName("stamps updateCount=1 (first update on empty workspace)")
        void stampsOne() {
            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            EditMessage result = controller.broadcastEdit(WORKSPACE_ID,
                    makeMessage(WORKSPACE_ID, b64));
            assertThat(result.getUpdateCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("stamps updateCount=50 (snapshot threshold boundary)")
        void stampsAtSnapshotThreshold() {
            when(workspaceService.applyUpdate(any(), any())).thenReturn(50);
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            EditMessage result = controller.broadcastEdit(WORKSPACE_ID,
                    makeMessage(WORKSPACE_ID, b64));
            assertThat(result.getUpdateCount()).isEqualTo(50);
        }

        @Test
        @DisplayName("stamps updateCount=0 (immediately after compaction)")
        void stampsZeroAfterCompaction() {
            when(workspaceService.applyUpdate(any(), any())).thenReturn(0);
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            EditMessage result = controller.broadcastEdit(WORKSPACE_ID,
                    makeMessage(WORKSPACE_ID, b64));
            assertThat(result.getUpdateCount()).isEqualTo(0);
        }
    }

    // ── broadcastEdit — Base64 handling ───────────────────────────────────────

    @Nested
    @DisplayName("broadcastEdit — Base64 decode correctness")
    class Base64Handling {

        @Test
        @DisplayName("empty Base64 string decodes to empty byte array without throwing")
        void emptyBase64DecodesToEmptyArray() {
            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);

            assertThatCode(() -> controller.broadcastEdit(
                    WORKSPACE_ID, makeMessage(WORKSPACE_ID, "")))
                    .doesNotThrowAnyException();

            ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
            verify(workspaceService).applyUpdate(any(), cap.capture());
            assertThat(cap.getValue()).isEmpty();
        }

        @Test
        @DisplayName("all 256 byte values survive a Base64 round-trip without corruption")
        void allByteValuesRoundTrip() {
            byte[] allBytes = new byte[256];
            for (int i = 0; i < 256; i++) allBytes[i] = (byte) i;
            String b64 = Base64.getEncoder().encodeToString(allBytes);

            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);
            controller.broadcastEdit(WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64));

            ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
            verify(workspaceService).applyUpdate(any(), cap.capture());
            assertThat(cap.getValue()).isEqualTo(allBytes);
        }

        @Test
        @DisplayName("large Yjs delta (64 KB) is forwarded without truncation")
        void largeDeltaForwardedIntact() {
            byte[] big = new byte[64 * 1024];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i & 0xFF);
            String b64 = Base64.getEncoder().encodeToString(big);

            when(workspaceService.applyUpdate(any(), any())).thenReturn(1);
            controller.broadcastEdit(WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64));

            ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
            verify(workspaceService).applyUpdate(any(), cap.capture());
            assertThat(cap.getValue()).isEqualTo(big);
        }
    }

    // ── broadcastEdit — error propagation ────────────────────────────────────

    @Nested
    @DisplayName("broadcastEdit — error propagation")
    class ErrorPropagation {

        @Test
        @DisplayName("WorkspaceNotFoundException from service propagates to caller")
        void notFoundPropagates() {
            String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
            doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                    .when(workspaceService).applyUpdate(eq(WORKSPACE_ID), any());

            assertThatThrownBy(() ->
                    controller.broadcastEdit(WORKSPACE_ID, makeMessage(WORKSPACE_ID, b64)))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }

        @Test
        @DisplayName("invalid Base64 string throws IllegalArgumentException")
        void invalidBase64Throws() {
            EditMessage msg = makeMessage(WORKSPACE_ID, "!!!NOT_VALID_BASE64!!!");

            assertThatThrownBy(() -> controller.broadcastEdit(WORKSPACE_ID, msg))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}