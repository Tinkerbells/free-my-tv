package io.freemytv;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The tiny HTTP server the Lampa plugin talks to, from Lampa's WebView on the same TV: POST /lampa/favorites with the
 * lists, GET /lampa/ping. Listens on 127.0.0.1 only (nothing leaves the TV), reads a bounded request (16 KB of
 * headers, 1 MiB of body, 5 s per read) and never logs what it receives.
 */
final class LampaBridge {
    static final int PORT = 38777;
    private static final int HEADER_MAX = 16 * 1024, BODY_MAX = 1024 * 1024, TIMEOUT_MS = 5000;

    /** A request we answer with an error status instead of handling. */
    private static final class Refused extends IOException {
        final int status;

        Refused(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private final Context context;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
    private volatile ServerSocket server;
    private volatile boolean running;

    LampaBridge(Context c) {
        context = c.getApplicationContext();
    }

    /** Opens the port and starts accepting. Throws if it can't be bound (another instance already holds it). */
    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), PORT), 8);
        server = s;
        running = true;
        Thread t = new Thread(() -> accept(s), "lampa-accept");
        t.setDaemon(true);
        t.start();
        AppLog.w("Lampa: listening on 127.0.0.1:" + PORT);
    }

    synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
        AppLog.w("Lampa: stopped");
    }

    private void accept(ServerSocket s) {
        while (running) {
            Socket socket;
            try {
                socket = s.accept();
            } catch (IOException e) {
                if (running) AppLog.w("Lampa: accept failed: " + e.getMessage());
                return;
            }
            try {
                pool.execute(() -> handle(socket));
            } catch (RejectedExecutionException e) {
                close(socket);   // busy: the plugin retries
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            try {
                respond(out, request(in));
            } catch (Refused e) {
                reply(out, e.status, null);
            }
            out.flush();
        } catch (IOException e) {
            // A client that left or stalled: nothing to answer.
        } finally {
            close(socket);
        }
    }

    /** Reads the request and returns the response to send: {status, body or null}. */
    private Object[] request(InputStream in) throws IOException {
        String head = readHead(in);
        String[] lines = head.split("\r\n");
        String[] start = lines[0].split(" ");
        if (start.length < 2) throw new Refused(400);
        String method = start[0].toUpperCase(Locale.ROOT);
        String path = start[1];
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        if (method.equals("OPTIONS")) return new Object[] {204, null};
        if (method.equals("GET") && path.equals("/lampa/ping")) return new Object[] {200, "{\"app\":\"free-my-tv\"}"};
        if (method.equals("POST") && path.equals("/lampa/favorites")) {
            if (headers.containsKey("transfer-encoding")) throw new Refused(411);   // only a Content-Length body
            String length = headers.get("content-length");
            if (length == null) throw new Refused(411);
            long n;
            try {
                n = Long.parseLong(length);
            } catch (NumberFormatException e) {
                throw new Refused(400);
            }
            if (n < 0) throw new Refused(400);
            if (n > BODY_MAX) throw new Refused(413);
            byte[] body = new byte[(int) n];
            int read = 0;
            while (read < body.length) {
                int got = in.read(body, read, body.length - read);
                if (got < 0) throw new Refused(400);
                read += got;
            }
            int lists = LampaLists.update(context, new String(body, StandardCharsets.UTF_8));
            if (lists < 0) throw new Refused(400);
            AppLog.w("Lampa: received " + lists + " lists");
            return new Object[] {200, "{\"ok\":true}"};
        }
        throw new Refused(404);
    }

    /** Up to the blank line that ends the headers; at most 16 KB. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int matched = 0, b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (buf.size() > HEADER_MAX) throw new Refused(431);
            // Matching "\r\n\r\n" one byte at a time.
            if (b == '\r' && (matched == 0 || matched == 2)) matched++;
            else if (b == '\n' && (matched == 1 || matched == 3)) matched++;
            else matched = b == '\r' ? 1 : 0;
            if (matched == 4) return new String(buf.toByteArray(), 0, buf.size() - 4, StandardCharsets.ISO_8859_1);
        }
        throw new Refused(400);
    }

    private static void respond(OutputStream out, Object[] response) throws IOException {
        reply(out, (Integer) response[0], (String) response[1]);
    }

    private static void reply(OutputStream out, int status, String body) throws IOException {
        byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Private-Network: true\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        h.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n");
        if (body != null) h.append("Content-Type: application/json; charset=utf-8\r\n");
        h.append("Content-Length: ").append(data.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 431: return "Request Header Fields Too Large";
            default: return "Error";
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
