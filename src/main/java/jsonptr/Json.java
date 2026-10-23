// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON codec of the document model: objects are LinkedHashMap&lt;String, Object&gt;, arrays
 * ArrayList&lt;Object&gt;, numbers Double, plus String, Boolean and null; {@link RawMessage},
 * {@link RawObject} and {@link RawArray} hold raw JSON.
 *
 * <p>Decoding follows Go's encoding/json: the whole text is checked for syntax and nesting depth
 * (at most 10000 nested arrays and objects) before any value is converted, numbers become binary64
 * (a number out of the finite range is an error), each byte of an invalid UTF-8 sequence inside a
 * string becomes U+FFFD, and a \\u escape of a surrogate that is not part of a valid pair becomes
 * U+FFFD.
 */
public final class Json {
    /** The maximum number of nested arrays and objects. */
    public static final int MAX_DEPTH = 10000;

    /**
     * A decoding error. {@link #reason()} is syntax, depth, number-range, eof or unexpected-eof;
     * {@link #offset()} is the byte offset of a syntax, depth or number-range error, -1 otherwise.
     */
    public static final class DecodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String reason;
        private final long offset;

        /** Creates the error for reason, found after reading offset bytes (-1 when not located). */
        public DecodeException(String reason, long offset) {
            super(offset < 0 ? reason : reason + " error after " + offset + " bytes");
            this.reason = reason;
            this.offset = offset;
        }

        /** Returns syntax, depth, number-range, eof or unexpected-eof. */
        public String reason() {
            return reason;
        }

        /**
         * Returns the offset of a syntax or depth error, or for a number-range error the offset Go's
         * UnmarshalTypeError reports (one past the index of the byte after the number in the decoded
         * text); -1 for the other reasons.
         */
        public long offset() {
            return offset;
        }
    }

    private final byte[] text;
    private int pos;
    private boolean outOfRange;
    private long rangeOffset = -1;

    private Json(byte[] text) {
        this.text = text;
    }

    /**
     * Decodes exactly one JSON value, with optional surrounding whitespace, from UTF-8 bytes, like
     * Go's json.Unmarshal into an interface{}.
     */
    public static Object parse(byte[] text) {
        JsonSyntax.checkValid(text);
        return decodeValue(text);
    }

    /** Decodes exactly one JSON value from text (encoded as UTF-8 first). */
    public static Object parse(String text) {
        return parse(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns a decoder reading JSON values from in, like Go's json.NewDecoder. */
    public static JsonDecoder newDecoder(InputStream in) {
        return new StreamDecoder(in);
    }

    /** Returns a decoder reading JSON values from bytes. */
    public static JsonDecoder newDecoder(byte[] bytes) {
        return new StreamDecoder(new ByteArrayInputStream(bytes.clone()));
    }

    /** Returns a decoder reading JSON values from the UTF-8 encoding of text. */
    public static JsonDecoder newDecoder(String text) {
        return newDecoder(text.getBytes(StandardCharsets.UTF_8));
    }

    // Converts a value already known to be valid JSON (optional surrounding whitespace).
    static Object decodeValue(byte[] valid) {
        Json p = new Json(valid);
        p.skipSpace();
        Object v = p.value();
        if (p.outOfRange) {
            throw new DecodeException("number-range", p.rangeOffset);
        }
        return v;
    }

    /**
     * Decodes only the outer layer of a raw JSON value: an object becomes a {@link RawObject}, an
     * array a {@link RawArray} (the members are kept raw), any other value is fully decoded.
     */
    static Object decodeLayer(byte[] raw) {
        int i = 0;
        while (i < raw.length && JsonSyntax.isSpace(raw[i] & 0xff)) {
            i++;
        }
        if (i < raw.length && (raw[i] == '{' || raw[i] == '[')) {
            JsonSyntax.checkValid(raw);
            Json p = new Json(raw);
            p.pos = i;
            return raw[i] == '{' ? p.rawObject() : p.rawArray();
        }
        return parse(raw);
    }

    private RawObject rawObject() {
        RawObject members = new RawObject();
        pos++;
        skipSpace();
        if (peek() == '}') {
            return members;
        }
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            pos++; // ':'
            skipSpace();
            members.put(key, rawMember());
            skipSpace();
            if (text[pos++] == '}') {
                return members;
            }
        }
    }

    private RawArray rawArray() {
        RawArray elements = new RawArray();
        pos++;
        skipSpace();
        if (peek() == ']') {
            return elements;
        }
        while (true) {
            skipSpace();
            elements.add(rawMember());
            skipSpace();
            if (text[pos++] == ']') {
                return elements;
            }
        }
    }

    // The raw bytes of the valid value starting at pos.
    private RawMessage rawMember() {
        int start = pos;
        int depth = 0;
        boolean inString = false;
        while (true) {
            int c = text[pos] & 0xff;
            if (inString) {
                if (c == '\\') {
                    pos++;
                } else if (c == '"') {
                    inString = false;
                    if (depth == 0) {
                        pos++;
                        break;
                    }
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
                if (depth == 0) {
                    pos++;
                    break;
                }
            } else if (c == ',' || JsonSyntax.isSpace(c)) {
                if (depth == 0) {
                    break;
                }
            }
            pos++;
        }
        return new RawMessage(Arrays.copyOfRange(text, start, pos));
    }

    private int peek() {
        return pos < text.length ? text[pos] & 0xff : -1;
    }

    private void skipSpace() {
        while (pos < text.length && JsonSyntax.isSpace(text[pos] & 0xff)) {
            pos++;
        }
    }

    // An array or object being decoded: its members so far and, for an object, the key of the
    // member being read.
    private static final class OpenContainer {
        final Map<String, Object> members;
        final List<Object> elements;
        String key;

        OpenContainer(Map<String, Object> members, List<Object> elements) {
            this.members = members;
            this.elements = elements;
        }

        Object container() {
            return members != null ? members : elements;
        }
    }

    // Converts the valid value at pos. Nested arrays and objects are kept on an explicit stack
    // rather than on the call stack, so MAX_DEPTH levels decode on any thread.
    private Object value() {
        Deque<OpenContainer> open = new ArrayDeque<>();
        while (true) {
            Object v;
            int c = peek();
            if (c == '{' || c == '[') {
                pos++;
                skipSpace();
                if (peek() == (c == '{' ? '}' : ']')) {
                    pos++;
                    v = c == '{' ? new LinkedHashMap<String, Object>() : new ArrayList<Object>();
                } else {
                    OpenContainer container = c == '{'
                            ? new OpenContainer(new LinkedHashMap<>(), null)
                            : new OpenContainer(null, new ArrayList<>());
                    open.push(container);
                    nextMember(container);
                    continue;
                }
            } else {
                v = scalar();
            }
            // Store v in the innermost open container, closing every container that ends here
            while (true) {
                OpenContainer container = open.peek();
                if (container == null) {
                    return v;
                }
                if (container.members != null) {
                    container.members.put(container.key, v);
                } else {
                    container.elements.add(v);
                }
                skipSpace();
                int end = text[pos++];
                if (end != '}' && end != ']') {
                    // ',': the container has another member
                    nextMember(container);
                    break;
                }
                open.pop();
                v = container.container();
            }
        }
    }

    // Moves pos to the value of the next member of container, reading the key of an object member.
    private void nextMember(OpenContainer container) {
        skipSpace();
        if (container.members != null) {
            container.key = string();
            skipSpace();
            pos++; // ':'
            skipSpace();
        }
    }

    private Object scalar() {
        switch (peek()) {
            case '"':
                return string();
            case 't':
                pos += 4;
                return Boolean.TRUE;
            case 'f':
                pos += 5;
                return Boolean.FALSE;
            case 'n':
                pos += 4;
                return null;
            default:
                return number();
        }
    }

    private static int hex(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // The code unit of the \\u escape starting at i, or -1 when there is none.
    private int u4(int i) {
        if (i + 6 > text.length || text[i] != '\\' || text[i + 1] != 'u') {
            return -1;
        }
        int r = 0;
        for (int k = i + 2; k < i + 6; k++) {
            r = r * 16 + hex(text[k] & 0xff);
        }
        return r;
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = text[pos] & 0xff;
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                escape(sb);
            } else if (c < 0x80) {
                sb.append((char) c);
                pos++;
            } else {
                pos += decodeRune(sb);
            }
        }
    }

    private void escape(StringBuilder sb) {
        int e = text[pos + 1] & 0xff;
        if (e != 'u') {
            pos += 2;
            switch (e) {
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                default -> sb.append((char) e);
            }
            return;
        }
        int rr = u4(pos);
        pos += 6;
        if (Character.isSurrogate((char) rr)) {
            int rr1 = u4(pos);
            if (Character.isHighSurrogate((char) rr) && rr1 >= 0 && Character.isLowSurrogate((char) rr1)) {
                // A valid pair; consume.
                sb.appendCodePoint(Character.toCodePoint((char) rr, (char) rr1));
                pos += 6;
                return;
            }
            // Invalid surrogate; fall back to the replacement rune.
            rr = 0xFFFD;
        }
        sb.append((char) rr);
    }

    private int cont(int i, int lo, int hi) {
        if (i >= text.length) {
            return -1;
        }
        int b = text[i] & 0xff;
        return b >= lo && b <= hi ? b & 0x3f : -1;
    }

    // Decodes the UTF-8 sequence at pos like Go's utf8.DecodeRune: a valid sequence gives its code
    // point, anything else gives U+FFFD for one byte. Returns the number of bytes consumed.
    private int decodeRune(StringBuilder sb) {
        int b0 = text[pos] & 0xff;
        int n;
        int lo = 0x80;
        int hi = 0xBF;
        int cp;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            n = 2;
            cp = b0 & 0x1f;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            n = 3;
            cp = b0 & 0x0f;
            if (b0 == 0xE0) {
                lo = 0xA0;
            } else if (b0 == 0xED) {
                hi = 0x9F;
            }
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            n = 4;
            cp = b0 & 0x07;
            if (b0 == 0xF0) {
                lo = 0x90;
            } else if (b0 == 0xF4) {
                hi = 0x8F;
            }
        } else {
            sb.append('\uFFFD');
            return 1;
        }
        for (int k = 1; k < n; k++) {
            int c = k == 1 ? cont(pos + k, lo, hi) : cont(pos + k, 0x80, 0xBF);
            if (c < 0) {
                sb.append('\uFFFD');
                return 1;
            }
            cp = (cp << 6) | c;
        }
        sb.appendCodePoint(cp);
        return n;
    }

    private Double number() {
        int start = pos;
        while (pos < text.length) {
            int c = text[pos];
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        double d = Double.parseDouble(new String(text, start, pos - start, StandardCharsets.US_ASCII));
        if (Double.isInfinite(d)) {
            // Go keeps decoding and reports the range error once the whole value is converted
            if (!outOfRange) {
                // Go saves the first conversion error, located just past the byte after the number
                rangeOffset = pos + 1L;
            }
            outOfRange = true;
            return null;
        }
        return d;
    }

    /**
     * Serializes a value as compact JSON text exactly like Go's encoding/json Encoder with HTML
     * escaping off, decoded as UTF-8 (see {@link #encode} for the bytes).
     */
    public static String write(Object value) {
        return new String(encode(value), StandardCharsets.UTF_8);
    }

    /**
     * Serializes a value as compact JSON bytes exactly like Go's encoding/json Encoder with HTML
     * escaping off: object keys sorted by their UTF-8 bytes, numbers in Go's float format, strings
     * escaped with Go's rules, and a {@link RawMessage} written as its own bytes without the
     * whitespace outside strings. The command line prints these bytes followed by a newline.
     */
    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static final Comparator<String> UTF8_ORDER = (a, b) ->
            Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private static void ascii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    // An array or object being written: the members left to write (the sorted keys of an object)
    // and its closing byte.
    private static final class OpenOutput {
        final Map<?, ?> members;
        final Iterator<?> next;
        final char close;
        boolean first = true;

        OpenOutput(Map<?, ?> members, Iterator<?> next, char close) {
            this.members = members;
            this.next = next;
            this.close = close;
        }
    }

    // Writes v. Nested arrays and objects are kept on an explicit stack rather than on the call
    // stack, so documents of any depth are written on any thread.
    private static void write(ByteArrayOutputStream out, Object v) {
        Deque<OpenOutput> open = new ArrayDeque<>();
        while (true) {
            if (v instanceof Map<?, ?> map) {
                List<String> keys = new ArrayList<>();
                for (Object k : map.keySet()) {
                    keys.add((String) k);
                }
                keys.sort(UTF8_ORDER);
                out.write('{');
                open.push(new OpenOutput(map, keys.iterator(), '}'));
            } else if (v instanceof List<?> list) {
                out.write('[');
                open.push(new OpenOutput(null, list.iterator(), ']'));
            } else {
                writeScalar(out, v);
            }
            // Find the next value to write, closing every container that has no member left
            while (true) {
                OpenOutput container = open.peek();
                if (container == null) {
                    return;
                }
                if (container.next.hasNext()) {
                    if (!container.first) {
                        out.write(',');
                    }
                    container.first = false;
                    Object member = container.next.next();
                    if (container.members != null) {
                        quote(out, (String) member);
                        out.write(':');
                        v = container.members.get(member);
                    } else {
                        v = member;
                    }
                    break;
                }
                out.write(container.close);
                open.pop();
            }
        }
    }

    private static void writeScalar(ByteArrayOutputStream out, Object v) {
        if (v == null) {
            ascii(out, "null");
        } else if (v instanceof String s) {
            quote(out, s);
        } else if (v instanceof Boolean) {
            ascii(out, v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            ascii(out, formatDouble(((Number) v).doubleValue()));
        } else if (v instanceof Number) {
            ascii(out, v.toString());
        } else if (v instanceof RawMessage raw) {
            compact(out, raw.bytes());
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
        }
    }

    // Writes valid raw JSON without the whitespace outside strings; invalid raw JSON is refused.
    private static void compact(ByteArrayOutputStream out, byte[] raw) {
        try {
            JsonSyntax.checkValid(raw);
        } catch (DecodeException e) {
            throw new IllegalArgumentException("invalid raw JSON: " + e.getMessage(), e);
        }
        boolean inString = false;
        for (int i = 0; i < raw.length; i++) {
            int c = raw[i] & 0xff;
            if (inString) {
                out.write(c);
                if (c == '\\') {
                    out.write(raw[++i]);
                } else if (c == '"') {
                    inString = false;
                }
            } else if (!JsonSyntax.isSpace(c)) {
                out.write(c);
                inString = c == '"';
            }
        }
    }

    /**
     * Formats a finite double like Go's encoding/json: the shortest decimal that reads back as the
     * same double, in plain notation when 1e-6 &lt;= |f| &lt; 1e21 (or f is zero), else in exponent
     * notation with a signed exponent of at least two digits for positive and no zero padding for
     * negative exponents ("1e+21", "1e-7").
     */
    static String formatDouble(double f) {
        if (Double.isNaN(f) || Double.isInfinite(f)) {
            throw new IllegalArgumentException("not a JSON number: " + f);
        }
        if (f == 0) {
            return 1 / f < 0 ? "-0" : "0";
        }
        BigDecimal exact = new BigDecimal(f);
        BigDecimal shortest = exact;
        for (int digits = 1; digits <= 17; digits++) {
            BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == f) {
                shortest = candidate;
                break;
            }
        }
        shortest = shortest.stripTrailingZeros();
        double abs = Math.abs(f);
        if (abs >= 1e-6 && abs < 1e21) {
            return shortest.toPlainString();
        }
        String unscaled = shortest.unscaledValue().abs().toString();
        int exponent = unscaled.length() - 1 - shortest.scale();
        StringBuilder sb = new StringBuilder();
        if (f < 0) {
            sb.append('-');
        }
        sb.append(unscaled.charAt(0));
        if (unscaled.length() > 1) {
            sb.append('.').append(unscaled, 1, unscaled.length());
        }
        sb.append('e').append(exponent < 0 ? '-' : '+');
        int e = Math.abs(exponent);
        if (exponent > 0 && e < 10) {
            sb.append('0');
        }
        return sb.append(e).toString();
    }

    private static void quote(ByteArrayOutputStream out, String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
