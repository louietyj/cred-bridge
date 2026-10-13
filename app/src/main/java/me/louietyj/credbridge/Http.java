package me.louietyj.credbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Blocking HTTP with JSON bodies. Call off the main thread. */
final class Http {

    private static final int TIMEOUT_MS = 20_000;

    static final class HttpError extends IOException {
        final int status;
        final String body;

        HttpError(int status, String body) {
            super("HTTP " + status + ": " + body.trim());
            this.status = status;
            this.body = body;
        }
    }

    private Http() {}

    /** {@code body} null sends no body. {@code bearer} null sends no Authorization. */
    static String send(String method, String url, String bearer, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            if (bearer != null) c.setRequestProperty("authorization", "Bearer " + bearer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            String reply = in == null ? "" : readAll(in);
            if (status != 200) throw new HttpError(status, reply);
            return reply;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }
}
