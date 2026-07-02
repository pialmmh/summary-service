package com.telcobright.summary.outbox.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Codec for the outbox {@code data} column — PINNED: {@code base64( gzip( UTF-8 JSON ) )}. {@link #decode}
 * turns the stored string back into the JSON bytes (the {@code [{Cdr,Customer},…]} array the bean parses);
 * {@link #encode} is the inverse (used by tests + local seeding to mirror what billing writes).
 */
public final class OutboxCodec {

    /**
     * Ceiling on a decoded blob — a ~1000-cdr batch is ~2 MB, so this is ~30× headroom. A blob past it is a
     * contract violation (or a gzip bomb) and must fail as a POISON row (quarantinable), not as an OOM that
     * kills the worker thread.
     */
    static final int MAX_DECODED_BYTES = 64 * 1024 * 1024;

    private OutboxCodec() {
    }

    public static byte[] decode(String base64Gzip) {
        return decode(base64Gzip, MAX_DECODED_BYTES);
    }

    static byte[] decode(String base64Gzip, int maxDecodedBytes) {
        byte[] gzipped = Base64.getDecoder().decode(base64Gzip);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > maxDecodedBytes) {
                    throw new IllegalStateException(
                            "outbox blob decodes past the " + maxDecodedBytes + "-byte cap — refusing it as poison");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("could not decode outbox blob", e);
        }
    }

    public static String encode(byte[] json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json);
        } catch (IOException e) {
            throw new UncheckedIOException("could not encode outbox blob", e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    public static String encode(String json) {
        return encode(json.getBytes(StandardCharsets.UTF_8));
    }
}
