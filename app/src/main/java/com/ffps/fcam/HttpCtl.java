package com.ffps.fcam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/** Минимальный HTTP-сервер: GET /api/shot?password=..., GET /api/list?password=... */
public class HttpCtl extends Thread {

    public interface Cb {
        /** 0 - запущено, 1 - серия уже идёт, 2 - камера недоступна */
        int onShot();

        /** Текст для /api/list. */
        String onList();

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
            Socket s = null;
            try {
                s = ss.accept();
                handle(s);
            } catch (IOException e) {
                if (stopped) break;
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(3000);
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
        boolean list = "/api/list".equals(path);
        if (!list && !"/api/shot".equals(path)) {
            reply(s, 404, "Not Found");
            return;
        }
        if (password.length() > 0) {
            String given = "";
            for (String kv : query.split("&")) {
                int e = kv.indexOf('=');
                if (e > 0 && "password".equals(kv.substring(0, e))) {
                    try {
                        given = URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                    } catch (Exception ex) {
                        given = "";
                    }
                }
            }
            if (!password.equals(given)) {
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
            replyBody(s, 200, "OK", body);
            return;
        }
        int res = cb.onShot();
        if (res == 0) reply(s, 200, "OK");
        else if (res == 1) reply(s, 409, "Busy");
        else reply(s, 503, "Camera unavailable");
    }

    private void reply(Socket s, int code, String text) throws IOException {
        replyBody(s, code, text, text + "\n");
    }

    private void replyBody(Socket s, int code, String text, String bodyText) throws IOException {
        byte[] body = bodyText.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes("ISO-8859-1"));
        o.write(body);
        o.flush();
    }
}
