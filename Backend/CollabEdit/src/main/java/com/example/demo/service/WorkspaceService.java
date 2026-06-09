package com.example.demo.service;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.example.demo.controller.exceptions.WorkspaceNotFoundException;
import com.example.demo.entity.Workspace;

import lombok.RequiredArgsConstructor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final PublicUrlGenerator publicUrlGenerator;
    private final Cache<String, Workspace> workspaces = Caffeine.newBuilder()
            .expireAfterAccess(24, TimeUnit.HOURS)
            .maximumSize(10_000) // optional safety limit
            .build();

    public Workspace createWorkspace() {
        String id = publicUrlGenerator.generate();

        Workspace workspace = new Workspace(id);
        workspaces.put(id, workspace);

        return workspace;
    }

    public Workspace getWorkspace(String id) {
        return workspaces.getIfPresent(id);
    }

    public void removeWorkspace(String id) {
        workspaces.invalidate(id);
    }

    // Appends a raw Yjs binary update to the workspace's update log.
    // No merging happens here — the backend is intentionally CRDT-agnostic.
    // Convergence is handled entirely by the frontend (Y.applyUpdate).
    // The count is broadcast to all clients so they know when to snapshot.
    public int applyUpdate(String id, byte[] update) {
        Workspace workspace = getWorkspace(id);

        if (workspace == null) {
            throw new WorkspaceNotFoundException(id);
        }

        workspace.addUpdate(update);
        return workspace.getUpdateCount();
    }

    // Replaces the update log with a single compacted snapshot blob.
    // Called via HTTP (not WebSocket) after a client decides it's time to compact.
    public void compact(String id, byte[] snapshot) {
        Workspace workspace = getWorkspace(id);

        if (workspace == null) {
            throw new WorkspaceNotFoundException(id);
        }

        workspace.compact(snapshot);
    }
}