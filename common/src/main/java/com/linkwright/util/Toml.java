package com.linkwright.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small TOML reader, enough for {@code mods.toml}, {@code neoforge.mods.toml}, {@code fml.toml}
 * and Linkwright's own config. Tables become {@code Map<String, Object>}, arrays
 * {@code List<Object>}; strings, booleans, integers ({@code Long}) and floats ({@code Double})
 * keep their types. Dates are read as their text.
 *
 * <p>Modern loaders ship a full TOML library, but legacy Forge and Fabric do not, and the core has
 * to run on all of them.
 */
public final class Toml {
    private final String src;
    private int pos;

    private Toml(String src) {
        this.src = src;
    }

    public static Map<String, Object> parse(String text) {
        Toml toml = new Toml(text.startsWith("\uFEFF") ? text.substring(1) : text);
        Map<String, Object> root = new LinkedHashMap<>();
        toml.document(root);
        return root;
    }

    /** {@code get(table, "a", "b")} reads {@code a.b}, or {@code null} when absent. */
    @SuppressWarnings("unchecked")
    public static Object get(Map<String, Object> table, String... path) {
        Object node = table;
        for (String key : path) {
            if (!(node instanceof Map)) {
                return null;
            }
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private void document(Map<String, Object> root) {
        Map<String, Object> current = root;
        while (true) {
            skipWhitespaceAndComments(true);
            if (pos >= src.length()) {
                return;
            }
            char c = src.charAt(pos);
            if (c == '[') {
                boolean arrayOfTables = pos + 1 < src.length() && src.charAt(pos + 1) == '[';
                pos += arrayOfTables ? 2 : 1;
                List<String> keys = keyPath();
                expect(']');
                if (arrayOfTables) {
                    expect(']');
                }
                Map<String, Object> parent = root;
                for (int i = 0; i < keys.size() - 1; i++) {
                    parent = descend(parent, keys.get(i));
                }
                String last = keys.get(keys.size() - 1);
                if (arrayOfTables) {
                    Object existing = parent.get(last);
                    List<Object> list;
                    if (existing instanceof List) {
                        list = (List<Object>) existing;
                    } else {
                        list = new ArrayList<>();
                        parent.put(last, list);
                    }
                    Map<String, Object> table = new LinkedHashMap<>();
                    list.add(table);
                    current = table;
                } else {
                    current = descend(parent, last);
                }
                endOfLine();
            } else {
                keyValue(current);
                endOfLine();
            }
        }
    }

    /** Moves into a sub-table, or into the last table of an array of tables. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> descend(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        if (existing instanceof List && !((List<Object>) existing).isEmpty()) {
            Object last = ((List<Object>) existing).get(((List<Object>) existing).size() - 1);
            if (last instanceof Map) {
                return (Map<String, Object>) last;
            }
        }
        Map<String, Object> table = new LinkedHashMap<>();
        parent.put(key, table);
        return table;
    }

    private void keyValue(Map<String, Object> table) {
        List<String> keys = keyPath();
        skipInlineWhitespace();
        expect('=');
        skipInlineWhitespace();
        Object value = value();
        Map<String, Object> target = table;
        for (int i = 0; i < keys.size() - 1; i++) {
            target = descend(target, keys.get(i));
        }
        target.put(keys.get(keys.size() - 1), value);
    }

    private List<String> keyPath() {
        List<String> keys = new ArrayList<>();
        while (true) {
            skipInlineWhitespace();
            char c = peek();
            if (c == '"') {
                keys.add(basicString());
            } else if (c == '\'') {
                keys.add(literalString());
            } else {
                int start = pos;
                while (pos < src.length()) {
                    char k = src.charAt(pos);
                    if (Character.isLetterOrDigit(k) || k == '_' || k == '-') {
                        pos++;
                    } else {
                        break;
                    }
                }
                if (start == pos) {
                    throw error("expected a key");
                }
                keys.add(src.substring(start, pos));
            }
            skipInlineWhitespace();
            if (peek() == '.') {
                pos++;
            } else {
                return keys;
            }
        }
    }

    private Object value() {
        char c = peek();
        if (c == '"') {
            return src.startsWith("\"\"\"", pos) ? multilineBasicString() : basicString();
        }
        if (c == '\'') {
            return src.startsWith("'''", pos) ? multilineLiteralString() : literalString();
        }
        if (c == '[') {
            return array();
        }
        if (c == '{') {
            return inlineTable();
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        return scalar();
    }

    private Object scalar() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ',' || c == ']' || c == '}' || c == '#' || c == '\n' || c == '\r') {
                break;
            }
            pos++;
        }
        String text = src.substring(start, pos).trim();
        if (text.isEmpty()) {
            throw error("expected a value");
        }
        String number = text.replace("_", "");
        try {
            if (number.startsWith("0x")) {
                return Long.parseLong(number.substring(2), 16);
            }
            if (number.startsWith("0o")) {
                return Long.parseLong(number.substring(2), 8);
            }
            if (number.startsWith("0b")) {
                return Long.parseLong(number.substring(2), 2);
            }
            return Long.parseLong(number);
        } catch (NumberFormatException notInteger) {
            try {
                return Double.parseDouble(number);
            } catch (NumberFormatException notFloat) {
                return text;
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments(true);
            if (peek() == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skipWhitespaceAndComments(true);
            if (peek() == ',') {
                pos++;
            } else {
                skipWhitespaceAndComments(true);
                expect(']');
                return list;
            }
        }
    }

    private Map<String, Object> inlineTable() {
        expect('{');
        Map<String, Object> table = new LinkedHashMap<>();
        while (true) {
            skipWhitespaceAndComments(true);
            if (peek() == '}') {
                pos++;
                return table;
            }
            keyValue(table);
            skipWhitespaceAndComments(true);
            if (peek() == ',') {
                pos++;
            } else {
                skipWhitespaceAndComments(true);
                expect('}');
                return table;
            }
        }
    }

    private String basicString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                escape(sb);
            } else {
                sb.append(c);
            }
        }
    }

    private String multilineBasicString() {
        pos += 3;
        if (src.startsWith("\r\n", pos)) {
            pos += 2;
        } else if (peek() == '\n') {
            pos++;
        }
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            if (src.startsWith("\"\"\"", pos)) {
                pos += 3;
                // Up to two more quotes may close the string as part of its content
                while (peek() == '"') {
                    sb.append('"');
                    pos++;
                }
                return sb.toString();
            }
            char c = src.charAt(pos++);
            if (c == '\\') {
                if (peek() == '\n' || peek() == '\r' || peek() == ' ' || peek() == '\t') {
                    // Line-ending backslash trims the newline and the following whitespace
                    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                        pos++;
                    }
                } else {
                    escape(sb);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private void escape(StringBuilder sb) {
        char e = src.charAt(pos++);
        switch (e) {
            case 'b':
                sb.append('\b');
                break;
            case 't':
                sb.append('\t');
                break;
            case 'n':
                sb.append('\n');
                break;
            case 'f':
                sb.append('\f');
                break;
            case 'r':
                sb.append('\r');
                break;
            case 'u':
                sb.appendCodePoint(Integer.parseInt(src.substring(pos, pos + 4), 16));
                pos += 4;
                break;
            case 'U':
                sb.appendCodePoint(Integer.parseInt(src.substring(pos, pos + 8), 16));
                pos += 8;
                break;
            default:
                sb.append(e);
                break;
        }
    }

    private String literalString() {
        expect('\'');
        int end = src.indexOf('\'', pos);
        if (end < 0) {
            throw error("unterminated string");
        }
        String s = src.substring(pos, end);
        pos = end + 1;
        return s;
    }

    private String multilineLiteralString() {
        pos += 3;
        if (src.startsWith("\r\n", pos)) {
            pos += 2;
        } else if (peek() == '\n') {
            pos++;
        }
        int end = src.indexOf("'''", pos);
        if (end < 0) {
            throw error("unterminated string");
        }
        while (end + 3 < src.length() && src.charAt(end + 3) == '\'') {
            end++;
        }
        String s = src.substring(pos, end);
        pos = end + 3;
        return s;
    }

    private void skipInlineWhitespace() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private void skipWhitespaceAndComments(boolean newlines) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || (newlines && (c == '\n' || c == '\r'))) {
                pos++;
            } else if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private void endOfLine() {
        skipWhitespaceAndComments(false);
        if (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
            throw error("expected the end of the line");
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException("TOML line " + line + ": " + message);
    }
}
