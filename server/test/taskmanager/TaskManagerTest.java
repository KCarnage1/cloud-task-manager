package taskmanager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dependency-free runner: each check prints PASS or FAIL and the exit code reflects the result. */
public final class TaskManagerTest {
    private static final List<String> failures = new ArrayList<>();
    private static int passed = 0;

    interface Check { void run() throws Exception; }

    static void test(String name, Check c) {
        try { c.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable t) { failures.add(name); System.out.println("FAIL " + name + ": " + t); }
    }

    static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    static void yes(boolean cond, String what) { if (!cond) throw new AssertionError(what); }

    static void rule(int status, Check c) {
        try { c.run(); } catch (Store.RuleException e) { eq(status, e.status); return; } catch (Exception e) { throw new AssertionError(e); }
        throw new AssertionError("expected a RuleException " + status);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    /* ---------- HTTP helpers ---------- */

    static final class Client {
        final HttpClient http = HttpClient.newHttpClient();
        final String base;
        String sid;   // tracked by hand because the JDK's CookieManager doesn't reliably return cookies to an IP-address host
        Client(String base) { this.base = base; }

        HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (!method.equals("GET")) b.header("X-Requested-With", "taskmanager");
            if (body != null) b.header("Content-Type", "application/json");
            if (sid != null) b.header("Cookie", "sid=" + sid);
            headers.forEach(b::setHeader);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            r.headers().firstValue("Set-Cookie").ifPresent(c -> {
                String value = c.substring(c.indexOf('=') + 1, c.indexOf(';'));
                sid = c.contains("Max-Age=0") || value.isEmpty() ? null : value;
            });
            return r;
        }

        HttpResponse<String> call(String method, String path, String body) throws Exception { return send(method, path, body, Map.of()); }
        Map<String, Object> json(HttpResponse<String> r) { return obj(Json.parse(r.body())); }
    }

    static Server startServer(Path dir) throws Exception {
        Path web = dir.resolve("web");
        Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "<!doctype html><title>t</title>");
        Files.writeString(web.resolve("app.js"), "//");
        Files.writeString(web.resolve("style.css"), "/**/");
        Server s = new Server(new Server.Config(0, "127.0.0.1", dir.resolve("data"), web, false, false));
        s.start();
        return s;
    }

    static final String PW = "correct horse battery";

    public static void main(String[] args) throws Exception {
        /* ---------- units ---------- */
        test("JSON round trip with escapes and unicode", () -> {
            String s = "{\"a\":[1,2.5,true,null],\"b\":\"line\\nbreak \\\"q\\\" \\u00e9 \\u2603\",\"c\":{\"d\":-3}}";
            Map<String, Object> m = Json.parseObject(s);
            eq(1L, ((List<?>) m.get("a")).get(0));
            eq(2.5, ((List<?>) m.get("a")).get(1));
            eq("line\nbreak \"q\" \u00e9 \u2603", m.get("b"));
            eq(m, Json.parseObject(Json.stringify(m)));
        });
        test("JSON output escapes markup characters", () -> eq("\"\\u003cscript\\u003e\"", Json.stringify("<script>")));
        test("JSON rejects bad input", () -> {
            for (String bad : new String[] {"", "{", "[1,]", "{\"a\":}", "{'a':1}", "tru", "[1] x", "\"\\q\"", "\"a\nb\""}) {
                try { Json.parse(bad); throw new AssertionError("accepted: " + bad); } catch (Json.ParseException expected) { }
            }
            try { Json.parse("[".repeat(100) + "]".repeat(100)); throw new AssertionError("accepted deep nesting"); } catch (Json.ParseException expected) { }
        });
        test("passwords hash with a unique salt and verify", () -> {
            String a = Passwords.hash(PW, 1000), b = Passwords.hash(PW, 1000);
            yes(!a.equals(b), "same salt");
            yes(Passwords.verify(PW, a) && !Passwords.verify(PW + "x", a), "verify");
            yes(!Passwords.verify(PW, "garbage") && !Passwords.verify(PW, "1$%%%$%%%"), "malformed hashes must fail, not throw");
        });
        test("account validation", () -> {
            eq(0, Validation.account("kc_01", PW).size());
            yes(Validation.account("ab", PW).containsKey("username"), "short name");
            yes(Validation.account("bad name!", PW).containsKey("username"), "bad chars");
            yes(Validation.account("kc_01", "short").containsKey("password"), "short password");
            yes(Validation.account("kc_01", "password1").containsKey("password"), "common password");
            yes(Validation.account("kc_01_kc_01", "kc_01_kc_01").containsKey("password"), "same as username");
            yes(Validation.account(null, null).size() == 2, "null input");
        });
        test("task validation never throws on odd types", () -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("title", "ok"); m.put("status", null); m.put("priority", 5L); m.put("due", "2026-02-31"); m.put("notes", List.of()); m.put("extra", 1L);
            Map<String, String> e = Validation.task(m, true);
            yes(e.containsKey("status") && e.containsKey("priority") && e.containsKey("due") && e.containsKey("notes") && e.containsKey("extra"), e.toString());
            yes(Validation.task(Map.of(), true).containsKey("title"), "title required on create");
            yes(Validation.task(Map.of("title", "x".repeat(121)), true).containsKey("title"), "title length");
            eq(0, Validation.task(Map.of("status", "done"), false).size());
        });
        test("rate limiter allows, blocks, then recovers", () -> {
            long[] now = {0};
            RateLimiter r = new RateLimiter(3, 10_000, () -> now[0]);
            eq(0L, r.hit("k")); eq(0L, r.hit("k")); eq(0L, r.hit("k"));
            yes(r.hit("k") > 0, "fourth hit should be blocked");
            eq(0L, r.hit("other"));
            now[0] = 10_001;
            eq(0L, r.hit("k"));
        });

        /* ---------- store rules ---------- */
        test("users only ever reach their own tasks", () -> {
            Store s = new Store(null);
            Store.User a = s.register("alice", PW), b = s.register("bob", PW);
            Store.Task t = s.create(a, Map.of("title", "Alice's"));
            eq(0, s.list(b).size());
            rule(404, () -> s.update(b, t.id, Map.of("title", "mine now")));
            rule(404, () -> s.delete(b, t.id));
            rule(404, () -> s.update(a, 9999, Map.of("title", "x")));
            eq("Alice's", s.list(a).get(0).title);
        });
        test("status changes are recorded in the history", () -> {
            Store s = new Store(null);
            Store.User a = s.register("alice", PW);
            Store.Task t = s.create(a, Map.of("title", "T"));
            eq("todo", t.status);
            s.update(a, t.id, Map.of("status", "doing"));
            s.update(a, t.id, Map.of("status", "doing"));
            s.update(a, t.id, Map.of("status", "done"));
            eq(2, t.history.size());
            eq("done", t.history.get(1).get("status"));
        });
        test("usernames are unique regardless of case; wrong passwords fail", () -> {
            Store s = new Store(null);
            s.register("Alice", PW);
            rule(409, () -> s.register("ALICE", PW));
            yes(s.authenticate("alice", PW) != null, "login");
            yes(s.authenticate("alice", "nope-nope-nope") == null && s.authenticate("ghost", PW) == null, "rejects");
        });
        test("sessions expire and can be ended", () -> {
            Store s = new Store(null);
            Store.User a = s.register("alice", PW);
            Instant now = Instant.now();
            String tok = s.newSession(a, now);
            yes(s.userForSession(tok, now) != null, "valid");
            yes(s.userForSession(tok, now.plusSeconds(Store.SESSION_SECONDS + 5)) == null, "expired");
            String tok2 = s.newSession(a, now);
            s.endSession(tok2);
            yes(s.userForSession(tok2, now) == null && s.userForSession(null, now) == null && s.userForSession("forged", now) == null, "ended or forged");
        });
        test("per-user task limit", () -> {
            Store s = new Store(null);
            Store.User a = s.register("alice", PW);
            for (int i = 0; i < Store.MAX_TASKS_PER_USER; i++) s.create(a, Map.of("title", "t" + i));
            rule(409, () -> s.create(a, Map.of("title", "one too many")));
        });
        test("data survives a restart", () -> {
            Path dir = Files.createTempDirectory("tm");
            Store s = new Store(dir.resolve("data.json"));
            Store.User a = s.register("alice", PW);
            Store.Task t = s.create(a, Map.of("title", "Keep me \u00e9", "notes", "n", "priority", "high", "due", "2026-05-01"));
            s.update(a, t.id, Map.of("status", "doing"));
            Store back = new Store(dir.resolve("data.json"));
            Store.User a2 = back.authenticate("alice", PW);
            yes(a2 != null, "login after restart");
            Store.Task t2 = back.list(a2).get(0);
            eq("Keep me \u00e9", t2.title); eq("doing", t2.status); eq("high", t2.priority); eq("2026-05-01", t2.due.toString()); eq(1, t2.history.size());
            eq(t.id + 1, back.create(a2, Map.of("title", "next")).id);
        });

        /* ---------- over HTTP ---------- */
        Path dir = Files.createTempDirectory("tm-http");
        Server server = startServer(dir);
        String base = "http://127.0.0.1:" + server.port();
        try {
            test("static page is served with security headers; nothing else is", () -> {
                Client c = new Client(base);
                var r = c.call("GET", "/", null);
                eq(200, r.statusCode());
                yes(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"), "CSP");
                eq("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
                for (String p : new String[] {"/data.json", "/../data/data.json", "/%2e%2e/data/data.json", "/nope"})
                    eq(404, c.call("GET", p, null).statusCode());
            });
            test("sign up, use the session, sign out", () -> {
                Client c = new Client(base);
                eq(401, c.call("GET", "/api/me", null).statusCode());
                var r = c.call("POST", "/api/register", "{\"username\":\"kc_01\",\"password\":\"" + PW + "\"}");
                eq(201, r.statusCode());
                String cookie = r.headers().firstValue("Set-Cookie").orElse("");
                yes(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "cookie flags: " + cookie);
                eq("kc_01", c.json(c.call("GET", "/api/me", null)).get("username"));
                eq(200, c.call("POST", "/api/logout", "{}").statusCode());
                eq(401, c.call("GET", "/api/me", null).statusCode());
            });
            test("the full task lifecycle", () -> {
                Client c = new Client(base);
                c.call("POST", "/api/register", "{\"username\":\"lifecycle\",\"password\":\"" + PW + "\"}");
                var created = c.call("POST", "/api/tasks", "{\"title\":\"Write report\",\"priority\":\"high\",\"due\":\"2026-06-01\"}");
                eq(201, created.statusCode());
                long id = ((Number) c.json(created).get("id")).longValue();
                var moved = c.call("PATCH", "/api/tasks/" + id, "{\"status\":\"doing\"}");
                eq(200, moved.statusCode());
                eq("doing", c.json(moved).get("status"));
                eq(1, ((List<?>) c.json(moved).get("history")).size());
                eq(1, ((List<?>) c.json(c.call("GET", "/api/tasks", null)).get("tasks")).size());
                eq(204, c.call("DELETE", "/api/tasks/" + id, null).statusCode());
                eq(404, c.call("DELETE", "/api/tasks/" + id, null).statusCode());
                eq(0, ((List<?>) c.json(c.call("GET", "/api/tasks", null)).get("tasks")).size());
            });
            test("invalid input gets field-level messages, not a crash", () -> {
                Client c = new Client(base);
                var weak = c.call("POST", "/api/register", "{\"username\":\"x\",\"password\":\"short\"}");
                eq(422, weak.statusCode());
                yes(obj(c.json(weak).get("errors")).containsKey("password"), "password message");
                c.call("POST", "/api/register", "{\"username\":\"validation\",\"password\":\"" + PW + "\"}");
                var bad = c.call("POST", "/api/tasks", "{\"title\":\"\",\"status\":null,\"due\":\"nope\"}");
                eq(422, bad.statusCode());
                yes(obj(c.json(bad).get("errors")).size() == 3, bad.body());
                eq(422, c.call("PATCH", "/api/tasks/1", "{}").statusCode());
            });
            test("one user can't see, change or delete another's task", () -> {
                Client a = new Client(base), b = new Client(base);
                a.call("POST", "/api/register", "{\"username\":\"owner_a\",\"password\":\"" + PW + "\"}");
                b.call("POST", "/api/register", "{\"username\":\"owner_b\",\"password\":\"" + PW + "\"}");
                long id = ((Number) a.json(a.call("POST", "/api/tasks", "{\"title\":\"private\"}")).get("id")).longValue();
                eq(0, ((List<?>) b.json(b.call("GET", "/api/tasks", null)).get("tasks")).size());
                eq(404, b.call("PATCH", "/api/tasks/" + id, "{\"title\":\"hijacked\"}").statusCode());
                eq(404, b.call("DELETE", "/api/tasks/" + id, null).statusCode());
                eq("private", ((Map<?, ?>) ((List<?>) a.json(a.call("GET", "/api/tasks", null)).get("tasks")).get(0)).get("title"));
            });
            test("requests without the app's header or from another origin are refused", () -> {
                Client c = new Client(base);
                c.call("POST", "/api/register", "{\"username\":\"csrf_user\",\"password\":\"" + PW + "\"}");
                var noHeader = c.http.send(HttpRequest.newBuilder(URI.create(base + "/api/tasks")).header("Content-Type", "application/json").header("Cookie", "sid=" + c.sid)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(403, noHeader.statusCode());
                eq(403, c.send("POST", "/api/tasks", "{\"title\":\"x\"}", Map.of("Origin", "https://evil.example")).statusCode());
                eq(201, c.send("POST", "/api/tasks", "{\"title\":\"x\"}", Map.of("Origin", base)).statusCode());
            });
            test("protocol limits: JSON only, bad JSON, oversize bodies, unauthenticated", () -> {
                Client c = new Client(base);
                eq(401, c.call("GET", "/api/tasks", null).statusCode());
                eq(415, c.send("POST", "/api/login", "{}", Map.of("Content-Type", "text/plain")).statusCode());
                eq(400, c.call("POST", "/api/login", "{nope").statusCode());
                eq(400, c.call("POST", "/api/login", "[1]").statusCode());
                eq(413, c.call("POST", "/api/login", "{\"username\":\"" + "a".repeat(70_000) + "\"}").statusCode());
                eq(405, c.call("PUT", "/api/login", "{}").statusCode());
            });
            test("wrong passwords are generic and rate limited", () -> {
                Client c = new Client(base);
                c.call("POST", "/api/register", "{\"username\":\"limited\",\"password\":\"" + PW + "\"}");
                Client d = new Client(base);
                var unknown = d.call("POST", "/api/login", "{\"username\":\"nobody_here\",\"password\":\"whatever-it-is\"}");
                var wrong = d.call("POST", "/api/login", "{\"username\":\"limited\",\"password\":\"whatever-it-is\"}");
                eq(401, unknown.statusCode()); eq(unknown.body(), wrong.body());
                for (int i = 0; i < 4; i++) d.call("POST", "/api/login", "{\"username\":\"limited\",\"password\":\"whatever-it-is\"}");
                var blocked = d.call("POST", "/api/login", "{\"username\":\"limited\",\"password\":\"" + PW + "\"}");
                eq(429, blocked.statusCode());
                yes(blocked.headers().firstValue("Retry-After").isPresent(), "Retry-After");
                eq(200, new Client(base).call("POST", "/api/login", "{\"username\":\"validation\",\"password\":\"" + PW + "\"}").statusCode());
            });
        } finally {
            server.stop();
        }

        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) System.exit(1);
    }
}
