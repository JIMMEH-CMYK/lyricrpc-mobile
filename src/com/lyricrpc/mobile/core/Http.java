package com.lyricrpc.mobile.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One GET helper for every provider, deliberately built on HttpURLConnection so the same class compiles
 * and runs on a plain JVM, where the lyric tests execute, and on Android, where there is no other option
 * without pulling a dependency into the APK.
 */
public final class Http {
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Some hosts answer a plain Java client with 403, so the request says who it is. */
    private static final String USER_AGENT = "LyricRPC/2.8 (Android)";

    private final long timeoutMs;

    public Http() {
        this(6000);
    }

    public Http(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) timeoutMs);
            connection.setReadTimeout((int) timeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, readAll(stream));
        } finally {
            connection.disconnect();
        }
    }

    /** Fetches JSON and turns a non-2xx answer into the same "returned 404" wording the desktop plugin logs. */
    public Json json(String url) throws IOException {
        Response response = get(url);
        if (!response.ok()) throw new IOException("returned " + response.status);
        return Json.parse(response.body);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long written = 0;
            while ((read = stream.read(buffer)) > 0) {
                // A misbehaving host must not be able to fill the heap through a chunked response.
                if (written + read > 4L * 1024 * 1024) throw new IOException("response too large");
                out.write(buffer, 0, read);
                written += read;
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            stream.close();
        }
    }
}
