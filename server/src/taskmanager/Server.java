package taskmanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

public final class Server {
    public record Config(int port, String bind, Path dataDir, Path webDir, boolean secureCookies, boolean trustProxy) {}

    private static final int MAX_BODY = 64 * 1024;
    private static final String CSRF_HEADER = "X-Requested-With";
    private static final String CSRF_VALUE = "taskmanager";
    private static final Map<String, String> STATIC = Map.of("/", "index.html", "/index.html", "index.html", "/app.js", "app.js", "/style.css", "style.css");
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    private final Config cfg;
    private final Store store;
    private final HttpServer http;
    private final RateLimiter loginLimiter = new RateLimiter(5, 5 * 60_000, System::currentTimeMillis);
    private final RateLimiter registerLimiter = new RateLimiter(10, 60 * 60_000, System::currentTimeMillis);

    public Server(Config cfg) throws IOException {
        this.cfg = cfg;
        this.store = new Store(cfg.dataDir().resolve("data.json"));
        this.http = HttpServer.create(new InetSocketAddress(cfg.bind(), cfg.port()), 0);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() { http.start(); }
    public void stop() { http.stop(0); }
    public int port() { return http.getAddress().getPort(); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (Store.RuleException e) {
            json(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Json.ParseException e) {
            json(ex, 400, Map.of("error", "That isn't valid JSON: " + e.getMessage()));
        } catch (ClassCastException | IllegalArgumentException e) {
            json(ex, 400, Map.of("error", "The request body has the wrong shape."));
        } catch (Exception e) {
            System.err.println("Unhandled error on " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + ": " + e);
            json(ex, 500, Map.of("error", "Something went wrong on the server. Try again in a moment."));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
        if (!path.startsWith("/api/")) {
            if (!method.equals("GET") && !method.equals("HEAD")) { json(ex, 405, Map.of("error", "Method not allowed.")); return; }
            String name = STATIC.get(path);
            if (name == null) { json(ex, 404, Map.of("error", "Not found.")); return; }
            byte[] body = Files.readAllBytes(cfg.webDir().resolve(name));
            String type = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : "text/html";
            send(ex, 200, body, type + "; charset=utf-8");
            return;
        }
        if (!method.equals("GET") && !crossSiteSafe(ex)) { json(ex, 403, Map.of("error", "This request didn't come from the app.")); return; }

        switch (path) {
            case "/api/register" -> { requireMethod(method, "POST"); register(ex); return; }
            case "/api/login" -> { requireMethod(method, "POST"); login(ex); return; }
            case "/api/logout" -> { requireMethod(method, "POST"); store.endSession(sessionToken(ex)); setCookie(ex, "", 0); json(ex, 200, Map.of("ok", true)); return; }
            default -> { }
        }

        Store.User me = store.userForSession(sessionToken(ex), Instant.now());
        if (me == null) { json(ex, 401, Map.of("error", "Sign in to continue.")); return; }

        if (path.equals("/api/me")) { requireMethod(method, "GET"); json(ex, 200, Map.of("username", me.username())); return; }
        if (path.equals("/api/tasks")) {
            if (method.equals("GET")) { json(ex, 200, Map.of("tasks", store.list(me).stream().map(Store.Task::toMap).toList())); return; }
            requireMethod(method, "POST");
            Map<String, Object> body = body(ex);
            Map<String, String> errors = Validation.task(body, true);
            if (!errors.isEmpty()) { json(ex, 422, Map.of("errors", errors)); return; }
            json(ex, 201, store.create(me, body).toMap());
            return;
        }
        if (path.startsWith("/api/tasks/")) {
            long id;
            try { id = Long.parseLong(path.substring("/api/tasks/".length())); }
            catch (NumberFormatException e) { throw new Store.RuleException(404, "No such task."); }
            switch (method) {
                case "PATCH" -> {
                    Map<String, Object> body = body(ex);
                    if (body.isEmpty()) { json(ex, 422, Map.of("error", "Nothing to change.")); return; }
                    Map<String, String> errors = Validation.task(body, false);
                    if (!errors.isEmpty()) { json(ex, 422, Map.of("errors", errors)); return; }
                    json(ex, 200, store.update(me, id, body).toMap());
                }
                case "DELETE" -> { store.delete(me, id); send(ex, 204, new byte[0], null); }
                default -> json(ex, 405, Map.of("error", "Method not allowed."));
            }
            return;
        }
        json(ex, 404, Map.of("error", "Not found."));
    }

    private void register(HttpExchange ex) throws IOException {
        long wait = registerLimiter.hit(clientIp(ex));
        if (wait > 0) { tooMany(ex, wait); return; }
        Map<String, Object> body = body(ex);
        Map<String, String> errors = Validation.account(body.get("username"), body.get("password"));
        if (!errors.isEmpty()) { json(ex, 422, Map.of("errors", errors)); return; }
        Store.User u = store.register((String) body.get("username"), (String) body.get("password"));
        setCookie(ex, store.newSession(u, Instant.now()), Store.SESSION_SECONDS);
        json(ex, 201, Map.of("username", u.username()));
    }

    private void login(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        if (!(body.get("username") instanceof String name) || !(body.get("password") instanceof String pw) || pw.length() > 128) {
            json(ex, 422, Map.of("error", "Enter your username and password."));
            return;
        }
        String key = clientIp(ex) + "|" + name.toLowerCase();
        long wait = loginLimiter.hit(key);
        if (wait > 0) { tooMany(ex, wait); return; }
        Store.User u = store.authenticate(name, pw);
        if (u == null) { json(ex, 401, Map.of("error", "Wrong username or password.")); return; }
        loginLimiter.clear(key);
        setCookie(ex, store.newSession(u, Instant.now()), Store.SESSION_SECONDS);
        json(ex, 200, Map.of("username", u.username()));
    }

    private void tooMany(HttpExchange ex, long wait) throws IOException {
        ex.getResponseHeaders().set("Retry-After", String.valueOf(wait));
        json(ex, 429, Map.of("error", "Too many attempts. Try again in " + wait + " seconds."));
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new Store.RuleException(405, "Method not allowed.");
    }

    /** Browsers can't add a custom header cross-site without a preflight we never approve, and a foreign Origin is refused outright. */
    private boolean crossSiteSafe(HttpExchange ex) {
        if (!CSRF_VALUE.equals(ex.getRequestHeaders().getFirst(CSRF_HEADER))) return false;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        try {
            URI o = URI.create(origin);
            String authority = o.getPort() == -1 ? o.getHost() : o.getHost() + ":" + o.getPort();
            return host != null && host.equalsIgnoreCase(authority);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) throw new Store.RuleException(415, "Send the body as application/json.");
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new Store.RuleException(413, "That request is too large.");
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private String clientIp(HttpExchange ex) {
        if (cfg.trustProxy()) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                return parts[parts.length - 1].strip();
            }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private String sessionToken(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            String p = part.strip();
            if (p.startsWith("sid=")) return p.substring(4);
        }
        return null;
    }

    private void setCookie(HttpExchange ex, String token, long maxAge) {
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (cfg.secureCookies() ? "; Secure" : ""));
    }

    private void json(HttpExchange ex, int status, Object payload) throws IOException {
        send(ex, status, Json.stringify(payload).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    private void send(HttpExchange ex, int status, byte[] body, String type) throws IOException {
        var h = ex.getResponseHeaders();
        if (type != null) h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", CSP);
        if (cfg.secureCookies()) h.set("Strict-Transport-Security", "max-age=31536000");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, status == 204 || head ? -1 : body.length);
        if (status != 204 && !head) ex.getResponseBody().write(body);
    }
}
