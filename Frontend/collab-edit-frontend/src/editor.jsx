import { useEffect, useRef, useImperativeHandle, forwardRef } from "react";
import Quill from "quill";
import "quill/dist/quill.snow.css";

import * as Y from "yjs";
import { QuillBinding } from "y-quill";

import { createStompClient } from "./websocket";

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function base64ToBytes(b64) {
    return Uint8Array.from(atob(b64), c => c.charCodeAt(0));
}

function bytesToBase64(bytes) {
    return btoa(String.fromCharCode(...bytes));
}

// ─────────────────────────────────────────────────────────────────────────────
// Offline persistence
// ─────────────────────────────────────────────────────────────────────────────

const STORAGE_PREFIX = "pending_updates:";

function storageKey(workspaceId) {
    return `${STORAGE_PREFIX}${workspaceId}`;
}

function readPending(workspaceId) {
    try {
        const raw = localStorage.getItem(storageKey(workspaceId));

        if (!raw) {
            return [];
        }

        const parsed = JSON.parse(raw);

        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

function appendPending(workspaceId, b64Update) {
    try {
        const current = readPending(workspaceId);

        current.push(b64Update);

        localStorage.setItem(
            storageKey(workspaceId),
            JSON.stringify(current)
        );
    } catch (err) {
        console.warn("[PENDING] localStorage write failed:", err);
    }
}

function clearPending(workspaceId) {
    try {
        localStorage.removeItem(storageKey(workspaceId));
    } catch {
        // ignore
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cursor overlay helpers
// ─────────────────────────────────────────────────────────────────────────────

function removeCursor(cursorMap, userId) {
    const existing = cursorMap[userId];

    if (!existing) {
        return;
    }

    existing.remove();

    delete cursorMap[userId];
}

function clearAllCursors(cursorMap) {
    Object.keys(cursorMap).forEach(userId => {
        removeCursor(cursorMap, userId);
    });
}

function positionCursorElement(quill, overlayEl, element, index) {
    const bounds = quill.getBounds(index);

    if (!bounds) {
        return;
    }

    // Quill coordinates are relative to .ql-editor
    // Overlay is attached to quill.container
    // We must translate coordinates.

    const editorEl = quill.root;

    const editorRect = editorEl.getBoundingClientRect();
    const overlayRect = overlayEl.getBoundingClientRect();

    const top =
        bounds.top +
        (editorRect.top - overlayRect.top) -
        editorEl.scrollTop;

    const left =
        bounds.left +
        (editorRect.left - overlayRect.left) -
        editorEl.scrollLeft;

    element.style.top = `${top}px`;
    element.style.left = `${left}px`;
    element.style.height = `${bounds.height}px`;
}

function renderCursor(
    quill,
    overlayEl,
    cursorMap,
    userId,
    name,
    color,
    index
) {
    removeCursor(cursorMap, userId);

    const wrapper = document.createElement("div");

    wrapper.dataset.cursorIndex = index;

    wrapper.style.cssText = `
        position: absolute;
        pointer-events: none;
        z-index: 1000;
    `;

    const caret = document.createElement("div");

    caret.style.cssText = `
        position: absolute;
        top: 0;
        left: 0;
        width: 2px;
        height: 100%;
        background: ${color};
    `;

    const label = document.createElement("div");

    label.textContent = name;

    label.style.cssText = `
        position: absolute;
        bottom: 100%;
        left: 0;
        margin-bottom: 2px;
        background: ${color};
        color: white;
        font-size: 11px;
        font-family: sans-serif;
        padding: 2px 5px;
        border-radius: 4px;
        white-space: nowrap;
    `;

    wrapper.appendChild(caret);
    wrapper.appendChild(label);

    overlayEl.appendChild(wrapper);

    positionCursorElement(
        quill,
        overlayEl,
        wrapper,
        index
    );

    cursorMap[userId] = wrapper;
}

// ─────────────────────────────────────────────────────────────────────────────
// Component
// ─────────────────────────────────────────────────────────────────────────────

const Editor = forwardRef(function Editor({
    editorApiRef,
    initialUpdates = [],
    workspaceId,
    identity,
    onAwarenessUpdate,
}, ref) {
    const editorRef = useRef(null);

    const overlayRef = useRef(null);

    const quillRef = useRef(null);

    const ydocRef = useRef(null);

    const identityRef = useRef(identity);

    const sendAwarenessRef = useRef(null);

    // Expose exportAsText() to parent via ref.
    // forwardRef is required for useImperativeHandle to attach —
    // without it React never sets ref.current and the parent crashes.
    useImperativeHandle(editorApiRef, () => ({
        exportAsText() {
            if (!quillRef.current) return "";
            return quillRef.current.getText();
        },
    }));

    useEffect(() => {
        identityRef.current = identity;
    });

    useEffect(() => {
        if (!sendAwarenessRef.current) {
            return;
        }

        sendAwarenessRef.current({
            ...identity,
            cursor: null,
        });
    }, [identity.name]);

    useEffect(() => {
        if (!editorRef.current) {
            return;
        }

        if (quillRef.current) return;

        // ─────────────────────────────────────────────────────────────────────
        // Yjs
        // ─────────────────────────────────────────────────────────────────────

        const ydoc = new Y.Doc();

        ydocRef.current = ydoc;

        const ytext = ydoc.getText("quill");

        // ─────────────────────────────────────────────────────────────────────
        // Quill
        // ─────────────────────────────────────────────────────────────────────

        const quill = new Quill(editorRef.current, {
            theme: "snow",
            placeholder: "Start typing…",
        });

        quillRef.current = quill;

        // Make Quill container positioning context for overlay
        quill.container.style.position = "relative";

        // ─────────────────────────────────────────────────────────────────────
        // Overlay
        // ─────────────────────────────────────────────────────────────────────

        const overlayEl = document.createElement("div");

        overlayEl.style.cssText = `
            position: absolute;
            top: -24px;
            left: 0;
            right: 0;
            bottom: 0;
            pointer-events: none;
            overflow: visible;
            z-index: 999;
        `;

        quill.container.appendChild(overlayEl);

        overlayRef.current = overlayEl;

        // ─────────────────────────────────────────────────────────────────────
        // Binding
        // ─────────────────────────────────────────────────────────────────────

        const binding = new QuillBinding(ytext, quill);

        // ─────────────────────────────────────────────────────────────────────
        // Initial sync
        // ─────────────────────────────────────────────────────────────────────

        if (initialUpdates.length > 0) {
            ydoc.transact(() => {
                initialUpdates.forEach(b64 => {
                    Y.applyUpdate(
                        ydoc,
                        base64ToBytes(b64)
                    );
                });
            }, "local-init");
        }

        let pendingUpdates = readPending(workspaceId);

        if (pendingUpdates.length > 0) {
            ydoc.transact(() => {
                pendingUpdates.forEach(b64 => {
                    Y.applyUpdate(
                        ydoc,
                        base64ToBytes(b64)
                    );
                });
            }, "local-init");
        }

        // ─────────────────────────────────────────────────────────────────────
        // Cursor state
        // ─────────────────────────────────────────────────────────────────────

        const cursorMap = {};

        function repositionAllCursors() {
            Object.entries(cursorMap).forEach(([userId, el]) => {
                const index = Number(el.dataset.cursorIndex);

                if (Number.isNaN(index)) {
                    return;
                }

                positionCursorElement(
                    quill,
                    overlayEl,
                    el,
                    index
                );
            });
        }

        // ─────────────────────────────────────────────────────────────────────
        // Websocket
        // ─────────────────────────────────────────────────────────────────────

        let wsReady = false;
        let alive = true;

        const SNAPSHOT_THRESHOLD = 5;
        const COMPACTION_THRESHOLD = 100;

        const {
            sendUpdate,
            sendAwareness,
            sendHello,
            destroy,
        } = createStompClient(workspaceId, {
            onConnected: () => {
                wsReady = true;

                const unsent = [];

                for (const b64 of pendingUpdates) {
                    try {
                        sendUpdate(b64);
                    } catch {
                        unsent.push(b64);
                    }
                }

                pendingUpdates = unsent;

                if (pendingUpdates.length === 0) {
                    clearPending(workspaceId);
                }

                sendAwarenessRef.current = sendAwareness;

                sendHello(identityRef.current);
            },

            onDisconnected: () => {
                wsReady = false;
            },

            onUpdate: (b64Update, updateCount, senderId) => {
                if (!alive) return;
                console.log(
                    "[SNAPSHOT CHECK]",
                    senderId,
                    identityRef.current.userId
                );
                Y.applyUpdate(
                    ydoc,
                    base64ToBytes(b64Update),
                    "remote"
                );

                if (
                    senderId === identityRef.current.userId && // TODO test the snapshot
                    updateCount > 0 &&
                    updateCount % SNAPSHOT_THRESHOLD === 0
                ) {
                    setTimeout(() => {
                        // Delay reduces race condition between in-flight updates and snapshot compaction on the server.
                        const snapshot = bytesToBase64(
                            Y.encodeStateAsUpdate(ydoc)
                        );

                        fetch(
                            // `https://sphere-motocross-hunchback.ngrok-free.app/${workspaceId}/snapshot`,
                            // `http://localhost:8080/${workspaceId}/snapshot`,
                            `/api/${workspaceId}/snapshot`,
                            {
                                method: "POST",
                                headers: {
                                    "Content-Type": "application/json",
                                },
                                body: JSON.stringify({
                                    snapshot,
                                }),
                            }
                        )
                            .then(() => {
                                console.log(
                                    `[SNAPSHOT] Successfully sent snapshot at updateCount=${updateCount}`
                                );
                            })

                            .catch(err => {
                                console.error(
                                    "[SNAPSHOT] Failed:",
                                    err
                                );
                            });
                    },500);
                }
            },

            onHello: ({ userId, name, color }) => {
                if (userId === identityRef.current.userId) {
                    return;
                }

                onAwarenessUpdate({
                    userId,
                    name,
                    color,
                    cursor: null,
                });

                sendAwareness({
                    ...identityRef.current,
                    cursor: null,
                });
            },

            onAwareness: ({
                userId,
                name,
                color,
                cursor,
                disconnected,
            }) => {
                if (userId === identityRef.current.userId) {
                    return;
                }

                if (!cursor || disconnected) {
                    removeCursor(cursorMap, userId);

                    onAwarenessUpdate({
                        userId,
                        name,
                        color,
                        cursor,
                        disconnected,
                    });

                    return;
                }

                const absPos =
                    Y.createAbsolutePositionFromRelativePosition(
                        Y.createRelativePositionFromJSON(
                            cursor.anchor
                        ),
                        ydoc
                    );

                if (!absPos) {
                    return;
                }

                renderCursor(
                    quill,
                    overlayEl,
                    cursorMap,
                    userId,
                    name,
                    color,
                    absPos.index
                );

                onAwarenessUpdate({
                    userId,
                    name,
                    color,
                    cursor,
                    disconnected,
                });
            },
        });

        // ─────────────────────────────────────────────────────────────────────
        // Awareness
        // ─────────────────────────────────────────────────────────────────────

        function selectionHandler(range) {
            if (!wsReady) {
                return;
            }

            if (!range) {
                sendAwareness({
                    ...identityRef.current,
                    cursor: null,
                });

                return;
            }

            const anchor =
                Y.createRelativePositionFromTypeIndex(
                    ytext,
                    range.index
                );

            const head =
                Y.createRelativePositionFromTypeIndex(
                    ytext,
                    range.index + range.length
                );

            sendAwareness({
                ...identityRef.current,
                cursor: {
                    anchor:
                        Y.relativePositionToJSON(anchor),
                    head:
                        Y.relativePositionToJSON(head),
                },
            });
        }

        quill.on(
            "selection-change",
            selectionHandler
        );

        // ─────────────────────────────────────────────────────────────────────
        // Updates
        // ─────────────────────────────────────────────────────────────────────

        function updateHandler(update, origin) {
            if (
                origin === "remote" ||
                origin === "local-init"
            ) {
                return;
            }

            const b64 = bytesToBase64(update);

            pendingUpdates.push(b64);

            appendPending(workspaceId, b64);

            if (pendingUpdates.length > COMPACTION_THRESHOLD) { // TODO test
                const compacted = bytesToBase64(
                    Y.encodeStateAsUpdate(ydoc)
                );

                pendingUpdates = [compacted];

                localStorage.setItem(
                    storageKey(workspaceId),
                    JSON.stringify([compacted])
                );
            }

            if (wsReady) {
                try {
                    sendUpdate(b64, identityRef.current.userId);

                    return;
                } catch {
                    wsReady = false;
                }
            }
        }

        ydoc.on("update", updateHandler);

        // ─────────────────────────────────────────────────────────────────────
        // Cursor repositioning
        // ─────────────────────────────────────────────────────────────────────

        quill.on(
            "text-change",
            repositionAllCursors
        );

        quill.root.addEventListener(
            "scroll",
            repositionAllCursors
        );

        window.addEventListener(
            "resize",
            repositionAllCursors
        );

        // ─────────────────────────────────────────────────────────────────────
        // Cleanup
        // ─────────────────────────────────────────────────────────────────────

        return () => {
            sendAwarenessRef.current = null;
            alive = false;

            quill.off(
                "selection-change",
                selectionHandler
            );

            quill.off(
                "text-change",
                repositionAllCursors
            );

            quill.root.removeEventListener(
                "scroll",
                repositionAllCursors
            );

            window.removeEventListener(
                "resize",
                repositionAllCursors
            );

            ydoc.off("update", updateHandler);

            clearAllCursors(cursorMap);

            binding?.destroy?.();
            ydocRef.current?.destroy?.();
            destroy?.(identityRef.current);

            overlayEl.remove();

            quillRef.current = null;

            ydocRef.current = null;

            if (editorRef.current) {
                const toolbar = quill.getModule("toolbar");
                toolbar.container.outerHTML = "";
            }
        };
    }, [workspaceId]);

    return (
        <div>
            <div
                ref={editorRef}
                style={{
                    height: "300px",
                }}
            />
        </div>
    );
});

export default Editor;