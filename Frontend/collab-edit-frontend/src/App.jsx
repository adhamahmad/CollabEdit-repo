import { useEffect, useState, useCallback, useRef } from "react";
import { Routes, Route, useNavigate, useParams } from "react-router-dom";
import Editor from "./editor";
import { PresencePills } from "./UserSidebar";
import { getOrCreateIdentity, updateIdentityName } from "./Identity.js";
import { downloadTextFile } from "./exportText";

// ─── Navbar ───────────────────────────────────────────────────────────────────

function Navbar({ onShare, onDownload, shareStatus, awareness, identity, onNameChange }) {
  return (
    <header style={nav.bar}>
      {/* Logo */}
      <div style={nav.logo}>
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <rect width="24" height="24" rx="6" fill="#2563EB" />
          <path
            d="M16.5 7.5H10.5C9.12 7.5 8 8.62 8 10s1.12 2.5 2.5 2.5h3c1.38 0 2.5 1.12 2.5 2.5s-1.12 2.5-2.5 2.5H7.5"
            fill="none"
            stroke="#FFFFFF"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle cx="16.5" cy="7.5" r="1.1" fill="#FFFFFF" />
          <circle cx="7.5" cy="17.5" r="1.1" fill="#FFFFFF" />
        </svg>

        <span style={nav.logoText}>SyncPad</span>
      </div>

      {/* Right side */}
      <div style={nav.actions}>
        <PresencePills
          awareness={awareness}
          identity={identity}
          onNameChange={onNameChange}
        />

        {/* GitHub — icon only */}
        <a
          href="https://github.com/adhamahmad/CollabEdit-repo"
          target="_blank"
          rel="noopener noreferrer"
          style={nav.ghostBtn}
          title="View on GitHub"
        >
          <GithubIcon />
        </a>

        {/* Download — ghost */}
        <button onClick={onDownload} style={nav.ghostBtn} title="Download as TXT">
          <DownloadIcon />
          <span style={nav.btnLabel}>Download</span>
        </button>

        {/* Share — primary CTA */}
        <div style={{ position: "relative" }}>
          <button onClick={onShare} style={nav.shareBtn}>
            <ShareIcon />
            Share
          </button>
          {shareStatus && (
            <div style={nav.toast} role="status" aria-live="polite">
              {shareStatus}
            </div>
          )}
        </div>
      </div>
    </header>
  );
}

// ─── Icons ────────────────────────────────────────────────────────────────────

function GithubIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0 1 12 6.844a9.59 9.59 0 0 1 2.504.337c1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.02 10.02 0 0 0 22 12.017C22 6.484 17.522 2 12 2z" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  );
}

function ShareIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
      <polyline points="16 6 12 2 8 6" />
      <line x1="12" y1="2" x2="12" y2="15" />
    </svg>
  );
}

// ─── Workspace ────────────────────────────────────────────────────────────────

function Workspace() {
  const [shareStatus, setShareStatus] = useState(null);
  const shareTimerRef = useRef(null);
  const editorApiRef = useRef(null);
  const { id } = useParams();
  const navigate = useNavigate();
  const [updates, setUpdates] = useState(null);
  const [identity, setIdentity] = useState(() => getOrCreateIdentity());
  const [awareness, setAwareness] = useState(() => {
    const id = getOrCreateIdentity();
    return { [id.userId]: { name: id.name, color: id.color, cursor: null } };
  });

  const handleShare = useCallback(() => {
    navigator.clipboard.writeText(window.location.href)
      .then(() => setShareStatus("Link copied"))
      .catch(() => setShareStatus("Copy failed"))
      .finally(() => {
        clearTimeout(shareTimerRef.current);
        shareTimerRef.current = setTimeout(() => setShareStatus(null), 2000);
      });
  }, []);

  const handleDownload = useCallback(() => {
    if (!editorApiRef.current) return;
    const text = editorApiRef.current.exportAsText();
    downloadTextFile(text, `workspace-${id}.txt`);
  }, [id]);

  useEffect(() => () => clearTimeout(shareTimerRef.current), []);

  useEffect(() => {
    fetch(`/api/${id}`)
      .then(res => {
        if (res.status === 404) { navigate("/"); return null; }
        return res.json();
      })
      .then(data => { if (data) setUpdates(data.updates || []); })
      .catch(err => { console.error("Failed to load workspace:", err); setUpdates([]); });
  }, [id, navigate]);

  const handleAwarenessUpdate = useCallback((state) => {
    const { userId, name, color, cursor, disconnected } = state;
    if (disconnected) {
      setAwareness(prev => {
        if (!prev[userId]) return prev;
        const next = { ...prev }; delete next[userId]; return next;
      });
      return;
    }
    setAwareness(prev => ({
      ...prev,
      [userId]: { ...(prev[userId] ?? {}), name, color, cursor },
    }));
  }, []);

  const handleNameChange = useCallback((newName) => {
    setIdentity(prev => {
      const updated = updateIdentityName(prev, newName);
      setAwareness(a => ({
        ...a,
        [updated.userId]: { ...(a[updated.userId] ?? {}), name: newName },
      }));
      return updated;
    });
  }, []);

  if (updates === null) {
    return (
      <div style={styles.loadingScreen}>
        <div style={styles.loadingDot} />
      </div>
    );
  }

  return (
    <div style={styles.root}>
      <Navbar
        onShare={handleShare}
        onDownload={handleDownload}
        shareStatus={shareStatus}
        awareness={awareness}
        identity={identity}
        onNameChange={handleNameChange}
      />

      <main style={styles.main}>
        <div style={styles.editorCard}>
          <Editor
            editorApiRef={editorApiRef}
            initialUpdates={updates}
            workspaceId={id}
            identity={identity}
            onAwarenessUpdate={handleAwarenessUpdate}
          />
        </div>
        <p style={styles.hint}>Everything syncs instantly.</p>
      </main>
    </div>
  );
}

// ─── Home ─────────────────────────────────────────────────────────────────────

function Home() {
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (creating) return;
    setCreating(true);
    fetch("/api/")
      .then(res => res.json())
      .then(data => navigate(`/${data.id}`))
      .catch(err => { console.error("Failed to create workspace:", err); setCreating(false); });
  }, [navigate, creating]);

  return (
    <div style={styles.loadingScreen}>
      <div style={styles.loadingDot} />
    </div>
  );
}

// ─── App ──────────────────────────────────────────────────────────────────────

function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/:id" element={<Workspace />} />
    </Routes>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const nav = {
  bar: {
    position: "sticky",
    top: 0,
    zIndex: 100,
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    padding: "0 24px",
    height: "52px",
    background: "#FAFAF9",
    borderBottom: "1px solid #E5E7EB",
  },
  logo: {
    display: "flex",
    alignItems: "center",
    gap: "8px",
  },
  logoText: {
    fontSize: "15px",
    fontWeight: "500",
    color: "#111827",
    letterSpacing: "-0.01em",
  },
  actions: {
    display: "flex",
    alignItems: "center",
    gap: "6px",
  },
  ghostBtn: {
    display: "inline-flex",
    alignItems: "center",
    gap: "5px",
    padding: "5px 10px",
    fontSize: "13px",
    fontWeight: "400",
    color: "#6B7280",
    background: "transparent",
    border: "1px solid #E5E7EB",
    borderRadius: "6px",
    cursor: "pointer",
    transition: "border-color 0.15s, color 0.15s",
    textDecoration: "none",
  },
  btnLabel: {
    fontSize: "13px",
  },
  shareBtn: {
    display: "inline-flex",
    alignItems: "center",
    gap: "6px",
    padding: "5px 14px",
    fontSize: "13px",
    fontWeight: "500",
    color: "#fff",
    background: "#2563EB",
    border: "1px solid #2563EB",
    borderRadius: "6px",
    cursor: "pointer",
    transition: "background 0.15s",
  },
  toast: {
    position: "absolute",
    top: "calc(100% + 8px)",
    right: 0,
    background: "#111827",
    color: "#fff",
    fontSize: "12px",
    padding: "5px 10px",
    borderRadius: "6px",
    whiteSpace: "nowrap",
    pointerEvents: "none",
    zIndex: 200,
  },
};

const styles = {
  root: {
    minHeight: "100vh",
    background: "#FAFAF9",
    display: "flex",
    flexDirection: "column",
  },
  main: {
    flex: 1,
    display: "flex",
    flexDirection: "column",
    alignItems: "center",
    padding: "32px 24px 64px",
  },
  editorCard: {
    width: "100%",
    maxWidth: "1200px",
    height: "calc(100vh - 160px)",
    background: "#FFFFFF",
    border: "1px solid #E5E7EB",
    borderRadius: "10px",
    display: "flex",
    flexDirection: "column",
    overflow: "hidden",
    boxShadow: "0 1px 3px rgba(0,0,0,0.04)",
  },
  hint: {
    marginTop: "16px",
    fontSize: "13px",
    color: "#9CA3AF",
    fontWeight: "400",
  },
  loadingScreen: {
    minHeight: "100vh",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    background: "#FAFAF9",
  },
  loadingDot: {
    width: "8px",
    height: "8px",
    borderRadius: "50%",
    background: "#D1D5DB",
    animation: "pulse 1.4s ease-in-out infinite",
  },
};

export default App;
