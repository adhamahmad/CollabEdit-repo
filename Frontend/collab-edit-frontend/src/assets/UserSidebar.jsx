// UserSidebar renders the list of currently connected collaborators.
// Props:
//   identity    — the local user's identity ({ userId, name, color })
//   awareness   — map of userId → { name, color, cursor } for ALL connected users
//   onNameChange — called with the new name string when the user edits their own name

import { useState } from "react";

function UserEntry({ user, isYou, onNameChange }) {
    const [editing, setEditing] = useState(false);
    const [draft, setDraft] = useState(user.name);

    function handleBlur() {
        setEditing(false);
        const trimmed = draft.trim();
        if (trimmed && trimmed !== user.name) {
            onNameChange(trimmed);
        } else {
            setDraft(user.name); // reset if empty or unchanged
        }
    }

    function handleKeyDown(e) {
        if (e.key === "Enter") e.target.blur();
        if (e.key === "Escape") {
            setDraft(user.name);
            setEditing(false);
        }
    }

    return (
        <div style={styles.entry}>
            {/* Color circle — same color as the user's cursor */}
            <div style={{ ...styles.circle, background: user.color }} />

            <div style={styles.nameRow}>
                {isYou && editing ? (
                    <input
                        autoFocus
                        value={draft}
                        onChange={e => setDraft(e.target.value)}
                        onBlur={handleBlur}
                        onKeyDown={handleKeyDown}
                        style={styles.input}
                        maxLength={32}
                    />
                ) : (
                    <span
                        style={{
                            ...styles.name,
                            // Only the local user's name looks clickable
                            cursor: isYou ? "pointer" : "default",
                            borderBottom: isYou ? "1px dashed #aaa" : "none",
                        }}
                        onClick={() => isYou && setEditing(true)}
                        title={isYou ? "Click to edit your name" : undefined}
                    >
                        {user.name}
                    </span>
                )}

                {isYou && (
                    <span style={styles.youBadge}>(you)</span>
                )}
            </div>
        </div>
    );
}

export function UserSidebar({ identity, awareness, onNameChange }) {
    // Sort so local user is always first, then alphabetically by name
    const entries = Object.entries(awareness).sort(([aId], [bId]) => {
        if (aId === identity.userId) return -1;
        if (bId === identity.userId) return 1;
        return awareness[aId].name.localeCompare(awareness[bId].name);
    });

    return (
        <div style={styles.sidebar}>
            <div style={styles.header}>
                {entries.length} online
            </div>

            {entries.map(([userId, user]) => (
                <UserEntry
                    key={userId}
                    user={user}
                    isYou={userId === identity.userId}
                    onNameChange={onNameChange}
                />
            ))}
        </div>
    );
}

const styles = {
    sidebar: {
        width: "180px",
        flexShrink: 0,
        borderLeft: "1px solid #e2e8f0",
        padding: "12px",
        fontFamily: "sans-serif",
        fontSize: "13px",
    },
    header: {
        fontWeight: 600,
        color: "#718096",
        marginBottom: "10px",
        textTransform: "uppercase",
        fontSize: "11px",
        letterSpacing: "0.05em",
    },
    entry: {
        display: "flex",
        alignItems: "center",
        gap: "8px",
        marginBottom: "8px",
    },
    circle: {
        width: "10px",
        height: "10px",
        borderRadius: "50%",
        flexShrink: 0,
    },
    nameRow: {
        display: "flex",
        alignItems: "center",
        gap: "4px",
        minWidth: 0,
    },
    name: {
        overflow: "hidden",
        textOverflow: "ellipsis",
        whiteSpace: "nowrap",
    },
    youBadge: {
        color: "#a0aec0",
        fontSize: "11px",
        flexShrink: 0,
    },
    input: {
        fontSize: "13px",
        border: "1px solid #cbd5e0",
        borderRadius: "3px",
        padding: "1px 4px",
        width: "110px",
        outline: "none",
    },
};
