// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/**
 * Syntax and nesting-depth checks for JSON text, with the error positions of Go's encoding/json: an
 * error is located at the first byte that cannot continue a valid beginning of a JSON value,
 * counting from 1, and a text that ends early is located at its length.
 */
final class JsonSyntax {
    /** Returned by {@link #valueEnd} when the bytes seen so far are a valid but unfinished value. */
    static final int INCOMPLETE = -1;

    // What the checker expects next, outside strings, numbers and literals.
    private static final int VALUE = 0;
    private static final int VALUE_OR_CLOSE = 1;
    private static final int KEY = 2;
    private static final int KEY_OR_CLOSE = 3;
    private static final int COLON = 4;
    private static final int SEPARATOR = 5;

    private JsonSyntax() {
    }

    static boolean isSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** Verifies that data is exactly one JSON value with optional surrounding space. */
    static void checkValid(byte[] data) {
        int end = valueEnd(data, 0, data.length, true, 0);
        if (end == INCOMPLETE) {
            throw new Json.DecodeException("syntax", data.length);
        }
        for (int i = end; i < data.length; i++) {
            if (!isSpace(data[i] & 0xff)) {
                throw new Json.DecodeException("syntax", i + 1);
            }
        }
    }

    /**
     * Finds the end of the JSON value that starts in buf at from, after optional space, reading no
     * further than limit. Returns the index just after the value, or {@link #INCOMPLETE} when the
     * value is not finished at limit; a number at the top level is finished only by the byte after
     * it or, when atEnd says no more bytes follow limit, by limit itself. A syntax or depth error
     * throws a {@link Json.DecodeException} located at base plus the 1-based position of the
     * offending byte counted from from.
     */
    static int valueEnd(byte[] buf, int from, int limit, boolean atEnd, long base) {
        byte[] open = new byte[64];
        int depth = 0;
        int expect = VALUE;
        int i = from;
        while (i < limit) {
            int c = buf[i] & 0xff;
            if (isSpace(c)) {
                i++;
                continue;
            }
            if ((expect == VALUE_OR_CLOSE && c == ']') || (expect == KEY_OR_CLOSE && c == '}')
                    || (expect == SEPARATOR && c == (open[depth - 1] == '{' ? '}' : ']'))) {
                i++;
                depth--;
                if (depth == 0) {
                    return i;
                }
                expect = SEPARATOR;
                continue;
            }
            switch (expect) {
                case SEPARATOR:
                    if (c != ',') {
                        throw error("syntax", i, from, base);
                    }
                    i++;
                    expect = open[depth - 1] == '{' ? KEY : VALUE;
                    continue;
                case COLON:
                    if (c != ':') {
                        throw error("syntax", i, from, base);
                    }
                    i++;
                    expect = VALUE;
                    continue;
                case KEY:
                case KEY_OR_CLOSE:
                    if (c != '"') {
                        throw error("syntax", i, from, base);
                    }
                    i = stringEnd(buf, i, limit, from, base);
                    if (i == INCOMPLETE) {
                        return INCOMPLETE;
                    }
                    expect = COLON;
                    continue;
                default:
                    break;
            }
            if (c == '{' || c == '[') {
                if (depth == Json.MAX_DEPTH) {
                    throw error("depth", i, from, base);
                }
                if (depth == open.length) {
                    open = java.util.Arrays.copyOf(open, depth * 2);
                }
                open[depth++] = (byte) c;
                expect = c == '{' ? KEY_OR_CLOSE : VALUE_OR_CLOSE;
                i++;
                continue;
            }
            if (c == '"') {
                i = stringEnd(buf, i, limit, from, base);
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                i = numberEnd(buf, i, limit, atEnd && depth == 0, from, base);
            } else if (c == 't') {
                i = wordEnd(buf, i, limit, "true", from, base);
            } else if (c == 'f') {
                i = wordEnd(buf, i, limit, "false", from, base);
            } else if (c == 'n') {
                i = wordEnd(buf, i, limit, "null", from, base);
            } else {
                throw error("syntax", i, from, base);
            }
            if (i == INCOMPLETE) {
                return INCOMPLETE;
            }
            if (depth == 0) {
                return i;
            }
            expect = SEPARATOR;
        }
        return INCOMPLETE;
    }

    // buf[start] is the opening quote; returns the index after the closing quote.
    private static int stringEnd(byte[] buf, int start, int limit, int from, long base) {
        int i = start + 1;
        while (i < limit) {
            int c = buf[i] & 0xff;
            if (c == '"') {
                return i + 1;
            }
            if (c < 0x20) {
                throw error("syntax", i, from, base);
            }
            if (c != '\\') {
                i++;
                continue;
            }
            if (++i == limit) {
                return INCOMPLETE;
            }
            int e = buf[i] & 0xff;
            if (e == 'u') {
                for (int k = 0; k < 4; k++) {
                    if (++i == limit) {
                        return INCOMPLETE;
                    }
                    if (Character.digit(buf[i] & 0xff, 16) < 0) {
                        throw error("syntax", i, from, base);
                    }
                }
            } else if ("\"\\/bfnrt".indexOf(e) < 0) {
                throw error("syntax", i, from, base);
            }
            i++;
        }
        return INCOMPLETE;
    }

    /*
     * buf[start] is '-' or a digit. Returns the index of the first byte after the number. A number
     * may stop after its integer, fraction or exponent digits; anything else that does not continue
     * it is an error. At limit the number is unfinished unless finishedAtLimit.
     */
    private static int numberEnd(byte[] buf, int start, int limit, boolean finishedAtLimit, int from, long base) {
        int i = start;
        if (buf[i] == '-') {
            i++;
            if (i == limit) {
                return INCOMPLETE;
            }
            if (!isDigit(buf[i])) {
                throw error("syntax", i, from, base);
            }
        }
        if (buf[i] == '0') {
            i++;
        } else {
            i = skipDigits(buf, i, limit);
        }
        if (i < limit && buf[i] == '.') {
            i = requireDigits(buf, i + 1, limit, from, base);
            if (i == INCOMPLETE) {
                return INCOMPLETE;
            }
        }
        if (i < limit && (buf[i] == 'e' || buf[i] == 'E')) {
            i++;
            if (i < limit && (buf[i] == '+' || buf[i] == '-')) {
                i++;
            }
            i = requireDigits(buf, i, limit, from, base);
            if (i == INCOMPLETE) {
                return INCOMPLETE;
            }
        }
        return i < limit || finishedAtLimit ? i : INCOMPLETE;
    }

    // At least one digit at i, then any more; returns the index after them.
    private static int requireDigits(byte[] buf, int i, int limit, int from, long base) {
        if (i == limit) {
            return INCOMPLETE;
        }
        if (!isDigit(buf[i])) {
            throw error("syntax", i, from, base);
        }
        return skipDigits(buf, i, limit);
    }

    private static int skipDigits(byte[] buf, int i, int limit) {
        while (i < limit && isDigit(buf[i])) {
            i++;
        }
        return i;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private static int wordEnd(byte[] buf, int start, int limit, String word, int from, long base) {
        for (int k = 1; k < word.length(); k++) {
            int i = start + k;
            if (i == limit) {
                return INCOMPLETE;
            }
            if (buf[i] != word.charAt(k)) {
                throw error("syntax", i, from, base);
            }
        }
        return start + word.length();
    }

    private static Json.DecodeException error(String reason, int at, int from, long base) {
        return new Json.DecodeException(reason, base + (at - from) + 1);
    }
}
