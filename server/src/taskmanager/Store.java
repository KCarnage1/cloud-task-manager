package taskmanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Users, sessions and tasks. One lock guards everything; each change is saved to disk before the call returns. */
public final class Store {
    public static final class RuleException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public RuleException(int status, String message) { super(message); this.status = status; }
    }

    public record User(long id, String username, String passwordHash, Instant created) {}

    public static final class Task {
        public final long id;
        public final long ownerId;
        public String title, notes, status, priority;
        public LocalDate due;
        public final Instant created;
        public Instant updated;
        public final List<Map<String, Object>> history = new ArrayList<>();

        Task(long id, long ownerId, String title, Instant now) {
            this.id = id; this.ownerId = ownerId; this.title = title; this.created = now; this.updated = now;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("title", title); m.put("notes", notes == null ? "" : notes);
            m.put("status", status); m.put("priority", priority); m.put("due", due == null ? null : due.toString());
            m.put("created", created.toString()); m.put("updated", updated.toString()); m.put("history", history);
            return m;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    static final long SESSION_SECONDS = 7 * 24 * 3600;
    static final int MAX_TASKS_PER_USER = 500;

    private final Path file;
    private final Map<Long, User> users = new LinkedHashMap<>();
    private final Map<Long, Task> tasks = new LinkedHashMap<>();
    private final Map<String, long[]> sessions = new HashMap<>();   // token hash -> {userId, expiresEpochSecond}
    private long nextUserId = 1, nextTaskId = 1;

    public Store(Path file) throws IOException {
        this.file = file;
        if (file != null && Files.exists(file)) load();
    }

    /* ---------- accounts ---------- */

    public synchronized User register(String username, String password) throws IOException {
        String name = username.toLowerCase();
        if (findUser(name) != null) throw new RuleException(409, "That username is taken.");
        User u = new User(nextUserId++, name, Passwords.hash(password), Instant.now());
        users.put(u.id(), u);
        save();
        return u;
    }

    /** Returns the user, or null for a wrong username or password. Takes the same time either way. */
    public User authenticate(String username, String password) {
        User u;
        synchronized (this) { u = findUser(username.toLowerCase()); }
        boolean ok = Passwords.verify(password, u != null ? u.passwordHash() : Passwords.DUMMY);
        return ok && u != null ? u : null;
    }

    private User findUser(String name) {
        for (User u : users.values()) if (u.username().equals(name)) return u;
        return null;
    }

    /* ---------- sessions ---------- */

    public synchronized String newSession(User u, Instant now) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(hashToken(token), new long[] {u.id(), now.getEpochSecond() + SESSION_SECONDS});
        sessions.values().removeIf(v -> v[1] < now.getEpochSecond());
        return token;
    }

    public synchronized User userForSession(String token, Instant now) {
        if (token == null) return null;
        long[] s = sessions.get(hashToken(token));
        if (s == null) return null;
        if (s[1] < now.getEpochSecond()) { sessions.remove(hashToken(token)); return null; }
        return users.get(s[0]);
    }

    public synchronized void endSession(String token) { if (token != null) sessions.remove(hashToken(token)); }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---------- tasks (every method takes the caller and only touches that user's tasks) ---------- */

    public synchronized List<Task> list(User u) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) if (t.ownerId == u.id()) out.add(t);
        out.sort(Comparator.comparing((Task t) -> t.created).reversed());
        return out;
    }

    public synchronized Task create(User u, Map<String, Object> body) throws IOException {
        if (list(u).size() >= MAX_TASKS_PER_USER) throw new RuleException(409, "You've reached the limit of " + MAX_TASKS_PER_USER + " tasks. Delete some to add more.");
        Instant now = Instant.now();
        Task t = new Task(nextTaskId++, u.id(), ((String) body.get("title")).strip(), now);
        t.status = "todo"; t.priority = "normal";
        apply(t, body, now);
        tasks.put(t.id, t);
        save();
        return t;
    }

    public synchronized Task update(User u, long id, Map<String, Object> body) throws IOException {
        Task t = owned(u, id);
        apply(t, body, Instant.now());
        save();
        return t;
    }

    public synchronized void delete(User u, long id) throws IOException {
        tasks.remove(owned(u, id).id);
        save();
    }

    /** Someone else's task looks exactly like a missing one, so IDs can't be probed. */
    private Task owned(User u, long id) {
        Task t = tasks.get(id);
        if (t == null || t.ownerId != u.id()) throw new RuleException(404, "No such task.");
        return t;
    }

    private void apply(Task t, Map<String, Object> body, Instant now) {
        if (body.containsKey("title")) t.title = ((String) body.get("title")).strip();
        if (body.containsKey("notes")) t.notes = body.get("notes") == null ? "" : ((String) body.get("notes")).strip();
        if (body.containsKey("priority")) t.priority = (String) body.get("priority");
        if (body.containsKey("due")) t.due = body.get("due") == null ? null : LocalDate.parse((String) body.get("due"));
        if (body.containsKey("status") && !body.get("status").equals(t.status)) {
            t.status = (String) body.get("status");
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("status", t.status); h.put("at", now.toString());
            t.history.add(h);
        }
        t.updated = now;
    }

    /* ---------- persistence ---------- */

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        nextUserId = ((Number) root.get("nextUserId")).longValue();
        nextTaskId = ((Number) root.get("nextTaskId")).longValue();
        for (Object o : (List<Object>) root.get("users")) {
            Map<String, Object> m = (Map<String, Object>) o;
            User u = new User(((Number) m.get("id")).longValue(), (String) m.get("username"), (String) m.get("passwordHash"), Instant.parse((String) m.get("created")));
            users.put(u.id(), u);
        }
        for (Object o : (List<Object>) root.get("tasks")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Task t = new Task(((Number) m.get("id")).longValue(), ((Number) m.get("ownerId")).longValue(), (String) m.get("title"), Instant.parse((String) m.get("created")));
            t.notes = (String) m.get("notes"); t.status = (String) m.get("status"); t.priority = (String) m.get("priority");
            t.due = m.get("due") == null ? null : LocalDate.parse((String) m.get("due"));
            t.updated = Instant.parse((String) m.get("updated"));
            t.history.addAll((List<Map<String, Object>>) m.get("history"));
            tasks.put(t.id, t);
        }
    }

    private void save() throws IOException {
        if (file == null) return;
        List<Object> us = new ArrayList<>(), ts = new ArrayList<>();
        for (User u : users.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id()); m.put("username", u.username()); m.put("passwordHash", u.passwordHash()); m.put("created", u.created().toString());
            us.add(m);
        }
        for (Task t : tasks.values()) {
            Map<String, Object> m = t.toMap();
            m.put("ownerId", t.ownerId);
            ts.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nextUserId", nextUserId); root.put("nextTaskId", nextTaskId); root.put("users", us); root.put("tasks", ts);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.stringify(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
