package com.peluware.uploadguard;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.tika.mime.MediaType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Relays every request, and inspects the ones that both carry a body and declare what it is. A
 * request with no body, or with a body it makes no claim about, has nothing to contradict.
 */
public final class UploadHandler implements HttpHandler {

    private static final System.Logger LOG = System.getLogger(UploadHandler.class.getName());

    /** Discarded from a rejected body before answering, so the client reads the status, not a reset. */
    private static final int MAX_DRAIN = 1024 * 1024;

    /** Chunked: there is a body, and its length is not known up front. */
    private static final long UNKNOWN_LENGTH = -1;

    private final ContentInspector inspector;
    private final Upstream upstream;

    public UploadHandler(ContentInspector inspector, Upstream upstream) {
        this.inspector = inspector;
        this.upstream = upstream;
    }

    @Override
    public void handle(HttpExchange ex) {
        try (ex) {
            Headers headers = ex.getRequestHeaders();
            String encoding = headers.getFirst("Transfer-Encoding");
            String lengthHeader = headers.getFirst("Content-Length");
            boolean chunked = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");

            // Transfer-Encoding overrides Content-Length, so a message with both is ambiguous: this hop
            // and the next could disagree on where the body ends. Relaying that is request smuggling.
            if (chunked && lengthHeader != null) {
                Http.reply(ex, 400, "Content-Length and Transfer-Encoding are mutually exclusive");
                return;
            }

            long length = UNKNOWN_LENGTH;
            if (!chunked && lengthHeader != null) {
                try {
                    length = Long.parseLong(lengthHeader.trim());
                    if (length < 0) throw new NumberFormatException(lengthHeader);
                } catch (NumberFormatException e) {
                    Http.reply(ex, 400, "Malformed Content-Length");
                    return;
                }
            }

            // Neither framing header means nobody at all: in HTTP/1.1 a request body has nowhere to
            // end otherwise, and what follows the headers is the next request on the connection.
            if (!chunked && length <= 0) {
                upstream.relay(ex, BodyPublishers.noBody());
                return;
            }

            MediaType declared = inspector.canonical(Http.baseType(headers.getFirst("Content-Type")));
            if (declared == null) {
                // No declared type is no claim, and nothing to hold the content against
                upstream.relay(ex, body(ex.getRequestBody(), length));
                return;
            }

            inspectAndRelay(ex, declared, length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failSafe(ex, e);
        } catch (Exception e) {
            failSafe(ex, e);
        }
    }

    private void inspectAndRelay(HttpExchange ex, MediaType declared, long length) throws IOException, InterruptedException {

        // Detection resets the stream, so this same one is what gets relayed
        InputStream buffered = new BufferedInputStream(ex.getRequestBody(), detectBuffer(length));
        MediaType detected = inspector.detect(buffered);

        if (!inspector.matches(declared, detected)) {
            LOG.log(WARNING, "Rejected {0} {1} over {2}: declared={3}, detected={4}", ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getProtocol(), declared, detected);
            Http.drain(buffered, MAX_DRAIN);
            Http.reply(ex, 415, "Content does not match the declared " + declared);
            return;
        }

        upstream.relay(ex, body(buffered, length));
    }

    /** Relays with the framing it arrived with. One-shot: a retry must fail short, never truncate. */
    private static BodyPublisher body(InputStream stream, long length) {
        var consumed = new AtomicBoolean();
        var publisher = BodyPublishers.ofInputStream(() -> consumed.compareAndSet(false, true) ? stream : InputStream.nullInputStream());
        return length == UNKNOWN_LENGTH ? publisher : BodyPublishers.fromPublisher(publisher, length);
    }

    private static int detectBuffer(long contentLength) {
        if (contentLength == UNKNOWN_LENGTH) return ContentInspector.DETECT_LENGTH;
        return Math.clamp(contentLength, 1, ContentInspector.DETECT_LENGTH);
    }

    private static void failSafe(HttpExchange ex, Exception cause) {
        LOG.log(ERROR, "Error handling " + ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + " over " + ex.getProtocol(), cause);
        try {
            if (ex.getResponseCode() == -1) Http.reply(ex, 502, "Upstream request failed");
        } catch (IOException e) {
            // already answered, or the connection is gone
        }
    }
}
