import { Client } from "@stomp/stompjs";

// Transport layer for a collaborative workspace session.
//
// Three channels, one WebSocket connection:
//
//   /topic/doc/{id}        Yjs binary update deltas (durable)
//   /topic/hello/{id}      Join announcements — triggers presence exchange (ephemeral)
//   /topic/awareness/{id}  Cursor + presence updates (ephemeral, throttled)
//
// This module has no knowledge of Quill, Yjs internals, or React state.

function getBrokerURL() {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const host = window.location.host; // includes port if non-standard
    return `${protocol}://${host}/ws`;
}

export function createStompClient(
    docId,
    {
        onConnected,
        onDisconnected,
        onUpdate,
        onHello,
        onAwareness,
    }
) {
    let destroyed = false;
    
    const client = new Client({
        brokerURL: getBrokerURL(),
        reconnectDelay: 3000,
    });
 

    function safeParse(body) {
        try {
            return JSON.parse(body);
        } catch (err) {
            console.error("[WS] Failed to parse message:", err);
            return null;
        }
    }

    // Cursor updates can fire dozens of times per second.
    // 50ms cap = at most 20 awareness publishes/sec.
    let lastAwarenessSentAt = 0;
    const AWARENESS_THROTTLE_MS = 50;

    client.onConnect = () => {
        if (destroyed) return;

        console.log(`[WS] Connected to workspace ${docId}`);

        // ── Doc updates ──────────────────────────────────────────
        client.subscribe(`/topic/doc/${docId}`, (msg) => {
            const data = safeParse(msg.body);
            if (!data?.update) {
                console.warn("[WS] Missing update payload");
                return;
            }
            onUpdate?.(data.update, data.updateCount,  data.senderId);
            // data.senderId
        });

        // ── Hello channel ─────────────────────────────────────────
        // Receives join announcements from other clients.
        // Each receiver responds via sendAwareness so the joiner
        // learns who is already in the room.
        client.subscribe(`/topic/hello/${docId}`, (msg) => {
            const data = safeParse(msg.body);
            if (!data?.userId) {
                console.warn("[WS] Hello missing userId");
                return;
            }
            onHello?.(data);
        });

        // ── Awareness channel ─────────────────────────────────────
        // Receives cursor positions, names, colors, disconnect signals.
        client.subscribe(`/topic/awareness/${docId}`, (msg) => {
            const data = safeParse(msg.body);
            if (!data?.userId) {
                console.warn("[WS] Awareness missing userId");
                return;
            }
            onAwareness?.(data);
        });

        onConnected?.();
    };

    client.onDisconnect = () => {
        console.log("[WS] Disconnected");
        onDisconnected?.();
    };

    client.onWebSocketClose = () => {
        console.log("[WS] WebSocket closed");
        onDisconnected?.();
    };

    client.onStompError = (frame) => {
        console.error("[WS] STOMP error:", frame.headers?.message);
    };

    client.activate();

    // ── Send functions ────────────────────────────────────────────

    // Throws if disconnected so the caller (updateHandler) can catch and buffer.
    // This is intentional — silent drops were causing offline updates to be lost.
    function sendUpdate(base64Update, userId) {
        if (!client.connected) {
            throw new Error("WebSocket not connected");
        }
        try {
            client.publish({
                destination: `/app/edit/${docId}`,
                body: JSON.stringify({ docId, update: base64Update, senderId: userId }),
            });
        } catch (err) {
            console.error("[WS] Failed to publish update:", err);
            throw err; // re-throw so caller knows the send failed
        }
    }

    // Throttled — safe to call on every cursor move.
    function sendAwareness(awarenessState) {
        if (!client.connected) return;

        const now = Date.now();
        if (now - lastAwarenessSentAt < AWARENESS_THROTTLE_MS) return;
        lastAwarenessSentAt = now;

        try {
            client.publish({
                destination: `/app/awareness/${docId}`,
                body: JSON.stringify(awarenessState),
            });
        } catch (err) {
            console.error("[WS] Failed to publish awareness:", err);
        }
    }

    // Not throttled — fires exactly once per connect.
    // Goes to the dedicated hello channel, not the awareness channel,
    // so receivers can handle it separately without type-checking hacks.
    function sendHello(localIdentity) {
        if (!client.connected) return;
        try {
            client.publish({
                destination: `/app/hello/${docId}`,
                body: JSON.stringify({
                    userId: localIdentity.userId,
                    name: localIdentity.name,
                    color: localIdentity.color,
                }),
            });
        } catch (err) {
            console.error("[WS] Failed to publish hello:", err);
        }
    }

    // Graceful shutdown — best-effort null cursor before deactivating.
    // Browser crash or network loss won't trigger this.
    async function destroy(localIdentity) {
        destroyed = true;
        try {
            if (client.connected && localIdentity) {
                // Bypass throttle — this is the last message we'll ever send.
                client.publish({
                    destination: `/app/awareness/${docId}`,
                    body: JSON.stringify({
                        userId: localIdentity.userId,
                        name: localIdentity.name,
                        color: localIdentity.color,
                        cursor: null,
                        disconnected: true,
                    }),
                });
            }
        } finally {
            await client.deactivate();
        }
    }

    return { sendUpdate, sendAwareness, sendHello, destroy };
}