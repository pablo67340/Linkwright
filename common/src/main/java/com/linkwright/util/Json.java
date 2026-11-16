package com.linkwright.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, lenient JSON reader for mod metadata ({@code mcmod.info}, {@code fabric.mod.json},
 * {@code quilt.mod.json}). Objects become {@code Map<String, Object>}, arrays {@code List<Object>};
 * strings, booleans, integers ({@code Long}) and other numbers ({@code Double}) keep their types.
 *
 * <p>Like the loaders' own readers it tolerates what hand-written metadata often contains:
 * comments, trailing commas and raw line breaks inside strings.
 */
public final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** @throws IllegalArgumentException when the text is not JSON */
    public static Object parse(String text) {
        Json json = new Json(text.startsWith("\uFEFF") ? text.substring(1) : text);
        Object value = json.value();
        json.skip();
        if (json.pos < json.src.length()) {
            throw json.error("unexpected text after the value");
        }
        return value;
    }

    private Object value() {
        skip();
        if (pos >= src.length()) {
            throw error("unexpected end");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
            case '\'':
                return string(c);
            default:
                return literal();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        while (true) {
            skip();
            if (peek() == '}') {
                pos++;
                return map;
            }
            char c = peek();
            String key = c == '"' || c == '\'' ? string(c) : String.valueOf(literal());
            skip();
            expect(':');
            map.put(key, value());
            skip();
            if (peek() == ',') {
                pos++;
            } else if (peek() != '}') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        while (true) {
            skip();
            if (peek() == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skip();
            if (peek() == ',') {
                pos++;
            } else if (peek() != ']') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string(char quote) {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                break;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw error("bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: sb.append(e);
            }
        }
        throw error("unterminated string");
    }

    /** Numbers, {@code true}, {@code false}, {@code null}, and bare words read as strings. */
    private Object literal() {
        int start = pos;
        while (pos < src.length() && ",:]}".indexOf(src.charAt(pos)) < 0 && !Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
        String word = src.substring(start, pos);
        if (word.isEmpty()) {
            throw error("expected a value");
        }
        if (word.equals("true")) {
            return Boolean.TRUE;
        }
        if (word.equals("false")) {
            return Boolean.FALSE;
        }
        if (word.equals("null")) {
            return null;
        }
        try {
            return Long.parseLong(word);
        } catch (NumberFormatException notLong) {
            try {
                return Double.parseDouble(word);
            } catch (NumberFormatException notNumber) {
                return word;
            }
        }
    }

    private void skip() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (src.startsWith("//", pos) || c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (src.startsWith("/*", pos)) {
                int end = src.indexOf("*/", pos + 2);
                pos = end < 0 ? src.length() : end + 2;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + pos);
    }
}
