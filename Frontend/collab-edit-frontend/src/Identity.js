// Adjectives and animals chosen to be readable and visually distinct.
// The generated names are only awareness/presence labels,
// not authenticated identities.
const ADJECTIVES = [
    "Silent", "Crimson", "Swift", "Amber", "Hollow",
    "Brisk", "Pale", "Dusty", "Calm", "Bold",
    "Dim", "Stark", "Silver", "Golden", "Shadow",
    "Iron", "Rapid", "Frozen", "Wild", "Quiet"
];

const ANIMALS = [
    "Otter", "Fox", "Panda", "Crane", "Lynx",
    "Raven", "Bison", "Moth", "Newt", "Vole",
    "Ibis", "Dace", "Falcon", "Wolf", "Badger",
    "Heron", "Seal", "Cobra", "Orca", "Wren"
];

// Curated palette designed for:
// - cursor visibility
// - sidebar readability
// - visual distinction
// - reasonable light/dark theme compatibility
const COLORS = [
    "#E53935", // red
    "#D81B60", // pink
    "#8E24AA", // purple
    "#5E35B1", // deep purple
    "#3949AB", // indigo
    "#1E88E5", // blue
    "#039BE5", // light blue
    "#00ACC1", // cyan
    "#00897B", // teal
    "#43A047", // green
    "#7CB342", // light green
    "#C0CA33", // lime
    "#FDD835", // yellow
    "#FFB300", // amber
    "#FB8C00", // orange
    "#F4511E", // deep orange

    "#6D4C41", // brown
    "#757575", // gray
    "#546E7A", // blue gray

    "#EC407A", // bright pink
    "#AB47BC", // bright purple
    "#26A69A", // bright teal
    "#9CCC65", // bright olive
    "#FFA726", // bright orange
];

const STORAGE_KEY = "collab_identity";

function randomItem(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function generateName() {
    return `${randomItem(ADJECTIVES)} ${randomItem(ANIMALS)}`;
}

// Uses the browser crypto API instead of Math.random()
// for significantly stronger uniqueness guarantees.
function generateId() {
    return crypto.randomUUID();
}

// Deterministically maps a string to a stable 32-bit integer.
// Same input always produces same output.
function hashString(str) {
    let hash = 0;

    for (let i = 0; i < str.length; i++) {
        hash = (hash * 31 + str.charCodeAt(i)) >>> 0;
    }

    return hash;
}

// Deterministically assigns a color from the palette.
// This avoids random color changes across refreshes/reconnects.
//
// NOTE:
// Collisions are still mathematically possible because
// the number of users can exceed the number of colors.
// The goal here is stability and distribution,
// not guaranteed uniqueness.
function generateColor(userId) {
    return COLORS[hashString(userId) % COLORS.length];
}

// Validates the stored identity shape before trusting it.
function isValidIdentity(identity) {
    return (
        identity &&
        typeof identity.userId === "string" &&
        typeof identity.name === "string" &&
        typeof identity.color === "string"
    );
}

// Returns the identity for this browser tab/session.
//
// sessionStorage is intentional:
// - refresh keeps identity
// - new tab gets a new collaborator identity
// - avoids multiple tabs sharing awareness state
export function getOrCreateIdentity() {
    const stored = sessionStorage.getItem(STORAGE_KEY);

    if (stored) {
        try {
            const parsed = JSON.parse(stored);

            if (isValidIdentity(parsed)) {
                return parsed;
            }
        } catch {
            // Corrupted storage — fall through and regenerate.
        }
    }

    const userId = generateId();

    const identity = {
        userId,
        name: generateName(),
        color: generateColor(userId),
    };

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(identity));

    return identity;
}

// Updates and persists the user's display name.
export function updateIdentityName(identity, newName) {
    const trimmed = newName.trim();

    // Ignore empty/whitespace-only names.
    if (!trimmed) {
        return identity;
    }

    // Prevent extremely long names from breaking the UI.
    const safeName = trimmed.slice(0, 32);

    const updated = {
        ...identity,
        name: safeName,
    };

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(updated));

    return updated;
}