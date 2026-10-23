// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Escaping and unescaping of JSON Pointer reference tokens. */
public final class Escape {
    private Escape() {
    }

    /**
     * Escapes a property name with JSON Pointer escapes: '~' becomes "~0" and '/' becomes "~1".
     */
    public static String escapeString(String name) {
        if (name.indexOf('~') < 0 && name.indexOf('/') < 0) {
            return name;
        }
        StringBuilder b = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '~') {
                b.append("~0");
            } else if (c == '/') {
                b.append("~1");
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Appends the escaped name to dst and returns dst. */
    public static StringBuilder appendEscape(StringBuilder dst, String name) {
        int start = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '~' || c == '/') {
                dst.append(name, start, i).append('~').append(c == '~' ? '0' : '1');
                start = i + 1;
            }
        }
        return dst.append(name, start, name.length());
    }

    /**
     * Appends the UTF-8 bytes of the escaped name to dst and returns the result: dst itself when
     * name is empty, else a new array.
     */
    public static byte[] appendEscape(byte[] dst, String name) {
        if (name.isEmpty()) {
            return dst;
        }
        byte[] escaped = escapeString(name).getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(dst, dst.length + escaped.length);
        System.arraycopy(escaped, 0, result, dst.length, escaped.length);
        return result;
    }

    /**
     * Unescapes a property name in place: "~1" becomes '/' and "~0" becomes '~'. The unescaped name
     * is written over the start of b and returned as a copy of that prefix.
     *
     * <p>Any '~' followed by something else (or nothing) is a {@link SentinelError} with
     * {@link Cause#SYNTAX}, any '/' a {@link SentinelError} with {@link Cause#USAGE}, in the same
     * order as {@link #unescapeString}.
     */
    public static byte[] unescape(byte[] b) {
        int p = -1;
        for (int q = 0; q < b.length; q++) {
            if (b[q] == '~') {
                p = q;
                break;
            }
            if (b[q] == '/') {
                throw new SentinelError(Cause.USAGE);
            }
        }
        // Nothing to replace
        if (p == -1) {
            return b.clone();
        }
        if (b[b.length - 1] == '~') {
            throw new SentinelError(Cause.SYNTAX);
        }
        for (int q = p; q < b.length; q++) {
            if (b[q] == '~') {
                q++;
                if (b[q] == '0') {
                    b[p] = '~';
                } else if (b[q] == '1') {
                    b[p] = '/';
                } else {
                    throw new SentinelError(Cause.SYNTAX);
                }
            } else if (b[q] == '/') {
                throw new SentinelError(Cause.USAGE);
            } else {
                // Move byte
                b[p] = b[q];
            }
            p++;
        }
        return Arrays.copyOf(b, p);
    }

    /**
     * Unescapes a property name: "~1" becomes '/' and "~0" becomes '~'.
     *
     * <p>Any '~' followed by something else (or nothing) is a {@link SentinelError} with
     * {@link Cause#SYNTAX}. A '/' is a {@link SentinelError} with {@link Cause#USAGE}, except that a
     * token whose last character is '~' is a syntax error as soon as a '~' comes before any '/'.
     */
    public static String unescapeString(String token) {
        int p = -1;
        for (int q = 0; q < token.length(); q++) {
            char c = token.charAt(q);
            if (c == '~') {
                p = q;
                break;
            }
            if (c == '/') {
                throw new SentinelError(Cause.USAGE);
            }
        }
        // Nothing to replace
        if (p == -1) {
            return token;
        }
        if (token.charAt(token.length() - 1) == '~') {
            throw new SentinelError(Cause.SYNTAX);
        }
        StringBuilder b = new StringBuilder(token.length());
        b.append(token, 0, p);
        for (int q = p; q < token.length(); q++) {
            char c = token.charAt(q);
            if (c == '~') {
                q++;
                char e = token.charAt(q);
                if (e == '0') {
                    b.append('~');
                } else if (e == '1') {
                    b.append('/');
                } else {
                    throw new SentinelError(Cause.SYNTAX);
                }
            } else if (c == '/') {
                throw new SentinelError(Cause.USAGE);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
