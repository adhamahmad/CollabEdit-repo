import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { PresencePills } from "../UserSidebar";
import { describe, it, expect, vi } from "vitest";

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures
// ─────────────────────────────────────────────────────────────────────────────

const LOCAL_USER_ID = "user-local";

const localIdentity = {
  userId: LOCAL_USER_ID,
  name: "Swift Fox",
  color: "#1E88E5",
};

function makeAwareness(overrides = {}) {
  return {
    [LOCAL_USER_ID]: {
      name: "Swift Fox",
      color: "#1E88E5",
      cursor: null,
    },
    ...overrides,
  };
}

function renderPills(
  awarenessOverrides = {},
  identity = localIdentity,
  onNameChange = vi.fn()
) {
  return render(
    <PresencePills
      awareness={makeAwareness(awarenessOverrides)}
      identity={identity}
      onNameChange={onNameChange}
    />
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendering
// ─────────────────────────────────────────────────────────────────────────────

describe("PresencePills — rendering", () => {
  it("renders the local user pill with '(you)' tag", () => {
    renderPills();
    expect(screen.getByText(/Swift Fox/)).toBeInTheDocument();
    expect(screen.getByText("(you)")).toBeInTheDocument();
  });

  it("renders remote users without additional '(you)' tags", () => {
    renderPills({
      "user-remote": { name: "Calm Otter", color: "#E53935", cursor: null },
    });
    expect(screen.getByText("Calm Otter")).toBeInTheDocument();
    expect(screen.getAllByText("(you)")).toHaveLength(1);
  });

  it("renders the first initial of the local user inside the color dot", () => {
    renderPills();
    // "Swift Fox" → first initial "S"
    expect(screen.getByText("S")).toBeInTheDocument();
  });

  it("renders correct initial for a remote user", () => {
    renderPills({
      "user-remote": { name: "Calm Otter", color: "#E53935", cursor: null },
    });
    expect(screen.getByText("C")).toBeInTheDocument();
  });

  it("renders multiple remote users", () => {
    renderPills({
      "user-b": { name: "Bold Wolf", color: "#E53935", cursor: null },
      "user-c": { name: "Dim Newt", color: "#43A047", cursor: null },
    });
    expect(screen.getByText("Bold Wolf")).toBeInTheDocument();
    expect(screen.getByText("Dim Newt")).toBeInTheDocument();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Overflow badge
// ─────────────────────────────────────────────────────────────────────────────

describe("PresencePills — overflow badge", () => {
  function buildExtraUsers(count) {
    const users = {};
    for (let i = 0; i < count; i++) {
      users[`user-extra-${i}`] = { name: `User ${i}`, color: "#757575", cursor: null };
    }
    return users;
  }

  it("does not render an overflow badge when total users ≤ 4", () => {
    // 1 local + 3 remote = 4 → no overflow
    renderPills(buildExtraUsers(3));
    expect(screen.queryByText(/^\+\d/)).not.toBeInTheDocument();
  });

  it("renders +1 overflow badge when 5 users are present", () => {
    // 1 local + 4 remote = 5 → +1
    renderPills(buildExtraUsers(4));
    expect(screen.getByText("+1")).toBeInTheDocument();
  });

  it("renders correct overflow count for many users", () => {
    // 1 local + 9 remote = 10 → +6
    renderPills(buildExtraUsers(9));
    expect(screen.getByText("+6")).toBeInTheDocument();
  });

  it("overflow badge has a descriptive title attribute", () => {
    renderPills(buildExtraUsers(4));
    expect(screen.getByText("+1")).toHaveAttribute("title", "1 more");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Sort order
// ─────────────────────────────────────────────────────────────────────────────

describe("PresencePills — sort order", () => {
  it("places local user before remote users regardless of key sort order", () => {
    renderPills({
      "aaa-comes-first-alphabetically": {
        name: "Aaa User",
        color: "#E53935",
        cursor: null,
      },
    });
    const allPills = screen.getAllByTitle(/Click to rename|Aaa User/);
    expect(allPills[0]).toHaveAttribute("title", "Click to rename");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Inline editing — local user
// ─────────────────────────────────────────────────────────────────────────────

describe("PresencePills — local user inline editing", () => {
  it("shows an input when the local pill is clicked", () => {
    renderPills();
    fireEvent.click(screen.getByTitle("Click to rename"));
    expect(screen.getByRole("textbox")).toBeInTheDocument();
  });

  it("pre-fills the input with the current name", () => {
    renderPills();
    fireEvent.click(screen.getByTitle("Click to rename"));
    expect(screen.getByRole("textbox")).toHaveValue("Swift Fox");
  });

  it("calls onNameChange with the trimmed value on blur", () => {
    const onNameChange = vi.fn();
    renderPills({}, localIdentity, onNameChange);
    fireEvent.click(screen.getByTitle("Click to rename"));
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "  Golden Crane  " } });
    fireEvent.blur(input);
    expect(onNameChange).toHaveBeenCalledWith("Golden Crane");
  });

  it("calls onNameChange when Enter is pressed", () => {
    const onNameChange = vi.fn();
    renderPills({}, localIdentity, onNameChange);
    fireEvent.click(screen.getByTitle("Click to rename"));
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "Iron Seal" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onNameChange).toHaveBeenCalledWith("Iron Seal");
  });

  it("does NOT call onNameChange when the name is unchanged", () => {
    const onNameChange = vi.fn();
    renderPills({}, localIdentity, onNameChange);
    fireEvent.click(screen.getByTitle("Click to rename"));
    fireEvent.blur(screen.getByRole("textbox"));
    expect(onNameChange).not.toHaveBeenCalled();
  });

  it("does NOT call onNameChange when input is blank or whitespace-only", () => {
    const onNameChange = vi.fn();
    renderPills({}, localIdentity, onNameChange);
    fireEvent.click(screen.getByTitle("Click to rename"));
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.blur(input);
    expect(onNameChange).not.toHaveBeenCalled();
  });

  it("dismisses the input and restores the original name on Escape", () => {
    renderPills();
    fireEvent.click(screen.getByTitle("Click to rename"));
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "something else" } });
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText(/Swift Fox/)).toBeInTheDocument();
  });

  it("hides the input after blur", () => {
    renderPills();
    fireEvent.click(screen.getByTitle("Click to rename"));
    fireEvent.blur(screen.getByRole("textbox"));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("enforces a 32-character maxLength on the input", () => {
    renderPills();
    fireEvent.click(screen.getByTitle("Click to rename"));
    expect(screen.getByRole("textbox")).toHaveAttribute("maxLength", "32");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Remote user pill — not editable
// ─────────────────────────────────────────────────────────────────────────────

describe("PresencePills — remote user pill", () => {
  it("does not open an input when a remote pill is clicked", () => {
    renderPills({
      "user-remote": { name: "Calm Otter", color: "#E53935", cursor: null },
    });
    fireEvent.click(screen.getByTitle("Calm Otter"));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("uses the user's full name as the tooltip title", () => {
    renderPills({
      "user-remote": { name: "Calm Otter", color: "#E53935", cursor: null },
    });
    expect(screen.getByTitle("Calm Otter")).toBeInTheDocument();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Name prop updates
// ─────────────────────────────────────────────────────────────────────────────

describe("PresencePills — name prop updates", () => {
  it("reflects updated name when awareness prop changes", () => {
    const { rerender } = render(
      <PresencePills
        awareness={{
          [LOCAL_USER_ID]: { name: "Swift Fox", color: "#1E88E5", cursor: null },
        }}
        identity={localIdentity}
        onNameChange={vi.fn()}
      />
    );

    rerender(
      <PresencePills
        awareness={{
          [LOCAL_USER_ID]: { name: "Rapid Heron", color: "#1E88E5", cursor: null },
        }}
        identity={{ ...localIdentity, name: "Rapid Heron" }}
        onNameChange={vi.fn()}
      />
    );

    expect(screen.getByText(/Rapid Heron/)).toBeInTheDocument();
  });

  it("opens the input with the updated name after a prop change while not editing", () => {
    const { rerender } = render(
      <PresencePills
        awareness={{
          [LOCAL_USER_ID]: { name: "Swift Fox", color: "#1E88E5", cursor: null },
        }}
        identity={localIdentity}
        onNameChange={vi.fn()}
      />
    );

    rerender(
      <PresencePills
        awareness={{
          [LOCAL_USER_ID]: { name: "Pale Crane", color: "#1E88E5", cursor: null },
        }}
        identity={{ ...localIdentity, name: "Pale Crane" }}
        onNameChange={vi.fn()}
      />
    );

    fireEvent.click(screen.getByTitle("Click to rename"));
    expect(screen.getByRole("textbox")).toHaveValue("Pale Crane");
  });
});