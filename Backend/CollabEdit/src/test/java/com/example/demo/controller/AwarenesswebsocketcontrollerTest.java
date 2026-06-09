package com.example.demo.controller;

import com.example.demo.controller.AwarenessWebSocketController.AwarenessMessage;
// import com.fasterxml.jackson.databind.JsonNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
// import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * AwarenessWebSocketControllerTest — unit tests for the awareness relay.
 *
 * What is tested:
 *   broadcastAwareness:
 *     - Returns the exact same message object (pure relay, no transformation)
 *     - All fields (userId, name, color, cursor, disconnected) are preserved
 *     - null cursor is preserved (blur / explicit null send)
 *     - disconnected=true is preserved (graceful leave signal)
 *     - disconnected=false is preserved (normal update)
 *     - Opaque JsonNode cursor passes through unchanged
 *
 *   broadcastHello:
 *     - Returns the exact same message object
 *     - All identity fields pass through unchanged
 *
 * Why valuable:
 *   This controller is intentionally a pure relay with no service dependency.
 *   Its entire contract is "return exactly what you received." Any accidental
 *   transformation (nulling a field, toggling disconnected, losing the cursor
 *   node) would corrupt presence state for every client in the session.
 *   The tests are simple but they make that contract explicit and regression-
 *   proof — a refactor that adds accidental logic will break them immediately.
 */
class AwarenessWebSocketControllerTest {

    private AwarenessWebSocketController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        controller = new AwarenessWebSocketController();
        objectMapper = new ObjectMapper();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AwarenessMessage buildMessage(
            String userId, String name, String color,
            JsonNode cursor, boolean disconnected) {

        AwarenessMessage msg = new AwarenessMessage();
        msg.setUserId(userId);
        msg.setName(name);
        msg.setColor(color);
        msg.setCursor(cursor);
        msg.setDisconnected(disconnected);
        return msg;
    }

    private AwarenessMessage basicMessage() {
        return buildMessage("user-123", "Swift Fox", "#E53935", null, false);
    }

    // ── broadcastAwareness ────────────────────────────────────────────────────

    @Nested
    @DisplayName("broadcastAwareness — pure relay contract")
    class BroadcastAwareness {

        @Test
        @DisplayName("returns the exact same object reference (no copy)")
        void returnsSameReference() {
            AwarenessMessage msg = basicMessage();
            AwarenessMessage result = controller.broadcastAwareness("ws-id", msg);
            assertThat(result).isSameAs(msg);
        }

        @Test
        @DisplayName("preserves userId")
        void preservesUserId() {
            AwarenessMessage msg = basicMessage();
            assertThat(controller.broadcastAwareness("ws-id", msg).getUserId())
                    .isEqualTo("user-123");
        }

        @Test
        @DisplayName("preserves name")
        void preservesName() {
            AwarenessMessage msg = basicMessage();
            assertThat(controller.broadcastAwareness("ws-id", msg).getName())
                    .isEqualTo("Swift Fox");
        }

        @Test
        @DisplayName("preserves color")
        void preservesColor() {
            AwarenessMessage msg = basicMessage();
            assertThat(controller.broadcastAwareness("ws-id", msg).getColor())
                    .isEqualTo("#E53935");
        }

        @Test
        @DisplayName("preserves null cursor (blur event)")
        void preservesNullCursor() {
            AwarenessMessage msg = buildMessage("u1", "A", "#fff", null, false);
            assertThat(controller.broadcastAwareness("ws-id", msg).getCursor()).isNull();
        }

        @Test
        @DisplayName("preserves non-null cursor JsonNode unchanged")
        void preservesNonNullCursor() throws Exception {
            JsonNode cursorNode = objectMapper.readTree(
                    "{\"anchor\":{\"type\":\"relative\",\"tname\":\"quill\"}," +
                    "\"head\":{\"type\":\"relative\",\"tname\":\"quill\"}}");

            AwarenessMessage msg = buildMessage("u1", "A", "#fff", cursorNode, false);
            JsonNode returned = controller.broadcastAwareness("ws-id", msg).getCursor();

            assertThat(returned).isEqualTo(cursorNode);
        }

        @Test
        @DisplayName("preserves cursor with nested Yjs relative position structure")
        void preservesComplexCursorStructure() throws Exception {
            // Realistic Yjs relative position JSON
            JsonNode cursor = objectMapper.readTree(
                    "{\"anchor\":{\"type\":\"relative\",\"tname\":\"quill\"," +
                    "\"item\":{\"client\":12345,\"clock\":7}}," +
                    "\"head\":{\"type\":\"relative\",\"tname\":\"quill\"," +
                    "\"item\":{\"client\":12345,\"clock\":12}}}");

            AwarenessMessage msg = buildMessage("u1", "B", "#000", cursor, false);
            assertThat(controller.broadcastAwareness("ws-id", msg).getCursor())
                    .isEqualTo(cursor);
        }

        @Test
        @DisplayName("preserves disconnected=true (graceful leave signal)")
        void preservesDisconnectedTrue() {
            AwarenessMessage msg = buildMessage("u1", "A", "#fff", null, true);
            assertThat(controller.broadcastAwareness("ws-id", msg).isDisconnected())
                    .isTrue();
        }

        @Test
        @DisplayName("preserves disconnected=false (normal cursor update)")
        void preservesDisconnectedFalse() {
            AwarenessMessage msg = buildMessage("u1", "A", "#fff", null, false);
            assertThat(controller.broadcastAwareness("ws-id", msg).isDisconnected())
                    .isFalse();
        }

        @Test
        @DisplayName("does NOT add or modify any field on the message")
        void noUnexpectedFieldMutation() {
            AwarenessMessage msg = buildMessage("u1", "Iron Seal", "#546E7A", null, false);

            AwarenessMessage result = controller.broadcastAwareness("any-workspace", msg);

            // Verify every field individually — there must be no unexpected mutation
            assertThat(result.getUserId()).isEqualTo("u1");
            assertThat(result.getName()).isEqualTo("Iron Seal");
            assertThat(result.getColor()).isEqualTo("#546E7A");
            assertThat(result.getCursor()).isNull();
            assertThat(result.isDisconnected()).isFalse();
        }

        @Test
        @DisplayName("workspace ID path variable does not appear in the returned message")
        void workspaceIdNotLeakedIntoMessage() {
            // The controller receives the workspace ID via @DestinationVariable
            // but must NOT inject it into the relayed message
            AwarenessMessage msg = basicMessage();
            AwarenessMessage result = controller.broadcastAwareness("WORKSPACE-XYZ", msg);
            // There is no workspaceId field on AwarenessMessage,
            // but we verify the known fields don't get contaminated
            assertThat(result.getUserId()).doesNotContain("WORKSPACE-XYZ");
            assertThat(result.getName()).doesNotContain("WORKSPACE-XYZ");
        }
    }

    // ── broadcastHello ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("broadcastHello — pure relay contract")
    class BroadcastHello {

        @Test
        @DisplayName("returns the exact same object reference")
        void returnsSameReference() {
            AwarenessMessage msg = basicMessage();
            assertThat(controller.broadcastHello("ws-id", msg)).isSameAs(msg);
        }

        @Test
        @DisplayName("preserves all identity fields: userId, name, color")
        void preservesIdentityFields() {
            AwarenessMessage msg = buildMessage(
                    "hello-user-id", "Golden Crane", "#FDD835", null, false);

            AwarenessMessage result = controller.broadcastHello("ws-id", msg);

            assertThat(result.getUserId()).isEqualTo("hello-user-id");
            assertThat(result.getName()).isEqualTo("Golden Crane");
            assertThat(result.getColor()).isEqualTo("#FDD835");
        }

        @Test
        @DisplayName("hello message cursor is null (join announcements carry no cursor)")
        void helloCursorIsNull() {
            // By design, hello messages don't carry cursor data
            AwarenessMessage msg = buildMessage("u1", "A", "#fff", null, false);
            assertThat(controller.broadcastHello("ws-id", msg).getCursor()).isNull();
        }

        @Test
        @DisplayName("hello message disconnected=false (joining, not leaving)")
        void helloDisconnectedFalse() {
            AwarenessMessage msg = buildMessage("u1", "A", "#fff", null, false);
            assertThat(controller.broadcastHello("ws-id", msg).isDisconnected()).isFalse();
        }
    }

    // ── No service interaction ────────────────────────────────────────────────

    @Nested
    @DisplayName("No persistence — controller has no service dependency")
    class NoPersistence {

        @Test
        @DisplayName("broadcastAwareness can be called without any service configured")
        void noServiceRequired() {
            // The controller is constructed directly with no dependencies.
            // If it ever gains a service injection, this test documents the
            // intentional constraint that awareness must remain ephemeral.
            AwarenessWebSocketController ephemeralController =
                    new AwarenessWebSocketController();

            assertThatCode(() ->
                    ephemeralController.broadcastAwareness("ws", basicMessage()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("broadcastHello can be called without any service configured")
        void noServiceRequiredForHello() {
            AwarenessWebSocketController ephemeralController =
                    new AwarenessWebSocketController();

            assertThatCode(() ->
                    ephemeralController.broadcastHello("ws", basicMessage()))
                    .doesNotThrowAnyException();
        }
    }
}