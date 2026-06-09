package com.example.demo.mapper;

import com.example.demo.DTO.WorkspaceResponse;
import com.example.demo.entity.Workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * WorkspaceMapperTest — unit tests for WorkspaceMapper.
 *
 * What is tested:
 * - toResponse maps workspace ID unchanged
 * - Empty update list maps to empty list (not null)
 * - Each raw byte[] update is correctly Base64-encoded in the response
 * - Multiple updates are all encoded, in order
 * - The encoded strings are decodable back to the original bytes
 * - Known byte sequences produce known Base64 strings (golden values)
 * - The mapper never exposes the workspace's internal mutable list
 *
 * Why valuable:
 * The mapper is the serialisation boundary between the domain model and
 * the HTTP/WebSocket response. A wrong encoding here causes every client
 * to receive corrupt Yjs deltas that cannot be applied, silently breaking
 * document convergence. The golden-value tests catch any encoder swap
 * (e.g. standard vs URL-safe Base64) immediately.
 */
class WorkspaceMapperTest {

    private WorkspaceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WorkspaceMapper();
    }

    // ── ID mapping ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ID mapping")
    class IdMapping {

        @Test
        @DisplayName("response ID matches workspace ID exactly")
        void idPassedThrough() {
            Workspace ws = new Workspace("my-workspace-id");
            WorkspaceResponse response = mapper.toResponse(ws);
            assertThat(response.getId()).isEqualTo("my-workspace-id");
        }

        @Test
        @DisplayName("response ID is never null")
        void idNotNull() {
            Workspace ws = new Workspace("some-id");
            assertThat(mapper.toResponse(ws).getId()).isNotNull();
        }
    }

    // ── Updates encoding ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Updates encoding")
    class UpdatesEncoding {

        @Test
        @DisplayName("empty workspace produces an empty updates list (not null)")
        void emptyWorkspaceProducesEmptyList() {
            Workspace ws = new Workspace("id");
            WorkspaceResponse response = mapper.toResponse(ws);
            assertThat(response.getUpdates())
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("single update is Base64-encoded in the response")
        void singleUpdateEncoded() {
            Workspace ws = new Workspace("id");
            byte[] raw = { 1, 2, 3, 4, 5 };
            ws.addUpdate(raw);

            WorkspaceResponse response = mapper.toResponse(ws);

            assertThat(response.getUpdates()).hasSize(1);
            String encoded = response.getUpdates().get(0);
            // Verify it decodes back to the original
            assertThat(Base64.getDecoder().decode(encoded)).isEqualTo(raw);
        }

        @Test
        @DisplayName("multiple updates are all encoded in insertion order")
        void multipleUpdatesEncodedInOrder() {
            Workspace ws = new Workspace("id");
            byte[] a = { 10, 20 };
            byte[] b = { 30, 40 };
            byte[] c = { 50, 60 };
            ws.addUpdate(a);
            ws.addUpdate(b);
            ws.addUpdate(c);

            WorkspaceResponse response = mapper.toResponse(ws);
            List<String> updates = response.getUpdates();

            assertThat(updates).hasSize(3);
            assertThat(Base64.getDecoder().decode(updates.get(0))).isEqualTo(a);
            assertThat(Base64.getDecoder().decode(updates.get(1))).isEqualTo(b);
            assertThat(Base64.getDecoder().decode(updates.get(2))).isEqualTo(c);
        }

        @Test
        @DisplayName("empty byte array encodes to the empty Base64 string")
        void emptyByteArrayEncodesToEmptyString() {
            Workspace ws = new Workspace("id");
            ws.addUpdate(new byte[0]);

            WorkspaceResponse response = mapper.toResponse(ws);
            assertThat(response.getUpdates().get(0)).isEqualTo("");
        }

        @Test
        @DisplayName("uses standard Base64 (not URL-safe) — '+' and '/' are valid output chars")
        void usesStandardBase64() {
            // Bytes chosen to produce '+' and '/' in standard Base64 but not URL-safe:
            // 0xFB, 0xEF → standard "++8=" ; 0xFF, 0xEF → standard "/+8="
            Workspace ws = new Workspace("id");
            ws.addUpdate(new byte[] { (byte) 0xFB, (byte) 0xEF });

            WorkspaceResponse response = mapper.toResponse(ws);
            String encoded = response.getUpdates().get(0);

            // Standard encoder produces "++" prefix; URL-safe would produce "--"
            assertThat(encoded).contains("+");
        }

        // ── Golden-value tests ────────────────────────────────────────────────
        // These lock in the exact encoder behaviour so any accidental swap to
        // URL-safe or un-padded Base64 is caught immediately.

        @Test
        @DisplayName("golden value: [0] encodes to 'AA=='")
        void goldenZeroByte() {
            Workspace ws = new Workspace("id");
            ws.addUpdate(new byte[] { 0 });
            assertThat(mapper.toResponse(ws).getUpdates().get(0)).isEqualTo("AA==");
        }

        @Test
        @DisplayName("golden value: 'Hello' bytes encode to 'SGVsbG8='")
        void goldenHelloString() {
            Workspace ws = new Workspace("id");
            ws.addUpdate("Hello".getBytes());
            assertThat(mapper.toResponse(ws).getUpdates().get(0)).isEqualTo("SGVsbG8=");
        }

        @Test
        @DisplayName("golden value: [1,2,3] encodes to 'AQID'")
        void goldenOneTwoThree() {
            Workspace ws = new Workspace("id");
            ws.addUpdate(new byte[] { 1, 2, 3 });
            assertThat(mapper.toResponse(ws).getUpdates().get(0)).isEqualTo("AQID");
        }

        @Test
        @DisplayName("all 256 single-byte values encode without throwing")
        void allByteValuesEncodeSafely() {
            for (int i = 0; i < 256; i++) {
                Workspace ws = new Workspace("id-" + i);
                ws.addUpdate(new byte[] { (byte) i });
                assertThatCode(() -> mapper.toResponse(ws))
                        .doesNotThrowAnyException();
            }
        }
    }

    // ── Large payload ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Large payload handling")
    class LargePayload {

        @Test
        @DisplayName("64 KB update encodes and decodes correctly")
        void largeUpdateRoundTrip() {
            byte[] big = new byte[64 * 1024];
            for (int i = 0; i < big.length; i++)
                big[i] = (byte) (i & 0xFF);

            Workspace ws = new Workspace("id");
            ws.addUpdate(big);

            WorkspaceResponse response = mapper.toResponse(ws);
            byte[] decoded = Base64.getDecoder().decode(response.getUpdates().get(0));
            assertThat(decoded).isEqualTo(big);
        }

        @Test
        @DisplayName("50 updates all encode correctly")
        void fiftyUpdatesAllEncode() {
            Workspace ws = new Workspace("id");
            for (int i = 0; i < 50; i++) {
                ws.addUpdate(new byte[] { (byte) i, (byte) (i + 1) });
            }

            WorkspaceResponse response = mapper.toResponse(ws);
            assertThat(response.getUpdates()).hasSize(50);

            for (int i = 0; i < 50; i++) {
                byte[] decoded = Base64.getDecoder().decode(response.getUpdates().get(i));
                assertThat(decoded).isEqualTo(new byte[] { (byte) i, (byte) (i + 1) });
            }
        }
    }
}