import React, { useState, useEffect, useRef } from "react";

// Compact presence pills shown in the navbar.
// Replaces the full sidebar with a lightweight inline row.
export function PresencePills({ awareness, identity, onNameChange }) {
  const entries = Object.entries(awareness).sort(([aId], [bId]) => {
    if (aId === identity.userId) return -1;
    if (bId === identity.userId) return 1;
    return awareness[aId].name.localeCompare(awareness[bId].name);
  });

  // Only show up to 4 avatars + overflow count to keep nav clean
  const MAX_SHOWN = 4;
  const shown = entries.slice(0, MAX_SHOWN);
  const overflow = entries.length - MAX_SHOWN;

  return (
    <div style={styles.pills}>
      {shown.map(([userId, user]) => (
        <Pill
          key={userId}
          user={user}
          isYou={userId === identity.userId}
          onNameChange={onNameChange}
        />
      ))}
      {overflow > 0 && (
        <div style={styles.overflow} title={`${overflow} more`}>
          +{overflow}
        </div>
      )}
    </div>
  );
}

function Pill({ user, isYou, onNameChange }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(user.name);
  const inputRef = useRef(null);

  useEffect(() => {
    if (!editing) setDraft(user.name);
  }, [user.name, editing]);

  function handleBlur() {
    setEditing(false);
    const trimmed = draft.trim();
    if (!trimmed) { setDraft(user.name); return; }
    if (trimmed !== user.name) onNameChange(trimmed);
  }

  function handleKeyDown(e) {
    if (e.key === "Enter") e.target.blur();
    if (e.key === "Escape") { setDraft(user.name); setEditing(false); }
  }

  const initials = user.name
    .split(" ")
    .slice(0, 2)
    .map(w => w[0])
    .join("")
    .toUpperCase();

  return (
    <div
      style={{
        ...styles.pill,
        cursor: isYou ? "pointer" : "default",
      }}
      onClick={() => { if (isYou && !editing) setEditing(true); }}
      title={isYou ? "Click to rename" : user.name}
    >
      {/* Color dot avatar */}
      <div
        style={{
          ...styles.dot,
          background: user.color,
        }}
        aria-hidden="true"
      >
        <span style={styles.dotInitials}>{initials.slice(0, 1)}</span>
      </div>

      {/* Name — editable inline for local user */}
      {isYou && editing ? (
        <input
          ref={inputRef}
          autoFocus
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onBlur={handleBlur}
          onKeyDown={handleKeyDown}
          maxLength={32}
          style={styles.nameInput}
          onClick={e => e.stopPropagation()}
        />
      ) : (
        <span style={styles.name}>
          {user.name}
          {isYou && <span style={styles.youTag}> (you)</span>}
        </span>
      )}
    </div>
  );
}

const styles = {
  pills: {
    display: "flex",
    alignItems: "center",
    gap: "4px",
    marginRight: "8px",
  },
  pill: {
    display: "inline-flex",
    alignItems: "center",
    gap: "6px",
    padding: "3px 8px 3px 4px",
    borderRadius: "20px",
    border: "1px solid #E5E7EB",
    background: "#fff",
    fontSize: "12px",
    color: "#374151",
    transition: "border-color 0.15s",
    userSelect: "none",
  },
  dot: {
    width: "18px",
    height: "18px",
    borderRadius: "50%",
    flexShrink: 0,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
  },
  dotInitials: {
    fontSize: "9px",
    fontWeight: "600",
    color: "#fff",
    lineHeight: 1,
  },
  name: {
    maxWidth: "100px",
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
  },
  youTag: {
    color: "#9CA3AF",
    fontSize: "11px",
  },
  nameInput: {
    fontSize: "12px",
    border: "none",
    outline: "none",
    background: "transparent",
    width: "80px",
    color: "#111827",
    fontFamily: "inherit",
  },
  overflow: {
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
    padding: "3px 8px",
    borderRadius: "20px",
    border: "1px solid #E5E7EB",
    background: "#F9FAFB",
    fontSize: "12px",
    color: "#6B7280",
    cursor: "default",
  },
};
