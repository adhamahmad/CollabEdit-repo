/**
 * exportText.js Tests
 *
 * What is tested:
 *   - downloadTextFile: Blob construction, anchor href/download attributes,
 *     DOM insertion/removal lifecycle, URL.revokeObjectURL cleanup
 *
 * Why valuable:
 *   The download function coordinates six DOM/browser API calls in a precise
 *   order. Any reorder causes silent failures (user gets no file, or gets a
 *   revoked URL). These tests pin the sequence of side effects.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { downloadTextFile } from "../exportText.js";

// jsdom does not implement URL.createObjectURL / revokeObjectURL.
// Install stubs once at module load; spies wrap them per-test.
if (typeof URL.createObjectURL === "undefined") {
  URL.createObjectURL = () => "blob:mock://polyfill-url";
}
if (typeof URL.revokeObjectURL === "undefined") {
  URL.revokeObjectURL = () => {};
}

// Helpers to intercept the anchor element created inside the function
function interceptAnchor(callback) {
  const origCreate = document.createElement.bind(document);
  const spy = vi.spyOn(document, "createElement").mockImplementation((tag) => {
    const el = origCreate(tag);
    if (tag === "a") callback(el);
    return el;
  });
  return spy;
}

describe("downloadTextFile", () => {
  let createObjectURLSpy;
  let revokeObjectURLSpy;
  let appendChildSpy;
  let removeChildSpy;

  beforeEach(() => {
    createObjectURLSpy = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:mock://test-url");
    revokeObjectURLSpy = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => {});
    appendChildSpy = vi
      .spyOn(document.body, "appendChild")
      .mockImplementation((el) => el);
    removeChildSpy = vi
      .spyOn(document.body, "removeChild")
      .mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ── Blob construction ────────────────────────────────────────────────────

  it("creates a Blob with text/plain;charset=utf-8 MIME type", () => {
    // We verify indirectly: createObjectURL is called with a Blob instance
    let capturedBlob;
    createObjectURLSpy.mockImplementation((blob) => {
      capturedBlob = blob;
      return "blob:mock://test-url";
    });
    downloadTextFile("some text", "doc.txt");
    expect(capturedBlob).toBeInstanceOf(Blob);
    expect(capturedBlob.type).toBe("text/plain;charset=utf-8");
  });

  it("passes the exact text content to the Blob", async () => {
    const content = "Line 1\nLine 2\r\nLine 3\ttabbed";
    let capturedBlob;
    createObjectURLSpy.mockImplementation((blob) => {
      capturedBlob = blob;
      return "blob:mock://test-url";
    });
    downloadTextFile(content, "doc.txt");
    // Read the blob back to verify content
    const text = await new Promise(res => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsText(capturedBlob); });
    expect(text).toBe(content);
  });

  // ── Anchor element ───────────────────────────────────────────────────────

  it("creates an anchor element", () => {
    const createSpy = vi.spyOn(document, "createElement");
    downloadTextFile("content", "out.txt");
    expect(createSpy).toHaveBeenCalledWith("a");
  });

  it("sets the download attribute to the provided filename", () => {
    let capturedAnchor;
    interceptAnchor((el) => { capturedAnchor = el; });
    downloadTextFile("content", "workspace-abc123.txt");
    expect(capturedAnchor.download).toBe("workspace-abc123.txt");
  });

  it("sets href to the object URL returned by createObjectURL", () => {
    let capturedAnchor;
    interceptAnchor((el) => { capturedAnchor = el; });
    downloadTextFile("content", "test.txt");
    // jsdom resolves relative hrefs; check it contains our mock URL
    expect(capturedAnchor.href).toContain("blob:mock://test-url");
  });

  // ── Side-effect ordering ─────────────────────────────────────────────────

  it("appends anchor to body BEFORE clicking it", () => {
    const callOrder = [];
    appendChildSpy.mockImplementation((el) => {
      callOrder.push("append");
      return el;
    });
    let capturedClick;
    interceptAnchor((el) => {
      capturedClick = vi.spyOn(el, "click").mockImplementation(() =>
        callOrder.push("click")
      );
    });

    downloadTextFile("x", "x.txt");
    expect(callOrder.indexOf("append")).toBeLessThan(callOrder.indexOf("click"));
  });

  it("removes anchor from body AFTER clicking it", () => {
    const callOrder = [];
    removeChildSpy.mockImplementation(() => { callOrder.push("remove"); });
    interceptAnchor((el) => {
      vi.spyOn(el, "click").mockImplementation(() => callOrder.push("click"));
    });

    downloadTextFile("x", "x.txt");
    expect(callOrder.indexOf("click")).toBeLessThan(callOrder.indexOf("remove"));
  });

  it("calls URL.revokeObjectURL with the blob URL to free memory", () => {
    downloadTextFile("content", "test.txt");
    expect(revokeObjectURLSpy).toHaveBeenCalledWith("blob:mock://test-url");
  });

  it("revokes the URL AFTER the anchor click", () => {
    const callOrder = [];
    revokeObjectURLSpy.mockImplementation(() => callOrder.push("revoke"));
    interceptAnchor((el) => {
      vi.spyOn(el, "click").mockImplementation(() => callOrder.push("click"));
    });

    downloadTextFile("x", "x.txt");
    expect(callOrder.indexOf("click")).toBeLessThan(callOrder.indexOf("revoke"));
  });

  // ── Edge cases ───────────────────────────────────────────────────────────

  it("handles empty string content without throwing", () => {
    expect(() => downloadTextFile("", "empty.txt")).not.toThrow();
  });

  it("handles unicode content without throwing", () => {
    expect(() =>
      downloadTextFile("日本語テスト 🎉", "unicode.txt")
    ).not.toThrow();
  });

  it("handles very long filenames without throwing", () => {
    const longName = "a".repeat(200) + ".txt";
    expect(() => downloadTextFile("data", longName)).not.toThrow();
  });
});
