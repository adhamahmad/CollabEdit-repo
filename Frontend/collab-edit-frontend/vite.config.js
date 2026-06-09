import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  define: {
    global: "window", // Polyfill global
  },
  plugins: [react()],
  server: {
    proxy: {
      // ── REST API ───────────────────────────────────────────────────
      // Proxies /api/* to the local backend, stripping the /api prefix.
      // Mirrors exactly what nginx.conf does in production so the
      // frontend code is identical in both environments.
      //
      // /api/          → http://localhost:8080/
      // /api/abc123    → http://localhost:8080/abc123
      "/api": {
        target: "http://localhost:8080",
        rewrite: (path) => path.replace(/^\/api/, ""),
        changeOrigin: true,
      },

      // ── WebSocket (STOMP) ──────────────────────────────────────────
      // Proxies the STOMP WebSocket endpoint to the local backend.
      // ws: true tells Vite to forward the WebSocket upgrade handshake
      // instead of treating this as a plain HTTP proxy — without it
      // the WebSocket connection silently falls back to HTTP and STOMP
      // never connects.
      //
      // changeOrigin rewrites the Origin header to match the target,
      // which prevents Spring's WebSocket origin check from rejecting
      // the connection during local dev.
      "/ws": {
        target: "http://localhost:8080",
        ws: true,
        changeOrigin: true,
      },
    },
  },
});