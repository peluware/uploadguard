package com.peluware.uploadguard;

import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeTypes;

import java.io.IOException;
import java.io.InputStream;

/** Whether the bytes of a body contradict the declared {@code Content-Type}. */
public final class ContentInspector {

    /** Prefix Tika reads to decide. Its own floor, and it reads all of it every time. */
    public static final int DETECT_LENGTH = MimeTypes.getDefaultMimeTypes().getMinLength();

    private final Tika tika = new Tika();
    private final MediaTypeRegistry registry = MediaTypeRegistry.getDefaultRegistry();

    /**
     * Detects the real type and leaves the stream at its first byte. Needs mark and reset, which a
     * request body does not have: wrap it in a {@link java.io.BufferedInputStream}.
     */
    public MediaType detect(InputStream body) throws IOException {
        return canonical(tika.detect(body));
    }

    /** Drops parameters and resolves the registry's aliases. Null if unparseable. */
    public MediaType canonical(String type) {
        if (type == null || type.isBlank()) return null;
        MediaType parsed = MediaType.parse(type);
        return parsed == null ? null : registry.normalize(parsed.getBaseType());
    }

    /**
     * True unless the content contradicts the declaration. A detection limited to a prefix can land
     * on a broader type than the one declared, which proves nothing either way: a docx reads as a
     * zip, JSON as plain text.
     */
    public boolean matches(MediaType declared, MediaType detected) {
        if (declared == null || detected == null) return false;
        if (declared.equals(detected)) return true;
        // Every type descends from octet-stream, so counting it as broader would accept anything
        return !MediaType.OCTET_STREAM.equals(detected) && registry.isSpecializationOf(declared, detected);
    }
}
