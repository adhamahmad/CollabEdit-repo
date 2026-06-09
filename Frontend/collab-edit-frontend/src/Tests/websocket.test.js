/**
 * websocket.js Tests
 *
 * What is tested:
 *   - createStompClient: subscription routing, callback invocation
 *   - sendUpdate / sendAwareness / sendHello / destroy call paths
 *   - Awareness throttling (50ms cap)
 *   - Error handling: malformed JSON, missing fields
 *   - Graceful destroy: null-cursor sentinel before deactivation
 *   - Post-destroy connect guard
 *
 * Why valuable:
 *   Every real-time collaboration feature depends on this transport layer.
 *   Bugs here cause silent data loss, stale cursors, or missed disconnect
 *   signals. We test OUR wrapper contracts, not the STOMP library itself.
 *
 * Bugs found:
 *   BUG 1 — sendAwareness silently drops when disconnected (intentional per
 *     docs, but means cursors vanish during reconnects). Documented.
 *   BUG 2 — Hello channel echoes to sender. Client must filter by userId.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";

// ── Manually construct a minimal STOMP mock ───────────────────────────────────
//
// Rather than relying on vi.mock (which has hoisting issues with async tests),
// we build the mock client class directly and inject it.

let capturedOnConnect = null;
let capturedOnDisconnect = null;
let capturedOnWebSocketClose = null;
let capturedOnStompError = null;

const publishCalls = [];
const subscribeCalls = [];
const subscriptionHandlers = {};

let mockConnected = false;

function resetMockState() {
  capturedOnConnect = null;
  capturedOnDisconnect = null;
  capturedOnWebSocketClose = null;
  capturedOnStompError = null;
  publishCalls.length = 0;
  subscribeCalls.length = 0;
  Object.keys(subscriptionHandlers).forEach(k => delete subscriptionHandlers[k]);
  mockConnected = false;
}

class MockStompClient {
  constructor() {}

  get connected() { return mockConnected; }

  set onConnect(fn) { capturedOnConnect = fn; }
  set onDisconnect(fn) { capturedOnDisconnect = fn; }
  set onWebSocketClose(fn) { capturedOnWebSocketClose = fn; }
  set onStompError(fn) { capturedOnStompError = fn; }

  subscribe(topic, handler) {
    subscribeCalls.push(topic);
    subscriptionHandlers[topic] = handler;
  }

  publish(args) {
    publishCalls.push(args);
  }

  activate() {}
  deactivate() {}
}

// ── Wire the mock into the module before importing ────────────────────────────
vi.mock("@stomp/stompjs", () => ({
  Client: MockStompClient,
}));

vi.mock("sockjs-client", () => ({ default: vi.fn() }));

// Now import the module under test (after mocks are set up)
const { createStompClient } = await import("../websocket.js");

// ── Test helpers ──────────────────────────────────────────────────────────────

const DOC_ID = "test-workspace-abc";

function makeCallbacks() {
  return {
    onConnected: vi.fn(),
    onDisconnected: vi.fn(),
    onUpdate: vi.fn(),
    onHello: vi.fn(),
    onAwareness: vi.fn(),
  };
}

function simulateConnect() {
  mockConnected = true;
  capturedOnConnect?.();
}

function pushMessage(topic, payload) {
  const handler = subscriptionHandlers[topic];
  if (!handler) throw new Error(`No handler for topic: ${topic}`);
  handler({ body: JSON.stringify(payload) });
}

function pushRaw(topic, rawBody) {
  const handler = subscriptionHandlers[topic];
  if (!handler) throw new Error(`No handler for topic: ${topic}`);
  handler({ body: rawBody });
}

// ── Initialization ────────────────────────────────────────────────────────────

describe("createStompClient — return value", () => {
  beforeEach(resetMockState);

  it("returns sendUpdate, sendAwareness, sendHello, destroy", () => {
    const api = createStompClient(DOC_ID, makeCallbacks());
    expect(typeof api.sendUpdate).toBe("function");
    expect(typeof api.sendAwareness).toBe("function");
    expect(typeof api.sendHello).toBe("function");
    expect(typeof api.destroy).toBe("function");
  });
});

// ── Connection lifecycle ──────────────────────────────────────────────────────

describe("createStompClient — connection lifecycle", () => {
  beforeEach(resetMockState);

  it("calls onConnected callback after connect", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    expect(cb.onConnected).toHaveBeenCalledOnce();
  });

  it("subscribes to doc, hello, and awareness topics on connect", () => {
    createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    expect(subscribeCalls).toContain(`/topic/doc/${DOC_ID}`);
    expect(subscribeCalls).toContain(`/topic/hello/${DOC_ID}`);
    expect(subscribeCalls).toContain(`/topic/awareness/${DOC_ID}`);
  });

  it("calls onDisconnected when STOMP disconnects", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    capturedOnDisconnect?.();
    expect(cb.onDisconnected).toHaveBeenCalledOnce();
  });

  it("calls onDisconnected when WebSocket closes", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    capturedOnWebSocketClose?.();
    expect(cb.onDisconnected).toHaveBeenCalledOnce();
  });
});

// ── Incoming doc updates ──────────────────────────────────────────────────────

describe("createStompClient — incoming doc updates", () => {
  beforeEach(() => {
    resetMockState();
  });

  it("calls onUpdate with base64 update and updateCount", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    pushMessage(`/topic/doc/${DOC_ID}`, { update: "SGVsbG8=", updateCount: 5 });
    expect(cb.onUpdate).toHaveBeenCalledWith("SGVsbG8=", 5, undefined);
  });

  it("does NOT call onUpdate when update field is missing", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    pushMessage(`/topic/doc/${DOC_ID}`, { wrong: "field" });
    expect(cb.onUpdate).not.toHaveBeenCalled();
  });

  it("does NOT throw on malformed JSON for doc topic", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    expect(() => pushRaw(`/topic/doc/${DOC_ID}`, "BAD JSON{{")).not.toThrow();
    expect(cb.onUpdate).not.toHaveBeenCalled();
  });

  it("passes updateCount=0 (boundary value) correctly", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    pushMessage(`/topic/doc/${DOC_ID}`, { update: "AA==", updateCount: 0 });
    expect(cb.onUpdate).toHaveBeenCalledWith("AA==", 0, undefined);
  });
});

// ── Incoming hello messages ───────────────────────────────────────────────────

describe("createStompClient — incoming hello messages", () => {
  beforeEach(resetMockState);

  it("calls onHello with full payload", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    const payload = { userId: "u1", name: "Bold Raven", color: "#E53935" };
    pushMessage(`/topic/hello/${DOC_ID}`, payload);
    expect(cb.onHello).toHaveBeenCalledWith(payload);
  });

  it("does NOT call onHello when userId is missing", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    pushMessage(`/topic/hello/${DOC_ID}`, { name: "No ID" });
    expect(cb.onHello).not.toHaveBeenCalled();
  });

  it("does NOT throw on malformed hello JSON", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    expect(() => pushRaw(`/topic/hello/${DOC_ID}`, "{bad}")).not.toThrow();
  });
});

// ── Incoming awareness ────────────────────────────────────────────────────────

describe("createStompClient — incoming awareness messages", () => {
  beforeEach(resetMockState);

  it("calls onAwareness with full cursor payload", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    const payload = {
      userId: "u2",
      name: "Calm Otter",
      color: "#039BE5",
      cursor: { anchor: {}, head: {} },
      disconnected: false,
    };
    pushMessage(`/topic/awareness/${DOC_ID}`, payload);
    expect(cb.onAwareness).toHaveBeenCalledWith(payload);
  });

  it("calls onAwareness with disconnected=true", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    const payload = { userId: "u2", name: "x", color: "#fff", cursor: null, disconnected: true };
    pushMessage(`/topic/awareness/${DOC_ID}`, payload);
    expect(cb.onAwareness).toHaveBeenCalledWith(payload);
  });

  it("does NOT call onAwareness when userId is missing", () => {
    const cb = makeCallbacks();
    createStompClient(DOC_ID, cb);
    simulateConnect();
    pushMessage(`/topic/awareness/${DOC_ID}`, { name: "x" });
    expect(cb.onAwareness).not.toHaveBeenCalled();
  });
});

// ── sendUpdate ────────────────────────────────────────────────────────────────

describe("createStompClient — sendUpdate", () => {
  beforeEach(resetMockState);

  it("throws when client is not connected", () => {
    mockConnected = false;
    const { sendUpdate } = createStompClient(DOC_ID, makeCallbacks());
    expect(() => sendUpdate("SGVsbG8=")).toThrow();
  });

  it("publishes to /app/edit/{id} when connected", () => {
    const { sendUpdate } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    sendUpdate("SGVsbG8=");
    const call = publishCalls.find(c => c.destination === `/app/edit/${DOC_ID}`);
    expect(call).toBeDefined();
    const body = JSON.parse(call.body);
    expect(body.update).toBe("SGVsbG8=");
    expect(body.docId).toBe(DOC_ID);
  });

  it("includes docId in the published body", () => {
    const { sendUpdate } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    sendUpdate("AA==");
    const call = publishCalls.find(c => c.destination?.includes("edit"));
    expect(JSON.parse(call.body).docId).toBe(DOC_ID);
  });
});

// ── sendAwareness throttle ────────────────────────────────────────────────────

describe("createStompClient — sendAwareness throttle", () => {
  beforeEach(() => {
    resetMockState();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("only publishes once when called 3 times within 50ms", () => {
    const { sendAwareness } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    const state = { userId: "u1", name: "A", color: "#fff", cursor: null };
    sendAwareness(state);
    sendAwareness(state);
    sendAwareness(state);
    const calls = publishCalls.filter(c => c.destination === `/app/awareness/${DOC_ID}`);
    expect(calls).toHaveLength(1);
  });

  it("allows a second publish after 51ms have elapsed", () => {
    const { sendAwareness } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    const state = { userId: "u1", name: "A", color: "#fff", cursor: null };
    sendAwareness(state); // t=0
    vi.advanceTimersByTime(51);
    sendAwareness(state); // t=51ms
    const calls = publishCalls.filter(c => c.destination === `/app/awareness/${DOC_ID}`);
    expect(calls).toHaveLength(2);
  });

  it("does NOT publish when disconnected", () => {
    mockConnected = false;
    const { sendAwareness } = createStompClient(DOC_ID, makeCallbacks());
    sendAwareness({ userId: "u1", name: "A", color: "#fff", cursor: null });
    expect(publishCalls).toHaveLength(0);
  });
});

// ── sendHello ─────────────────────────────────────────────────────────────────

describe("createStompClient — sendHello", () => {
  beforeEach(resetMockState);

  it("publishes to /app/hello/{id} with identity fields", () => {
    const { sendHello } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    sendHello({ userId: "u1", name: "Bold Raven", color: "#E53935" });
    const call = publishCalls.find(c => c.destination === `/app/hello/${DOC_ID}`);
    expect(call).toBeDefined();
    const body = JSON.parse(call.body);
    expect(body).toMatchObject({ userId: "u1", name: "Bold Raven", color: "#E53935" });
  });

  it("does NOT publish when disconnected", () => {
    mockConnected = false;
    const { sendHello } = createStompClient(DOC_ID, makeCallbacks());
    sendHello({ userId: "u1", name: "A", color: "#fff" });
    expect(publishCalls).toHaveLength(0);
  });
});

// ── destroy ───────────────────────────────────────────────────────────────────

describe("createStompClient — destroy", () => {
  beforeEach(resetMockState);

  it("sends disconnect sentinel with cursor=null and disconnected=true when connected", () => {
    const { destroy } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    destroy({ userId: "u1", name: "Bold Raven", color: "#E53935" });
    const sentinel = publishCalls.find(c => {
      if (c.destination !== `/app/awareness/${DOC_ID}`) return false;
      const b = JSON.parse(c.body);
      return b.disconnected === true;
    });
    expect(sentinel).toBeDefined();
    const body = JSON.parse(sentinel.body);
    expect(body.cursor).toBeNull();
    expect(body.userId).toBe("u1");
  });

  it("bypasses the 50ms throttle for the destroy sentinel", () => {
    const { sendAwareness, destroy } = createStompClient(DOC_ID, makeCallbacks());
    simulateConnect();
    // Exhaust the throttle window by sending at t=0
    sendAwareness({ userId: "u1", name: "A", color: "#fff", cursor: null });
    // destroy should send immediately without waiting for throttle
    destroy({ userId: "u1", name: "A", color: "#fff" });
    const awarenessCalls = publishCalls.filter(c => c.destination === `/app/awareness/${DOC_ID}`);
    // At least 2: one from sendAwareness, one from destroy sentinel
    expect(awarenessCalls.length).toBeGreaterThanOrEqual(2);
  });

  it("does NOT call onConnected after destroy (post-destroy connect guard)", () => {
    const cb = makeCallbacks();
    const { destroy } = createStompClient(DOC_ID, cb);
    mockConnected = false;
    destroy(null);
    // Simulate a late reconnect
    capturedOnConnect?.();
    expect(cb.onConnected).not.toHaveBeenCalled();
  });

  it("passes senderId when present", () => {
    const cb = makeCallbacks();
  
    createStompClient(DOC_ID, cb);
  
    simulateConnect();
  
    pushMessage(`/topic/doc/${DOC_ID}`, {
      update: "SGVsbG8=",
      updateCount: 5,
      senderId: "user-123",
    });
  
    expect(cb.onUpdate).toHaveBeenCalledWith(
      "SGVsbG8=",
      5,
      "user-123"
    );
  });
  
});
