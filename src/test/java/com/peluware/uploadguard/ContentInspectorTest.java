package com.peluware.uploadguard;

import org.apache.tika.mime.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentInspectorTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ContentInspector inspector = new ContentInspector();

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    }

    /** Valid magic bytes followed by filler, to a total of {@code total} bytes. */
    private static byte[] padded(byte[] head, int total) {
        byte[] data = new byte[total];
        System.arraycopy(head, 0, data, 0, head.length);
        for (int i = head.length; i < total; i++) {
            data[i] = (byte) (i * 31);
        }
        return data;
    }

    private static byte[] zip(String firstEntry) {
        var out = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(firstEntry));
            zos.write("content".getBytes());
            zos.closeEntry();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    /** Detection takes a stream; the tests still think in terms of bytes. */
    private MediaType detect(byte[] data) {
        try {
            return inspector.detect(new BufferedInputStream(new ByteArrayInputStream(data), ContentInspector.DETECT_LENGTH));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private boolean matches(String declared, String detected) {
        return inspector.matches(MediaType.parse(declared), MediaType.parse(detected));
    }

    @Nested
    @DisplayName("spelling of media types")
    class Canonical {

        @ParameterizedTest
        @CsvSource({
                "image/jpeg, image/jpeg",
                "image/png, image/png",
                "IMAGE/PNG, image/png",
                "image/png; charset=binary, image/png",
                // the aliases Tika's own registry resolves
                "application/x-pdf, application/pdf",
                "application/x-zip-compressed, application/zip",
        })
        void resolves(String input, String expected) {
            assertEquals(MediaType.parse(expected), inspector.canonical(input));
        }

        /**
         * Deliberate: only the registry's aliases are resolved. Tika does not know the legacy
         * spellings, and a hand-written table of them here would mean guessing which ones matter. If a
         * signer ever uses one, the log names both types and the fix belongs in the signer.
         */
        @ParameterizedTest
        @ValueSource(strings = {"image/jpg", "image/pjpeg", "image/x-png"})
        void legacySpellingsAreLeftAlone(String input) {
            assertEquals(MediaType.parse(input), inspector.canonical(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void rejectsBlank(String input) {
            assertNull(inspector.canonical(input));
        }

        @Test
        void rejectsNull() {
            assertNull(inspector.canonical(null));
        }
    }

    @Nested
    @DisplayName("detection from the head of a body")
    class Detection {

        @Test
        void readsMagicBytes() {
            assertEquals(MediaType.image("png"), detect(png()));
            assertEquals(MediaType.image("gif"), detect("GIF89a....".getBytes()));
            assertEquals(MediaType.parse("application/pdf"), detect("%PDF-1.7\n%....".getBytes()));
            assertEquals(MediaType.parse("image/svg+xml"),
                    detect("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes()));
        }

        @Test
        void unknownBytesAreOctetStream() {
            assertEquals(MediaType.OCTET_STREAM, detect(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
            assertEquals(MediaType.OCTET_STREAM, detect(new byte[0]));
        }

        @Test
        void aPrefixCannotTellOoxmlFromAZip() {
            assertEquals(MediaType.application("x-tika-ooxml"), detect(zip("[Content_Types].xml")));
            assertEquals(MediaType.application("zip"), detect(zip("whatever.txt")));
        }

        /**
         * The handler forwards the very same stream it handed to detection, so a body that is not left
         * at its first byte would be uploaded truncated. This is the property that makes that safe.
         */
        @Test
        void leavesTheStreamAtTheFirstByte() throws IOException {
            byte[] data = padded(png(), 3 * ContentInspector.DETECT_LENGTH);
            var stream = new BufferedInputStream(new ByteArrayInputStream(data), ContentInspector.DETECT_LENGTH);

            assertEquals(MediaType.image("png"), inspector.detect(stream));
            assertArrayEquals(data, stream.readAllBytes(), "the whole body must still be readable");
        }

        /** What {@code detectBuffer} does for a tiny upload: a one-byte buffer still has to reset. */
        @Test
        void resetsWithABufferSizedToASmallBody() throws IOException {
            byte[] data = png();
            var stream = new BufferedInputStream(new ByteArrayInputStream(data), 1);

            assertEquals(MediaType.image("png"), inspector.detect(stream));
            assertArrayEquals(data, stream.readAllBytes());
        }
    }

    @Nested
    @DisplayName("the content contradicts the declaration")
    class Contradiction {

        @ParameterizedTest(name = "{0} declared, {1} detected")
        @CsvSource({
                "image/png, application/pdf",
                "image/jpeg, image/png",
                "application/pdf, image/png",
                "video/mp4, image/png",
                "image/png, text/plain",
                "image/png, application/zip",
        })
        void refusedOnEvidence(String declared, String detected) {
            assertFalse(matches(declared, detected));
        }

        /**
         * The invariant the whole service rests on. Every type descends from application/octet-stream
         * in Tika's registry, so treating it as a broader bucket would accept any binary under any
         * declared type. A fallback to it means no magic matched at all.
         */
        @ParameterizedTest
        @ValueSource(strings = {"image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
                DOCX, "application/json", "image/svg+xml"})
        void octetStreamIsNeverABroaderBucket(String declared) {
            assertFalse(matches(declared, "application/octet-stream"),
                    declared + " must not be accepted for undetectable bytes");
        }

        @Test
        void octetStreamStillMatchesItself() {
            assertTrue(matches("application/octet-stream", "application/octet-stream"));
        }

        @Test
        void nullsNeverMatch() {
            assertFalse(inspector.matches(null, MediaType.image("png")));
            assertFalse(inspector.matches(MediaType.image("png"), null));
        }
    }

    @Nested
    @DisplayName("the detection is merely less precise than the declaration")
    class NotContradicted {

        @Test
        void sameTypeMatches() {
            assertTrue(matches("image/png", "image/png"));
        }

        /**
         * These are the cases that make the rule more than an equality check. The signer decides which
         * types may be uploaded, so any of them can turn up here and must not be refused just because
         * a prefix cannot confirm the exact flavour.
         */
        @ParameterizedTest(name = "{0} declared, {1} detected")
        @CsvSource({
                DOCX + ", application/x-tika-ooxml",
                DOCX + ", application/zip",
                XLSX + ", application/x-tika-ooxml",
                "application/json, text/plain",
                "text/csv, text/plain",
                "image/svg+xml, application/xml",
                "image/svg+xml, text/plain",
        })
        void acceptedWithoutEvidenceAgainst(String declared, String detected) {
            assertTrue(matches(declared, detected));
        }

        @Test
        void realOoxmlBodyIsAccepted() {
            assertTrue(inspector.matches(MediaType.parse(DOCX), detect(zip("[Content_Types].xml"))));
        }
    }
}
