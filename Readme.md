# SyncPad

SyncPad is a minimal, efficient, open-source collaborative text editor that enables real-time multi-user editing directly in the browser. It is built with **Yjs CRDTs** over **WebSockets** and keeps the backend intentionally simple and CRDT-agnostic, focusing only on message relay and persistence.

The system is fully self-hosted, open-source, requires no database, no signup, and runs in a lightweight Docker setup.

---

> ![SyncPad demo](media/collabe_edit_HD.gif)

---

## Key Features

- **Real-time co-editing** — document state converges across all connected clients via Yjs CRDT merge semantics
- **Live cursor presence** — per-user cursors and display names rendered directly in the editor with color-coded overlays
- **Offline write buffering** — edits made while disconnected are queued locally and flushed to the server on reconnect, with no data loss
- **Export as text file** — users can download the current document as a `.txt` file directly from the editor
- **Automatic snapshot compaction** — after every 50 updates, a client compacts the full document state into a single blob, keeping late-joiner sync fast regardless of edit history length
- **Ephemeral workspaces** — no database; workspaces live in a Caffeine in-memory cache with a 24-hour TTL
- **Session-stable identity** — users get a consistent name and color within a browser tab; opening a new tab spawns a separate collaborator identity

---

## Quick Start

### Docker (recommended)

```bash
git clone https://github.com/adhamahmad/CollabEdit-repo
cd CollabEdit-repo
docker compose up --build
```

The frontend is served at `http://localhost:80`. Navigating to `/` auto-creates a workspace and redirects to `/{id}`. Open the same URL in a second tab to test real-time collaboration.

```bash
PORT=8081 docker compose up --build   # use a different host port
```

### Local Development

**Backend**

```bash
cd Backend/CollabEdit
mvn spring-boot:run
# Starts on :8080
```

**Frontend**

```bash
cd Frontend/collab-edit-frontend
npm install
npm run dev
# Vite dev server on :5173
```

## Architecture

```
Browser (React + Quill + Yjs)
        │
        ├── HTTP GET  /{id}           → Fetch workspace state (Base64 Yjs update list)
        ├── HTTP POST /{id}/snapshot  → Submit compacted document snapshot
        └── WebSocket (STOMP)
              ├── /app/edit/{id}        → Publish Yjs binary delta
              ├── /app/awareness/{id}   → Publish cursor / presence state
              └── /app/hello/{id}       → Announce join, trigger presence exchange

Spring Boot
        │
        ├── WorkspaceController              → REST: workspace creation and retrieval
        ├── WorkSpaceWebSocketController     → Persist incoming delta, broadcast to subscribers
        ├── AwarenessWebSocketController     → Pure relay for cursor and presence messages
        └── WorkspaceService                 → Caffeine cache keyed by workspace ID

In-memory Workspace
        └── List<byte[]> updates     → Ordered Yjs binary deltas; replaced atomically on snapshot
```

### WebSocket Channels

| Channel | Direction | Durability | Purpose |
|---|---|---|---|
| `/topic/doc/{id}` | Server → all clients | Persisted | Yjs binary update deltas |
| `/topic/awareness/{id}` | Server → all clients | Ephemeral | Cursor positions, names, colors, disconnect signals |
| `/topic/hello/{id}` | Server → all clients | Ephemeral | Join announcement — triggers presence exchange from existing clients |

---

## Design Decisions

### CRDT-agnostic backend

The server stores and broadcasts raw `byte[]` blobs. It has no knowledge of Yjs document structure, merge semantics, or operation ordering. Document convergence is handled entirely by `Y.applyUpdate` on each client. This boundary means the backend cannot corrupt document state, can be swapped for a different CRDT library without server changes, and has no logic to test beyond persistence and broadcast.

### Awareness is never persisted

The awareness controller has no service dependency by design. Cursor positions, names, and colors are only meaningful to currently connected clients — writing them to any store would add complexity with no benefit. The backend receives a cursor message and immediately rebroadcasts it, unchanged.

### Offline write buffering

When the WebSocket is unavailable, outgoing Yjs deltas are appended to a queue backed by `localStorage`. On reconnect, the queue is replayed to the server before normal operation resumes. The send path throws explicitly on a disconnected socket rather than failing silently, so the caller can catch the error and gate the flush correctly. This prevents silent data loss that would otherwise be undetectable at the application level.

### Client-triggered snapshot compaction

Every broadcast update carries a server-incremented `updateCount`. When a client observes `updateCount % 50 === 0`, it calls `Y.encodeStateAsUpdate` to produce a full-state snapshot and POSTs it to `/{id}/snapshot`. The backend replaces the entire delta list with this single blob atomically. Late joiners then receive one compact entry instead of an unbounded delta log, keeping initial sync time proportional to document size rather than edit history length.

### Concurrency model

`Workspace` exposes only synchronized methods for all mutable operations (`addUpdate`, `compact`, `getUpdates`, `getUpdateCount`). `getUpdates` returns a `List.copyOf` so callers hold a consistent snapshot that cannot be mutated by concurrent writers. The Caffeine cache handles workspace-level concurrent access. There are no explicit locks above the method level — the synchronization boundary is the `Workspace` object itself.

### Session-scoped identity

User identity is stored in `sessionStorage`, not `localStorage`. This means a page refresh preserves the same name and cursor color, but opening a new tab creates a distinct collaborator. Color is assigned deterministically by hashing the session `userId` modulo the palette size, so identity is stable across reconnects without requiring any server-side user registry.

---

## API Reference

### Create workspace

```
GET /
```

Creates a new workspace. The ID is generated from 96 bits of `SecureRandom` entropy, URL-safe Base64 encoded.

```json
{ "id": "abc123XYZ_def456", "updates": [] }
```

### Load workspace

```
GET /{id}
```

Returns the ordered list of stored Yjs updates as Base64 strings. Clients apply all entries via `Y.applyUpdate` inside a single transaction on load.

```json
{
  "id": "abc123XYZ_def456",
  "updates": ["AQID...", "BAUG..."]
}
```

Returns `404` if the workspace does not exist or has expired.

### Compact update log

```
POST /{id}/snapshot
Content-Type: application/json

{ "snapshot": "<base64-encoded Y.encodeStateAsUpdate output>" }
```

Replaces the entire update log with a single compacted blob. Triggered automatically by the client; not intended for direct use.

---

## WebSocket Protocol

Clients connect via STOMP to `/ws` using a native WebSocket.

### Join sequence

```
Client  →  CONNECT /ws
Client  →  SUBSCRIBE /topic/doc/{id}
Client  →  SUBSCRIBE /topic/awareness/{id}
Client  →  SUBSCRIBE /topic/hello/{id}
Client  →  SEND /app/hello/{id}      { userId, name, color }

Server  →  BROADCAST /topic/hello/{id}

Others  →  SEND /app/awareness/{id}  { userId, name, color, cursor: null }
```

Existing clients respond to a `hello` by broadcasting their current awareness state, allowing the new joiner to populate the presence sidebar without a server-side registry.

### Sending an edit

```
Client  →  SEND /app/edit/{id}
           { "docId": "...", "update": "<base64 Yjs delta>" }

Server persists raw bytes, increments updateCount

Server  →  BROADCAST /topic/doc/{id}
           { "docId": "...", "update": "<same base64>", "updateCount": 42 }
```

All clients including the sender receive the broadcast. The sender skips re-applying the update by checking the Yjs transaction origin (`"remote"`) inside the document update listener.

### Cursor update

```
Client  →  SEND /app/awareness/{id}
           {
             "userId": "...",
             "name": "Silent Otter",
             "color": "#E53935",
             "cursor": { "anchor": { ... }, "head": { ... } }
           }

Server  →  BROADCAST /topic/awareness/{id}   (relay only, payload unchanged)
```

Cursor positions are encoded as Yjs relative positions (`Y.createRelativePositionFromTypeIndex`) so they remain valid under concurrent edits. The client throttles awareness publishes to one per 50 ms. Sending `cursor: null` clears the remote cursor on all receivers.

### Graceful disconnect

```
Client  →  SEND /app/awareness/{id}
           { "userId": "...", "disconnected": true, "cursor": null }

Client  →  STOMP DISCONNECT
```

The `disconnected` flag causes receivers to remove the user from the presence sidebar immediately. Abrupt disconnects (network loss, browser crash) do not trigger this path — remote cursors from crashed clients persist until the next received awareness update from that user.

---

## Testing

**Backend**

```bash
cd Backend/CollabEdit
mvn test
```

**Frontend**

```bash
cd Frontend/collab-edit-frontend
npx vitest
```

## Project Structure

```
Backend/CollabEdit/
├── src/main/java/com/example/demo/
│   ├── config/       STOMP broker and endpoint configuration
│   ├── controller/   REST and WebSocket message handlers
│   ├── entity/       Workspace — in-memory update store with synchronized access
│   ├── service/      Workspace lifecycle, Caffeine cache, compaction
│   ├── mapper/       Entity → DTO (Base64 encoding of byte[] updates)
│   └── DTO/          API response types
└── Dockerfile        Multi-stage: Maven build → JRE 21 Alpine runtime

Frontend/collab-edit-frontend/
├── src/              React app — editor, WebSocket client, presence UI
└── Dockerfile        Multi-stage: Node build → Nginx static serve

docker-compose.yml
    backend     Internal network only — not exposed to host
    frontend    Nginx reverse proxy, exposed on host port (default 80)
```
