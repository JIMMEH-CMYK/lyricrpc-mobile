package com.lyricrpc.mobile.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader. Android ships org.json, but the core is also compiled and tested on a plain JVM
 * where that class does not exist, and a lyric response is the easiest possible parser job. Numbers keep
 * their original text: AMLL ids are 64-bit integers, and reading them as doubles silently rounds anything
 * above 2^53, which then 404s on the follow-up request.
 */
public final class Json {
    private static final Json NULL = new Json(Type.NULL);

    private enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    private final Type type;
    private final Map<String, Json> members;
    private final List<Json> elements;

    /** Holds the string for STRING nodes and the untouched token for NUMBER nodes. */
    private final String text;
    private final boolean bool;

    private Json(Type type) {
        this(type, null, false);
    }

    private Json(Type type, String text, boolean bool) {
        this.type = type;
        this.members = type == Type.OBJECT ? new LinkedHashMap<String, Json>() : null;
        this.elements = type == Type.ARRAY ? new ArrayList<Json>() : null;
        this.text = text;
        this.bool = bool;
    }

    private static Json string(String value) {
        return new Json(Type.STRING, value, false);
    }

    private static Json number(String raw) {
        return new Json(Type.NUMBER, raw, false);
    }

    private static Json bool(boolean value) {
        return new Json(Type.BOOLEAN, null, value);
    }

    public static Json parse(String source) {
        Parser parser = new Parser(source == null ? "" : source);
        Json value = parser.value();
        parser.skipSpaces();
        return value;
    }

    /** The answer to hand back when a search found nothing, so callers can chain reads without null checks. */
    public static Json empty() {
        return NULL;
    }

    public boolean isNull() {
        return type == Type.NULL;
    }

    public boolean isArray() {
        return type == Type.ARRAY;
    }

    public boolean isObject() {
        return type == Type.OBJECT;
    }

    public Json get(String key) {
        if (members == null) return NULL;
        Json value = members.get(key);
        return value == null ? NULL : value;
    }

    /** Walks a chain of keys, so a caller can say data.get("track").get("title") without null ladders. */
    public Json path(String... keys) {
        Json node = this;
        for (String key : keys) {
            node = node.get(key);
            if (node.isNull()) return node;
        }
        return node;
    }

    public Json at(int index) {
        if (elements == null || index < 0 || index >= elements.size()) return NULL;
        return elements.get(index);
    }

    public int size() {
        if (elements != null) return elements.size();
        if (members != null) return members.size();
        if (type == Type.NULL) return 0;
        return 1;
    }

    public List<Json> items() {
        return elements == null ? new ArrayList<Json>() : elements;
    }

    public List<Json> itemsOf(String key) {
        return get(key).items();
    }

    public String asText(String fallback) {
        switch (type) {
            case STRING:
            case NUMBER:
                return text;
            case BOOLEAN:
                return bool ? "true" : "false";
            default:
                return fallback;
        }
    }

    public double asNumber(double fallback) {
        if (type != Type.NUMBER && type != Type.STRING) return fallback;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Reads the token as an exact long so a 64-bit id survives the round trip. */
    public long asLong(long fallback) {
        if (type == Type.NUMBER) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // fall through to the double path for "1.0"-style tokens
            }
        }
        double value = asNumber(Double.NaN);
        return Double.isNaN(value) ? fallback : Math.round(value);
    }

    public boolean asBoolean(boolean fallback) {
        if (type == Type.BOOLEAN) return bool;
        if (type == Type.NULL) return fallback;
        return Boolean.parseBoolean(asText("false"));
    }

    @Override
    public String toString() {
        return asText(type == Type.NULL ? "null" : "?");
    }

    private static final class Parser {
        private final String src;
        private int at;

        Parser(String source) {
            this.src = source;
        }

        Json value() {
            skipSpaces();
            if (at >= src.length()) throw error("unexpected end of JSON");
            char ch = src.charAt(at);
            if (ch == '{') return object();
            if (ch == '[') return array();
            if (ch == '"') return Json.string(string());
            if (ch == 't' || ch == 'f') return literalBool();
            if (ch == 'n') {
                expect("null");
                return NULL;
            }
            return number();
        }

        private Json object() {
            Json node = new Json(Type.OBJECT);
            at++;
            skipSpaces();
            if (peek() == '}') {
                at++;
                return node;
            }
            while (true) {
                skipSpaces();
                String key = string();
                skipSpaces();
                if (peek() != ':') throw error("expected : after " + key);
                at++;
                node.members.put(key, value());
                skipSpaces();
                char next = peek();
                if (next == ',') {
                    at++;
                    continue;
                }
                if (next == '}') {
                    at++;
                    return node;
                }
                throw error("expected , or } in object");
            }
        }

        private Json array() {
            Json node = new Json(Type.ARRAY);
            at++;
            skipSpaces();
            if (peek() == ']') {
                at++;
                return node;
            }
            while (true) {
                node.elements.add(value());
                skipSpaces();
                char next = peek();
                if (next == ',') {
                    at++;
                    skipSpaces();
                    if (peek() == ']') {
                        at++;
                        return node;
                    }
                    continue;
                }
                if (next == ']') {
                    at++;
                    return node;
                }
                throw error("expected , or ] in array");
            }
        }

        private Json number() {
            int start = at;
            while (at < src.length() && "-+0123456789.eE".indexOf(src.charAt(at)) >= 0) at++;
            String raw = src.substring(start, at);
            if (raw.isEmpty()) throw error("bad value");
            return Json.number(raw);
        }

        private Json literalBool() {
            if (src.startsWith("true", at)) {
                at += 4;
                return Json.bool(true);
            }
            if (src.startsWith("false", at)) {
                at += 5;
                return Json.bool(false);
            }
            throw error("bad literal");
        }

        private String string() {
            if (peek() != '"') throw error("expected a string");
            at++;
            StringBuilder out = new StringBuilder();
            while (at < src.length()) {
                char ch = src.charAt(at++);
                if (ch == '"') return out.toString();
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (at >= src.length()) break;
                char escape = src.charAt(at++);
                switch (escape) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case '/': out.append('/'); break;
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case 'u':
                        if (at + 4 > src.length()) throw error("bad \\u escape");
                        out.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default:
                        throw error("unsupported escape \\" + escape);
                }
            }
            throw error("unterminated string");
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, at)) throw error("expected " + literal);
            at += literal.length();
        }

        private char peek() {
            return at < src.length() ? src.charAt(at) : '\0';
        }

        private void skipSpaces() {
            while (at < src.length() && Character.isWhitespace(src.charAt(at))) at++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + at);
        }
    }
}
