package io.splicer.web.rest.errors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BaseExceptionTranslator#stripNonAscii(String)}.
 * Validates ASCII sanitization that prevents Cloudflare 520 errors.
 */
class BaseExceptionTranslatorTest {

    @Test
    void stripNonAscii_asciiPassesThrough() {
        assertEquals("Hello World 123!", BaseExceptionTranslator.stripNonAscii("Hello World 123!"));
    }

    @Test
    void stripNonAscii_emDashStripped() {
        // The actual Cloudflare 520 bug: em dash U+2014 in error message header
        assertEquals("value  here", BaseExceptionTranslator.stripNonAscii("value \u2014 here"));
    }

    @Test
    void stripNonAscii_mixedContent() {
        assertEquals("abc123", BaseExceptionTranslator.stripNonAscii("a\u00E9b\u00F1c\u2019123"));
    }

    @Test
    void stripNonAscii_null() {
        assertNull(BaseExceptionTranslator.stripNonAscii(null));
    }

    @Test
    void stripNonAscii_controlCharsStripped() {
        // Control chars below 0x20 (tab, newline, etc.) should be stripped
        assertEquals("ab", BaseExceptionTranslator.stripNonAscii("a\tb\n"));
    }

    @Test
    void stripNonAscii_emptyString() {
        assertEquals("", BaseExceptionTranslator.stripNonAscii(""));
    }

    @Test
    void stripNonAscii_spacePreserved() {
        // Space (0x20) is the lowest preserved char
        assertEquals(" ", BaseExceptionTranslator.stripNonAscii(" "));
    }

    @Test
    void stripNonAscii_tildePreserved() {
        // Tilde (0x7E) is the highest preserved char
        assertEquals("~", BaseExceptionTranslator.stripNonAscii("~"));
    }

    @Test
    void stripNonAscii_delStripped() {
        // DEL (0x7F) should be stripped
        assertEquals("ab", BaseExceptionTranslator.stripNonAscii("a\u007Fb"));
    }
}
