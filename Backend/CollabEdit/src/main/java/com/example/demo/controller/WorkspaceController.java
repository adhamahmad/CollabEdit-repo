package com.example.demo.controller;

import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.DTO.WorkspaceResponse;
import com.example.demo.controller.exceptions.WorkspaceNotFoundException;
import com.example.demo.entity.Workspace;
import com.example.demo.mapper.WorkspaceMapper;
import com.example.demo.service.WorkspaceService;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceMapper workspaceMapper;

    @GetMapping("/")
    public WorkspaceResponse autoCreateWorkspace() {
        Workspace workspace = workspaceService.createWorkspace();
        return workspaceMapper.toResponse(workspace);
    }

    @GetMapping("/{id:[A-Za-z0-9_-]{16,22}}")
    public WorkspaceResponse openWorkspace(@PathVariable String id) {
        Workspace workspace = workspaceService.getWorkspace(id);

        if (workspace == null) {
            throw new WorkspaceNotFoundException(id);
        }

        return workspaceMapper.toResponse(workspace);
    }

    @Data
    public static class SnapshotRequest {
        private String snapshot; // Base64-encoded full Y.encodeStateAsUpdate(ydoc)
    }

    // Triggered by the frontend when updateCount hits the threshold.
    // Replaces the entire update log with one compacted blob — from this point
    // forward, late joiners receive a single entry instead of thousands of deltas.
    @PostMapping("/{id:[A-Za-z0-9_-]{16,22}}/snapshot")
    public void saveSnapshot(@PathVariable String id,
                             @RequestBody SnapshotRequest req) {
        if (req.getSnapshot() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "snapshot field is required");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(req.getSnapshot());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "snapshot is not valid Base64");
        }
        workspaceService.compact(id, bytes);
    }
 

}