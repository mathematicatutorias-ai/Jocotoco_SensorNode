package org.jocotoco.sensornode;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiServer {
    static final int PORT = 8765;
    private final Context app;
    private volatile boolean active;
    private volatile String serverError = "";
    private volatile ServerSocket socket;
    private ExecutorService workers;
    private Thread listener;

    ApiServer(Context context) { app = context.getApplicationContext(); }

    void start() {
        if (active) return;
        active = true;
        workers = Executors.newFixedThreadPool(3);
        listener = new Thread(new Runnable() {
            @Override public void run() { serve(); }
        }, "SensorNode-HTTP");
        listener.start();
    }

    void stop() {
        active = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (workers != null) workers.shutdownNow();
        if (listener != null) listener.interrupt();
    }

    private void serve() {
        try (ServerSocket s = new ServerSocket()) {
            socket = s;
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 6);
            while (active) {
                try {
                    final Socket client = s.accept();
                    client.setSoTimeout(5000);
                    workers.execute(new Runnable() {
                        @Override public void run() { handle(client); }
                    });
                } catch (IOException e) {
                    if (active) serverError = e.toString();
                }
            }
        } catch (IOException e) {
            serverError = e.toString();
            android.util.Log.e("SensorNode", "No se pudo abrir puerto " + PORT, e);
        }
    }

    private void handle(Socket client) {
        try (Socket s = client;
             InputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String first = line(in);
            if (first == null || first.isEmpty()) return;
            String[] tokens = first.split(" ");
            if (tokens.length != 3 || !tokens[2].startsWith("HTTP/1.")) {
                jsonError(out, 400, "Solicitud HTTP inválida"); return;
            }
            String method = tokens[0];
            String route = tokens[1];
            if (route.length() > 2048) { jsonError(out, 414, "Ruta demasiado larga"); return; }
            String auth = null;
            int headerBytes = 0;
            while (true) {
                String header = line(in);
                if (header == null) { jsonError(out, 400, "Cabecera incompleta"); return; }
                headerBytes += header.length();
                if (headerBytes > 8192) { jsonError(out, 431, "Demasiadas cabeceras"); return; }
                if (header.isEmpty()) break;
                int idx = header.indexOf(':');
                if (idx > 0 && header.substring(0, idx).trim().equalsIgnoreCase("X-SensorNode-Token")) {
                    auth = header.substring(idx + 1).trim();
                }
            }
            String path = route.split("\\?", 2)[0];
            try {
            if ("GET".equals(method) && "/v1/health".equals(path)) {
                JSONObject health = new JSONObject();
                health.put("ok", serverError.isEmpty());
                health.put("service", "Jocotoco SensorNode");
                health.put("version", "0.1.0");
                health.put("error", serverError);
                json(out, 200, health); return;
            }
            if (!Secrets.tokenEquals(app, auth)) { jsonError(out, 401, "Token inválido o ausente"); return; }
            if ("GET".equals(method) && "/v1/status".equals(path)) {
                json(out, 200, Hardware.status(app));
            } else if ("GET".equals(method) && "/v1/sensors".equals(path)) {
                json(out, 200, Hardware.sensors(app));
            } else if ("POST".equals(method) && "/v1/photo".equals(path)) {
                String facing = query(route, "facing", "back");
                if (!"back".equals(facing) && !"front".equals(facing)) { jsonError(out, 400, "facing debe ser back/front"); return; }
                File file = Hardware.photo(app, facing);
                json(out, 200, Hardware.fileInfo(file));
            } else if ("POST".equals(method) && "/v1/audio".equals(path)) {
                int seconds;
                try { seconds = Integer.parseInt(query(route, "seconds", "5")); }
                catch (NumberFormatException e) { jsonError(out, 400, "seconds no es entero"); return; }
                if (seconds < 1 || seconds > 60) { jsonError(out, 400, "seconds debe estar entre 1 y 60"); return; }
                File file = Hardware.audio(app, seconds);
                json(out, 200, Hardware.fileInfo(file));
            } else if ("GET".equals(method) && "/v1/files".equals(path)) {
                JSONArray files = new JSONArray();
                File[] all = Hardware.folder(app).listFiles();
                if (all != null) {
                    java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                        @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                    });
                    for (File item : all) if (item.isFile()) files.put(Hardware.fileInfo(item));
                }
                JSONObject result = new JSONObject();
                result.put("files", files);
                json(out, 200, result);
            } else if ("GET".equals(method) && "/v1/file".equals(path)) {
                String name = query(route, "name", "");
                if (!name.matches("[A-Za-z0-9_.-]{1,150}") || name.startsWith(".")) {
                    jsonError(out, 400, "Nombre de archivo inválido"); return;
                }
                File file = new File(Hardware.folder(app), name);
                if (!file.isFile()) { jsonError(out, 404, "No existe el archivo"); return; }
                download(out, file);
            } else {
                jsonError(out, 404, "Ruta o método no admitido");
            }
            } catch (SecurityException permission) {
                jsonError(out, 403, permission.getMessage());
            } catch (Exception deviceError) {
                android.util.Log.e("SensorNode", "Error en API", deviceError);
                jsonError(out, 500, deviceError.getClass().getSimpleName() + ": " + deviceError.getMessage());
            }
        } catch (Exception e) {
            // El servidor puede registrar este error. Las excepciones ocurridas durante
            // una respuesta binaria podrían haber enviado ya las cabeceras HTTP.
            android.util.Log.e("SensorNode", "Error solicitud (conexión finalizada)", e);
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= 4096) {
            int c = in.read();
            if (c == -1) return bytes.size() == 0 ? null : bytes.toString("UTF-8");
            if (c == '\n') break;
            if (c != '\r') bytes.write(c);
        }
        if (bytes.size() > 4096) throw new IOException("Línea demasiado larga");
        return bytes.toString("UTF-8");
    }

    private static String query(String route, String key, String fallback) throws Exception {
        int q = route.indexOf('?');
        if (q < 0) return fallback;
        String[] items = route.substring(q + 1).split("&");
        for (String item : items) {
            String[] kv = item.split("=", 2);
            if (URLDecoder.decode(kv[0], "UTF-8").equals(key)) {
                return kv.length == 2 ? URLDecoder.decode(kv[1], "UTF-8") : "";
            }
        }
        return fallback;
    }

    private static void json(OutputStream out, int status, JSONObject object) throws Exception {
        byte[] payload = object.toString().getBytes(StandardCharsets.UTF_8);
        replyHeaders(out, status, "application/json; charset=utf-8", payload.length);
        out.write(payload); out.flush();
    }
    private static void jsonError(OutputStream out, int status, String message) throws Exception {
        JSONObject error = new JSONObject(); error.put("error", message == null ? "Error" : message);
        json(out, status, error);
    }

    private static void replyHeaders(OutputStream out, int status, String type, long length) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 403 ? "Forbidden" : status == 400 ? "Bad Request"
            : status == 404 ? "Not Found" : "Error";
        String header = String.format(Locale.US, "HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n", status, reason, type, length);
        out.write(header.getBytes(StandardCharsets.US_ASCII));
    }
    private static void download(OutputStream out, File file) throws IOException {
        String type = file.getName().endsWith(".jpg") ? "image/jpeg" : file.getName().endsWith(".wav") ? "audio/wav" : "application/octet-stream";
        replyHeaders(out, 200, type, file.length());
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        out.flush();
    }
}
