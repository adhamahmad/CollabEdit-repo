package com.example.demo.controller;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import tools.jackson.databind.JsonNode;

// Handles cursor position and user identity broadcast.
// This controller intentionally has NO service dependency —
// awareness is ephemeral and must never be persisted.
// The backend is a pure relay: receive → broadcast, nothing else.
@Controller
public class AwarenessWebSocketController {

    // We use a loose JsonNode for the cursor field because it contains
    // Yjs relative position objects whose structure we don't need to
    // understand on the backend — we just pass them through unchanged.
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwarenessMessage {
        private String userId;
        private String name;
        private String color;
        // null when the user disconnects or blurs the editor.
        // Receivers remove the cursor when they see null.
        private JsonNode cursor;
        // Set by the client on graceful disconnect so receivers remove the
        // user from the sidebar immediately rather than waiting for a timeout.
        // Not set during normal cursor-null events (blur).
        private boolean disconnected;
    }

    @MessageMapping("/awareness/{workspaceId}")
    @SendTo("/topic/awareness/{workspaceId}")
    public AwarenessMessage broadcastAwareness(
            @DestinationVariable String workspaceId,
            AwarenessMessage message) {
        // Pure relay — no storage, no transformation.
        return message;
    }

        // Join announcement channel.
    // Receivers respond with their own state on /app/awareness/{id}.
    @MessageMapping("/hello/{workspaceId}")
    @SendTo("/topic/hello/{workspaceId}")
    public AwarenessMessage broadcastHello(
            @DestinationVariable String workspaceId,
            AwarenessMessage message) {
        return message;
    }
 
}