package com.example.demo.service;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * PublicUrlGeneratorTest — unit tests for PublicUrlGenerator.
 *
 * What is tested:
 *   - Output is a valid URL-safe Base64 string (no +, /, or = padding)
 *   - Output length is exactly 16 characters (12 bytes → 16 Base64 chars)
 *   - Output character set is strictly [A-Za-z0-9_-]
 *   - No two calls produce the same ID (collision resistance across 10,000 samples)
 *   - The generator is usable immediately after construction (no warm-up required)
 *
 * Why valuable:
 *   The generated ID is used directly as the workspace URL path segment and
 *   as the Caffeine cache key. If IDs contain characters outside [A-Za-z0-9_-]
 *   they will either fail the controller's path regex {16,22} or cause cache
 *   key collisions. The collision test gives statistical confidence — 10,000
 *   samples from a 96-bit space have a collision probability of ~10^-23.
 */
class PublicUrlGeneratorTest {

    // URL-safe Base64 alphabet — no +, /, or = padding
    private static final Pattern URL_SAFE_B64 = Pattern.compile("^[A-Za-z0-9_-]+$");

    // 12 bytes → ceil(12 * 4/3) = 16 chars (no padding because withoutPadding())
    private static final int EXPECTED_LENGTH = 16;

    private PublicUrlGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new PublicUrlGenerator();
    }

    // ── Output format ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Output format")
    class OutputFormat {

        @Test
        @DisplayName("generated ID is not null")
        void notNull() {
            assertThat(generator.generate()).isNotNull();
        }

        @Test
        @DisplayName("generated ID is not blank")
        void notBlank() {
            assertThat(generator.generate()).isNotBlank();
        }

        @Test
        @DisplayName("generated ID is exactly 16 characters long")
        void exactLength() {
            assertThat(generator.generate()).hasSize(EXPECTED_LENGTH);
        }

        @Test
        @DisplayName("generated ID contains only URL-safe Base64 characters [A-Za-z0-9_-]")
        void urlSafeCharset() {
            String id = generator.generate();
            assertThat(id).matches(URL_SAFE_B64);
        }

        @Test
        @DisplayName("generated ID contains no standard Base64 padding '='")
        void noPadding() {
            assertThat(generator.generate()).doesNotContain("=");
        }

        @Test
        @DisplayName("generated ID contains no standard Base64 '+' character")
        void noPlus() {
            assertThat(generator.generate()).doesNotContain("+");
        }

        @Test
        @DisplayName("generated ID contains no standard Base64 '/' character")
        void noSlash() {
            assertThat(generator.generate()).doesNotContain("/");
        }

        @Test
        @DisplayName("generated ID is decodable back to 12 bytes by URL-safe Base64 decoder")
        void decodableTo12Bytes() {
            String id = generator.generate();
            byte[] decoded = Base64.getUrlDecoder().decode(id);
            assertThat(decoded).hasSize(12);
        }

        @RepeatedTest(20)
        @DisplayName("format is consistent across repeated calls")
        void formatConsistency() {
            String id = generator.generate();
            assertThat(id)
                    .hasSize(EXPECTED_LENGTH)
                    .matches(URL_SAFE_B64);
        }
    }

    // ── Path regex compatibility ──────────────────────────────────────────────

    @Nested
    @DisplayName("Controller path regex compatibility")
    class PathRegexCompatibility {

        // The WorkspaceController uses: {id:[A-Za-z0-9_-]{16,22}}
        private static final Pattern CONTROLLER_REGEX =
                Pattern.compile("^[A-Za-z0-9_-]{16,22}$");

        @RepeatedTest(50)
        @DisplayName("every generated ID matches the controller path regex {16,22}")
        void matchesControllerRegex() {
            assertThat(generator.generate()).matches(CONTROLLER_REGEX);
        }
    }

    // ── Uniqueness / collision resistance ─────────────────────────────────────

    @Nested
    @DisplayName("Uniqueness")
    class Uniqueness {

        @Test
        @DisplayName("two consecutive calls produce different IDs")
        void twoCallsDiffer() {
            assertThat(generator.generate()).isNotEqualTo(generator.generate());
        }

        @Test
        @DisplayName("10,000 generated IDs are all unique (no collisions)")
        void noCollisionsAcross10kSamples() {
            int sampleSize = 10_000;
            Set<String> seen = new HashSet<>(sampleSize * 2);

            for (int i = 0; i < sampleSize; i++) {
                String id = generator.generate();
                boolean added = seen.add(id);
                assertThat(added)
                        .as("Collision detected at iteration %d: id=%s", i, id)
                        .isTrue();
            }
        }
    }
}
