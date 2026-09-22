package in.simplifymoney.ledgersync.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader/writer, so this project builds with nothing but a JDK.
 *
 * Numbers are read as BigDecimal. That is deliberate: this codebase handles
 * money, and a JSON library that hands you a double has already lost.
 *
 * You may replace this with Jackson or Gson if you prefer. If you do, say why
 * in your decision log.
 */
public final class Json {

    private Json() {}

    // ---------------------------------------------------------------- read

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw p.err("trailing content");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        RuntimeException err(String msg) {
            return new IllegalArgumentException("JSON at index " + i + ": " + msg);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return number();
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, i)) throw err("expected " + lit);
            i += lit.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++; ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw err("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected string");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw err("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad \\u escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw err("unknown escape \\" + e);
                }
            }
        }

        BigDecimal number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw err("expected value");
            return new BigDecimal(s.substring(start, i));
        }
    }

    // --------------------------------------------------------------- write

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b, 0, false);
        return b.toString();
    }

    public static String writePretty(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b, 0, true);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b, int depth, boolean pretty) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String str) { escape(str, b); return; }
        if (v instanceof Boolean || v instanceof BigDecimal) { b.append(v); return; }
        if (v instanceof Number n) { b.append(n); return; }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, depth + 1, pretty);
                escape(String.valueOf(e.getKey()), b);
                b.append(':');
                if (pretty) b.append(' ');
                write(e.getValue(), b, depth + 1, pretty);
            }
            nl(b, depth, pretty);
            b.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) b.append(',');
                first = false;
                nl(b, depth + 1, pretty);
                write(o, b, depth + 1, pretty);
            }
            if (!first) nl(b, depth, pretty);
            b.append(']');
            return;
        }
        escape(String.valueOf(v), b);
    }

    private static void nl(StringBuilder b, int depth, boolean pretty) {
        if (!pretty) return;
        b.append('\n');
        b.append("  ".repeat(depth));
    }

    private static void escape(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
