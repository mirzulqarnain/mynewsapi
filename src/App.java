import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class App {
    static final String REGISTRATION = "SP23-BAI-023";
    static final String NEWS_SOURCE = "NPR";
    static final int PORT = 7000;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", App::handleRoot);
        server.createContext("/get", App::handleGet);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("API running on port " + PORT);
    }

    // Web page that shows the registration number
    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Lab 4 - News API</title></head>"
                + "<body style=\"font-family:sans-serif;margin:40px\">"
                + "<h1>Registration Number: " + REGISTRATION + "</h1>"
                + "<h3>News source: " + NEWS_SOURCE + "</h3>"
                + "<p>Usage: <code>/get?keyword=imran</code></p>"
                + "</body></html>";
        send(ex, 200, "text/html; charset=utf-8", html);
    }

    private static void handleGet(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method not allowed\"}");
            return;
        }
        String keyword = parseQuery(ex.getRequestURI().getRawQuery()).get("keyword");
        if (keyword == null || keyword.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"keyword query parameter is required\"}");
            return;
        }
        keyword = keyword.trim();

        try {
            NewsScraper.Result r = NewsScraper.fetchFirstResult(keyword);
            String summary = Summarizer.summarize(r.text(), 3);
            send(ex, 200, "application/json", json(keyword, r.url(), summary));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", json(keyword, "", "Error: " + e.getMessage()));
        }
    }

    private static String json(String keyword, String url, String summary) {
        return "{"
                + "\"registration\":\"" + esc(REGISTRATION) + "\","
                + "\"newssource\":\"" + esc(NEWS_SOURCE) + "\","
                + "\"keyword\":\"" + esc(keyword) + "\","
                + "\"url\":\"" + esc(url) + "\","
                + "\"summary\":\"" + esc(summary) + "\""
                + "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null) return map;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
