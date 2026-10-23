// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pointer represents a mutable parsed JSON Pointer: the list of its unescaped reference tokens.
 * The navigation methods modify the pointer and return it for chaining.
 */
public final class Pointer {
    private final ArrayList<String> tokens;

    /** Creates the pointer made of the given (unescaped) tokens; no token is the root pointer. */
    public Pointer(String... tokens) {
        this.tokens = new ArrayList<>(Arrays.asList(tokens));
    }

    /**
     * Parses a JSON pointer from its text representation. A non-empty text that does not start
     * with '/' is a {@link SentinelError} with {@link Cause#SYNTAX}; a bad escape is a
     * {@link BadPointerError} located at the prefix of the text that ends with the bad token.
     */
    public static Pointer parse(String pointer) {
        Pointer ptr = new Pointer();
        if (pointer.isEmpty()) {
            return ptr;
        }
        if (pointer.charAt(0) != '/') {
            throw new SentinelError(Cause.SYNTAX);
        }
        int p = 1;
        while (true) {
            int q = pointer.indexOf('/', p);
            String part = pointer.substring(p, q < 0 ? pointer.length() : q);
            try {
                ptr.tokens.add(Escape.unescapeString(part));
            } catch (SentinelError e) {
                throw new BadPointerError(pointer.substring(0, p + part.length()), Cause.SYNTAX);
            }
            if (q < 0) {
                return ptr;
            }
            p = q + 1;
        }
    }

    /**
     * MustParse wraps {@link #parse} and throws an IllegalArgumentException, whose cause is the
     * parse error, in case of error.
     */
    public static Pointer mustParse(String pointer) {
        try {
            return parse(pointer);
        } catch (JsonPointerException e) {
            throw new IllegalArgumentException("\"" + pointer + "\": " + e.getMessage(), e);
        }
    }

    /**
     * UnmarshalText parses text (UTF-8) like {@link #parse} and replaces the tokens of this pointer
     * with the result; null or empty text gives the root pointer. On error this pointer is left
     * unchanged. The text is neither modified nor retained.
     */
    public void unmarshalText(byte[] text) {
        Pointer p = parse(text == null ? "" : new String(text, StandardCharsets.UTF_8));
        tokens.clear();
        tokens.addAll(p.tokens);
    }

    /** MarshalText returns the JSON Pointer string as UTF-8 bytes (an empty array for the root). */
    public byte[] marshalText() {
        return toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Grow prepares space for n more tokens (before use of {@link #property} or {@link #index}). */
    public void grow(int n) {
        if (n > 0) {
            tokens.ensureCapacity(tokens.size() + n);
        }
    }

    /** Returns a copy of the unescaped tokens. */
    public List<String> tokens() {
        return new ArrayList<>(tokens);
    }

    /**
     * Returns the JSON Pointer string, escaping components when necessary: '~' is replaced by "~0",
     * '/' by "~1".
     */
    @Override
    public String toString() {
        StringBuilder dst = new StringBuilder(8 * tokens.size());
        for (String part : tokens) {
            Escape.appendEscape(dst.append('/'), part);
        }
        return dst.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pointer o && tokens.equals(o.tokens);
    }

    @Override
    public int hashCode() {
        return tokens.hashCode();
    }

    /** Returns a new, independent, copy of the pointer. */
    public Pointer copy() {
        return new Pointer(tokens.toArray(new String[0]));
    }

    /** Returns true if the pointer is at root (empty). */
    public boolean isRoot() {
        return tokens.isEmpty();
    }

    /**
     * Up removes the last element of the pointer. The pointer is returned for chaining. At root
     * it throws a {@link SentinelError} with {@link Cause#ROOT}.
     */
    public Pointer up() {
        if (isRoot()) {
            throw new SentinelError(Cause.ROOT);
        }
        tokens.remove(tokens.size() - 1);
        return this;
    }

    /**
     * Pop removes the last element of the pointer and returns it. At root it throws a
     * {@link SentinelError} with {@link Cause#ROOT}.
     */
    public String pop() {
        if (isRoot()) {
            throw new SentinelError(Cause.ROOT);
        }
        return tokens.remove(tokens.size() - 1);
    }

    /** Property moves the pointer deeper, following a property name. Returned for chaining. */
    public Pointer property(String name) {
        tokens.add(name);
        return this;
    }

    /**
     * Index moves the pointer deeper, following an array index; a negative index gives "-".
     * Returned for chaining.
     */
    public Pointer index(long index) {
        return property(index < 0 ? "-" : Long.toString(index));
    }

    /**
     * Returns the name of the last part of the pointer. At root it throws a {@link SentinelError}
     * with {@link Cause#ROOT}.
     */
    public String leafName() {
        if (isRoot()) {
            throw new SentinelError(Cause.ROOT);
        }
        return tokens.get(tokens.size() - 1);
    }

    /**
     * Returns the last part of the pointer as an array index; -1 is returned for "-". A token that
     * is not an index is a {@link SentinelError} with {@link Cause#SYNTAX}.
     */
    public long leafIndex() {
        return JsonPtr.arrayIndex(leafName());
    }

    private String prefix(int n) {
        return new Pointer(tokens.subList(0, n).toArray(new String[0])).toString();
    }

    private String suffix(int n) {
        return new Pointer(tokens.subList(n, tokens.size()).toArray(new String[0])).toString();
    }

    /**
     * In returns the value from doc pointed by this pointer. doc may be any document accepted by
     * {@link JsonPtr#get}. A missing property is a {@link PtrError} with {@link Cause#PROPERTY},
     * any token that does not select an element of an array a {@link PtrError} with
     * {@link Cause#INDEX} (both located at the prefix ending with that token), and a value that
     * can't be traversed a {@link DocumentError} located at that value.
     */
    public Object in(Object doc) {
        for (int i = 0; i < tokens.size(); i++) {
            String key = tokens.get(i);
            if (doc instanceof Map<?, ?> here) {
                if (!here.containsKey(key)) {
                    throw new PtrError(prefix(i + 1), Cause.PROPERTY);
                }
                doc = here.get(key);
            } else if (doc instanceof List<?> here) {
                long n;
                try {
                    n = JsonPtr.arrayIndex(key);
                } catch (SentinelError e) {
                    n = -1;
                }
                if (n < 0 || n >= here.size()) {
                    throw new PtrError(prefix(i + 1), Cause.INDEX);
                }
                doc = here.get((int) n);
            } else if (doc instanceof JsonDecoder here) {
                try {
                    return JsonPtr.getJSON(here, suffix(i));
                } catch (JsonPointerException e) {
                    throw JsonPtr.rebased(e, prefix(i));
                }
            } else if (doc instanceof RawMessage here) {
                try {
                    return JsonPtr.getRaw(here, suffix(i));
                } catch (JsonPointerException e) {
                    throw JsonPtr.rebased(e, prefix(i));
                }
            } else {
                // We report the error at the upper level: the location of the value that can't be traversed
                throw new DocumentError(prefix(i), Cause.NOT_CONTAINER);
            }
        }
        try {
            return JsonPtr.getLeaf(doc);
        } catch (JsonPointerException e) {
            throw JsonPtr.rebased(e, toString());
        }
    }

    /** Set changes a value in the document referenced by doc at the location pointed by this pointer. */
    public void set(JsonPtr.Ref doc, Object value) {
        JsonPtr.set(doc, toString(), value);
    }

    /**
     * Delete removes an object property or an array element (and shifts remaining ones) in the
     * document referenced by doc, and returns it. It can't be applied on root.
     */
    public Object delete(JsonPtr.Ref doc) {
        return JsonPtr.delete(doc, toString());
    }
}
