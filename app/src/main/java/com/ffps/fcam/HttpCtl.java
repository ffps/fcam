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
 * Минимальный HTTP-сервер. Параметр password - в строке запроса.
 * GET /api/shot, /api/list (JSON), /api/photo?name=, /api/thumb?name=, /api/delete?name=, /api/delete_all,
 * GET/POST /api/config (JSON), GET /api/info (JSON), GET /api/reboot, GET /api и /api/ - список методов (без пароля),
 * GET / - DCIM/fCam/index.html (без пароля).
 */
public class HttpCtl extends Thread {

    public interface Cb {
        /** 0 - запущено, 1 - серия уже идёт, 2 - камера недоступна */
        int onShot();

        /** JSON для /api/list. */
        String onList();

        /** Файл фото по имени; null, если имени нет, файла нет или имя недопустимо. */
        File onPhoto(String name);

        /** Файл миниатюры (при невозможности создать - оригинал); null, если фото нет. */
        File onThumb(String name);

        /** Удаление фото по имени: 0 - удалено, 1 - нет такого файла, 2 - не удалось удалить. */
        int onDelete(String name);

        /** Планирует перезагрузку; возвращает способ ("deviceOwner"/"accessibility") или null, если прав нет. */
        String onReboot();

        /** Сведения о системе в JSON. */
        String onInfo();

        /** Удаляет все фото; возвращает {удалено, не удалось}. */
        int[] onDeleteAll();

        /** Текущие настройки в JSON. */
        String onConfigGet();

        /** Применяет настройки из JSON; null - успех, иначе текст ошибки. */
        String onConfigSet(String body);

        /** Перезапуск сервиса после смены настроек (вызывается после отправки ответа). */
        void onRestart();

        /** Файл index.html для "/" или null, если его нет. */
        File onIndex();

        void onError(String msg);
    }

    private static final String JSON = "application/json; charset=utf-8";
    private static final int MAX_BODY = 16384;

    private static final String NO_REBOOT = "{\n"
            + "  \"ok\": false,\n"
            + "  \"error\": \"no_permission\",\n"
            + "  \"method\": \"none\",\n"
            + "  \"message\": \"Нет прав на перезагрузку: сделайте fCam владельцем устройства (Android 7+, команда adb shell dpm set-device-owner)"
            + ", дайте fCam доступ root (su) или включите службу специальных возможностей fCam (Android 5+). На Android 4.x без root перезагрузка недоступна.\"\n"
            + "}\n";

    /** Список методов для /api (не требует пароля). */
    private static final String API_INDEX = "{\n"
            + "  \"GET /\": \"Страница DCIM/fCam/index.html, если она есть (без пароля)\",\n"
            + "  \"GET /api\": \"Этот список методов (без пароля)\",\n"
            + "  \"GET /api/shot\": \"Сделать серию кадров. Параметры: password\",\n"
            + "  \"GET /api/list\": \"Список фото (JSON). Параметры: password\",\n"
            + "  \"GET /api/photo\": \"Фото по имени (JPEG). Параметры: password, name\",\n"
            + "  \"GET /api/thumb\": \"Миниатюра фото (JPEG). Параметры: password, name\",\n"
            + "  \"GET /api/delete_all\": \"Удалить все фото (*.jpg) из папки fCam. Параметры: password\",\n"
            + "  \"GET /api/delete\": \"Удалить фото по имени. Параметры: password, name\",\n"
            + "  \"GET /api/config\": \"Текущие настройки (JSON). Параметры: password\",\n"
            + "  \"POST /api/config\": \"Изменить настройки: в теле JSON с нужными ключами, сервис перезапустится. Параметры: password\",\n"
            + "  \"GET /api/reboot\": \"Перезагрузить устройство (через пару секунд после ответа). Нужны права: владелец устройства, root или служба спец. возможностей. Параметры: password\",\n"
            + "  \"GET /api/info\": \"Сведения о системе (JSON): устройство, Android, аптайм, батарея, IP, память. Параметры: password\"\n"
            + "}\n";

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
        try {
            s.setTcpNoDelay(true);
            s.setSendBufferSize(262144);
        } catch (Exception ignored) {
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
        String line = r.readLine();
        if (line == null) return;
        int contentLen = 0;
        for (int i = 0; i < 64; i++) {      // заголовки
            String h = r.readLine();
            if (h == null || h.length() == 0) break;
            if (h.regionMatches(true, 0, "content-length:", 0, 15)) {
                try {
                    contentLen = Integer.parseInt(h.substring(15).trim());
                } catch (Exception ignored) {
                }
            }
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            reply(s, 400, "Bad Request");
            return;
        }
        boolean post = "POST".equals(parts[0]);
        if (!post && !"GET".equals(parts[0])) {
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
        if (post && !"/api/config".equals(path)) {
            reply(s, 405, "Method Not Allowed");
            return;
        }
        if ("/api".equals(path) || "/api/".equals(path)) {
            replyBody(s, 200, "OK", API_INDEX, JSON);
            return;
        }
        if ("/".equals(path)) {
            File idx = cb.onIndex();
            if (idx == null || !idx.isFile()) {
                reply(s, 404, "Not Found");
                return;
            }
            sendFile(s, idx, "text/html; charset=utf-8", null, false);
            return;
        }
        boolean shot = "/api/shot".equals(path);
        boolean list = "/api/list".equals(path);
        boolean photo = "/api/photo".equals(path);
        boolean thumb = "/api/thumb".equals(path);
        boolean delete = "/api/delete".equals(path);
        boolean deleteAll = "/api/delete_all".equals(path);
        boolean config = "/api/config".equals(path);
        boolean info = "/api/info".equals(path);
        boolean reboot = "/api/reboot".equals(path);
        if (!shot && !list && !photo && !thumb && !delete && !deleteAll && !config && !info && !reboot) {
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
        if (config) {
            if (post) {
                if (contentLen > MAX_BODY) {
                    reply(s, 413, "Payload Too Large");
                    return;
                }
                char[] buf = new char[contentLen];
                int off = 0;
                while (off < contentLen) {
                    int n = r.read(buf, off, contentLen - off);
                    if (n < 0) break;
                    off += n;
                }
                // тело читалось как ISO-8859-1 (1 символ = 1 байт), декодируем как UTF-8
                String body = new String(new String(buf, 0, off).getBytes("ISO-8859-1"), "UTF-8");
                String err = cb.onConfigSet(body);
                if (err != null) {
                    replyBody(s, 400, "Bad Request", err + "\n", "text/plain; charset=utf-8");
                    return;
                }
                replyBody(s, 200, "OK", cb.onConfigGet(), JSON);
                cb.onRestart();
                return;
            }
            replyBody(s, 200, "OK", cb.onConfigGet(), JSON);
            return;
        }
        if (reboot) {
            String m = cb.onReboot();
            if (m == null) {
                replyBody(s, 501, "Not Implemented", NO_REBOOT, JSON);
            } else {
                replyBody(s, 200, "OK", "{\n  \"ok\": true,\n  \"method\": \"" + m
                        + "\",\n  \"message\": \"Перезагрузка через пару секунд\"\n}\n", JSON);
            }
            return;
        }
        if (info) {
            String body;
            try {
                body = cb.onInfo();
            } catch (Exception e) {
                reply(s, 500, "Error");
                return;
            }
            replyBody(s, 200, "OK", body, JSON);
            return;
        }
        if (list) {
            String body;
            try {
                body = cb.onList();
            } catch (Exception e) {
                reply(s, 500, "Error");
                return;
            }
            replyBody(s, 200, "OK", body, JSON);
            return;
        }
        if (deleteAll) {
            int[] r2 = cb.onDeleteAll();
            String body = "{\n  \"ok\": " + (r2[1] == 0) + ",\n  \"deleted\": " + r2[0] + ",\n  \"failed\": " + r2[1] + "\n}\n";
            if (r2[1] == 0) replyBody(s, 200, "OK", body, JSON);
            else replyBody(s, 500, "Delete failed", body, JSON);
            return;
        }
        if (delete) {
            int dr = cb.onDelete(param(query, "name"));
            if (dr == 0) reply(s, 200, "OK");
            else if (dr == 1) reply(s, 404, "Not Found");
            else reply(s, 500, "Delete failed");
            return;
        }
        if (photo || thumb) {
            String name = param(query, "name");
            File f = photo ? cb.onPhoto(name) : cb.onThumb(name);
            if (f == null || !f.isFile()) {
                reply(s, 404, "Not Found");
                return;
            }
            sendFile(s, f, "image/jpeg", f.getName(), true);
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
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes("ISO-8859-1"));
        o.write(body);
        o.flush();
    }

    private void sendFile(Socket s, File f, String type, String dispName, boolean cache) throws IOException {
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
                    + (cache ? "Cache-Control: private, max-age=86400\r\n" : "")
                    + "Connection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(head.getBytes("ISO-8859-1"));
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.flush();
        } finally {
            in.close();
        }
    }
}
