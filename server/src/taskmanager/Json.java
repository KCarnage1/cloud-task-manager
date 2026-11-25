package taskmanager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader and writer. Objects are Map, arrays are List, numbers are Long or Double. */
public final class Json {
    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        ParseException(String msg, int pos) { super(msg + " at position " + pos); }
    }

    private static final int MAX_DEPTH = 32;
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw new ParseException("Unexpected data after the JSON value", p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new ParseException("Expected a JSON object", 0);
        return (Map<String, Object>) v;
    }

    private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

    private char peek() {
        if (i >= s.length()) throw new ParseException("Unexpected end of JSON", i);
        return s.charAt(i);
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw new ParseException("JSON is nested too deeply", i);
        char c = peek();
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw new ParseException("Expected a string key", i);
            String k = string();
            ws();
            if (peek() != ':') throw new ParseException("Expected ':'", i);
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw new ParseException("Expected ',' or '}'", i - 1);
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == ']') return l;
            if (c != ',') throw new ParseException("Expected ',' or ']'", i - 1);
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            char c = peek();
            i++;
            if (c == '"') return sb.toString();
            if (c < 0x20) throw new ParseException("Control character in string", i - 1);
            if (c != '\\') { sb.append(c); continue; }
            char e = peek();
            i++;
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw new ParseException("Bad \\u escape", i);
                    try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                    catch (NumberFormatException ex) { throw new ParseException("Bad \\u escape", i); }
                    i += 4;
                }
                default -> throw new ParseException("Bad escape \\" + e, i - 1);
            }
        }
    }

    private Number number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        if (t.isEmpty() || t.equals("-")) throw new ParseException("Unexpected character '" + s.charAt(start) + "'", start);
        try {
            return t.matches("-?\\d+") ? (Number) Long.parseLong(t) : (Number) Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new ParseException("Bad number '" + t + "'", start);
        }
    }

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String str) quote(sb, str);
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else quote(sb, o.toString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
