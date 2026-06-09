package com.example.demo.controller;

import java.util.Base64;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import com.example.demo.service.WorkspaceService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Controller
public class WorkSpaceWebSocketController {

    private final WorkspaceService workspaceService;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EditMessage {
        private String docId;
        private String update;
        private String senderId;
        // Set by the server on broadcast — tells clients how many updates
        // have been stored since the last snapshot. The client that receives
        // this checks if it's hit the threshold and triggers compaction if so.
        private int updateCount;
    }

    @MessageMapping("/edit/{workspaceId}")
    @SendTo("/topic/doc/{workspaceId}")   // must match frontend subscription
    public EditMessage broadcastEdit(
            @DestinationVariable String workspaceId,
            EditMessage message) {
                
        // Decode Base64 → raw bytes and persist
        byte[] updateBytes = Base64.getDecoder().decode(message.getUpdate());
        int count = workspaceService.applyUpdate(workspaceId, updateBytes);
 
        // Broadcast the same message back to all subscribers (including sender).
        // The frontend is responsible for ignoring its own updates via origin tracking.
        message.setUpdateCount(count);
        return message;
    }
}