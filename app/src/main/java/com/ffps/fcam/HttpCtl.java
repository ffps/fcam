package com.ffps.fcam;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * Минимальный HTTP-сервер (все запросы GET, параметр password):
 * /api/shot, /api/list (JSON), /api/photo?name=файл.jpg (без name - последнее фото),
 * "/" - отдаёт DCIM/fCam/index.html (без пароля, если файл есть).
 */
public class HttpCtl extends Thread {

    public interface Cb {
        /** 0 - запущено, 1 - серия уже идёт, 2 - камера недоступна */
        int onShot();

        /** JSON для /api/list. */
        String onList();

        /** Файл фото по имени (null/пусто - последнее); null, если нет или имя недопустимо. */
        File onPhoto(String name);

        /** Файл index.html для "/" или null, если его нет. */
        File onIndex();

        void onError(String msg);
    }

    private final int port;
    private final String password;
    private final Cb cb;
    private volatile boolean stopped;
    private ServerSocket ss;

    public HttpCtl(int port, String password, Cb cb) {
        super("fcam-http");
        this.port = port;
        this.password = password == null ? "" : password;
        this.cb = cb;
    }

    public void shutdown() {
        stopped = true;
        try {
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            cb.onError("Порт " + port + " недоступен");
            return;
        }
        while (!stopped) {
            try {
                final Socket s = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(s);
                        } catch (IOException ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }, "fcam-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (stopped) break;
            }
        }
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && key.equals(kv.substring(0, e))) {
                try {
                    return URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                } catch (Exception ex) {
                    return "";
                }
            }
        }
        return null;
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(5000);
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
        String line = r.readLine();
        if (line == null) return;
        for (int i = 0; i < 64; i++) {      // дочитываем заголовки
            String h = r.readLine();
            if (h == null || h.length() == 0) break;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            reply(s, 400, "Bad Request");
            return;
        }
        if (!"GET".equals(parts[0])) {
            reply(s, 405, "Method Not Allowed");
            return;
        }
        String target = parts[1];
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        if ("/".equals(path)) {
            File idx = cb.onIndex();
            if (idx == null || !idx.isFile()) {
                reply(s, 404, "Not Found");
                return;
            }
            sendFile(s, idx, "text/html; charset=utf-8", null);
            return;
        }
        boolean shot = "/api/shot".equals(path);
        boolean list = "/api/list".equals(path);
        boolean photo = "/api/photo".equals(path);
        if (!shot && !list && !photo) {
            reply(s, 404, "Not Found");
            return;
        }
        if (password.length() > 0) {
            String given = param(query, "password");
            if (given == null || !password.equals(given)) {
                reply(s, 403, "Forbidden");
                return;
            }
        }
        if (list) {
            String body;
            try {
                body = cb.onList();
            } catch (Exception e) {
                reply(s, 500, "Error");
                return;
            }
            replyBody(s, 200, "OK", body, "application/json; charset=utf-8");
            return;
        }
        if (photo) {
            File f = cb.onPhoto(param(query, "name"));
            if (f == null || !f.isFile()) {
                reply(s, 404, "Not Found");
                return;
            }
            sendFile(s, f, "image/jpeg", f.getName());
            return;
        }
        int res = cb.onShot();
        if (res == 0) reply(s, 200, "OK");
        else if (res == 1) reply(s, 409, "Busy");
        else reply(s, 503, "Camera unavailable");
    }

    private void reply(Socket s, int code, String text) throws IOException {
        replyBody(s, code, text, text + "\n", "text/plain; charset=utf-8");
    }

    private void replyBody(Socket s, int code, String text, String bodyText, String type) throws IOException {
        byte[] body = bodyText.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes("ISO-8859-1"));
        o.write(body);
        o.flush();
    }

    private void sendFile(Socket s, File f, String type, String dispName) throws IOException {
        FileInputStream in;
        try {
            in = new FileInputStream(f);
        } catch (IOException e) {
            reply(s, 404, "Not Found");
            return;
        }
        try {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + f.length() + "\r\n"
                    + (dispName != null ? "Content-Disposition: inline; filename=\"" + dispName + "\"\r\n" : "")
                    + "Connection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(head.getBytes("ISO-8859-1"));
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.flush();
        } finally {
            in.close();
        }
    }
}
