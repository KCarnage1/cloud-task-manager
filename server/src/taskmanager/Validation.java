package taskmanager;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Every rule the API enforces on input. Returns field-by-field messages written for the person typing. */
public final class Validation {
    public static final Set<String> STATUSES = Set.of("todo", "doing", "done");
    public static final Set<String> PRIORITIES = Set.of("low", "normal", "high");
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");
    private static final Set<String> COMMON = Set.of("password", "password1", "1234567890", "qwertyuiop", "iloveyou12", "letmein123");

    private Validation() {}

    public static Map<String, String> account(Object username, Object password) {
        Map<String, String> e = new LinkedHashMap<>();
        if (!(username instanceof String u) || !USERNAME.matcher(u.toLowerCase()).matches())
            e.put("username", "Use 3 to 32 letters, numbers or underscores.");
        if (!(password instanceof String p)) e.put("password", "Enter a password.");
        else if (p.length() < 10) e.put("password", "Use at least 10 characters.");
        else if (p.length() > 128) e.put("password", "Use 128 characters or fewer.");
        else if (COMMON.contains(p.toLowerCase())) e.put("password", "That password is too common. Pick something less guessable.");
        else if (username instanceof String u2 && p.equalsIgnoreCase(u2)) e.put("password", "The password can't be the same as the username.");
        return e;
    }

    /** Checks the fields present in a create or update body. On create every required field must be present. */
    public static Map<String, String> task(Map<String, Object> body, boolean create) {
        Map<String, String> e = new LinkedHashMap<>();
        for (String k : body.keySet())
            if (!List.of("title", "notes", "status", "priority", "due").contains(k)) e.put(k, "Unknown field.");
        if (create && !body.containsKey("title")) e.put("title", "Give the task a title.");
        if (body.containsKey("title")) {
            if (!(body.get("title") instanceof String t) || t.isBlank()) e.put("title", "Give the task a title.");
            else if (t.strip().length() > 120) e.put("title", "Keep the title under 120 characters.");
        }
        if (body.containsKey("notes") && body.get("notes") != null) {
            if (!(body.get("notes") instanceof String n)) e.put("notes", "Notes must be text.");
            else if (n.length() > 2000) e.put("notes", "Keep notes under 2000 characters.");
        }
        if (body.containsKey("status") && !(body.get("status") instanceof String st && STATUSES.contains(st)))
            e.put("status", "Status must be todo, doing or done.");
        if (body.containsKey("priority") && !(body.get("priority") instanceof String pr && PRIORITIES.contains(pr)))
            e.put("priority", "Priority must be low, normal or high.");
        if (body.containsKey("due") && body.get("due") != null) {
            if (!(body.get("due") instanceof String d)) e.put("due", "Use a date like 2026-03-31.");
            else {
                try {
                    LocalDate.parse(d);
                } catch (DateTimeException ex) {
                    e.put("due", "Use a real date like 2026-03-31.");
                }
            }
        }
        return e;
    }
}
