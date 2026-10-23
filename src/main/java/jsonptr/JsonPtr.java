// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Pointer (RFC 6901) lookup and modification.
 *
 * <p>Any part of a data tree made of {@link List} and {@link Map} (with String keys) may be
 * dereferenced with a JSON Pointer; any other value is a leaf. A tree may also hold raw JSON
 * ({@link RawMessage}), partially decoded containers ({@link RawObject}, {@link RawArray}) and
 * streamed JSON ({@link JsonDecoder}), mixed at any level.
 */
public final class JsonPtr {
    private JsonPtr() {
    }

    /** A mutable reference to a document (Go's *interface{}): Set and Delete may replace it. */
    public static final class Ref {
        private Object value;

        /** Creates a reference to document. */
        public Ref(Object document) {
            this.value = document;
        }

        /** Returns the referenced document. */
        public Object get() {
            return value;
        }

        /** Replaces the referenced document. */
        public void set(Object document) {
            this.value = document;
        }
    }

    /**
     * Parses an array index token: ASCII decimal digits without leading zero, in the signed 64-bit
     * range. "-" gives -1. Anything else is a {@link SentinelError} with {@link Cause#SYNTAX}.
     */
    static long arrayIndex(String token) {
        if (token.isEmpty()) {
            throw new SentinelError(Cause.SYNTAX);
        }
        if (token.length() == 1) {
            if (token.charAt(0) == '0') {
                return 0;
            }
            if (token.charAt(0) == '-') {
                return -1;
            }
        }
        if (token.charAt(0) < '1') {
            throw new SentinelError(Cause.SYNTAX);
        }
        final long cutoff = Long.MAX_VALUE / 10 + 1;
        long n = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                throw new SentinelError(Cause.SYNTAX);
            }
            if (n >= cutoff) {
                // Overflow
                throw new SentinelError(Cause.SYNTAX);
            }
            n *= 10;
            long n1 = n + (c - '0');
            if (n1 < n) {
                // Overflow
                throw new SentinelError(Cause.SYNTAX);
            }
            n = n1;
        }
        return n;
    }

    /**
     * Reports a token which is not a valid array index: a BadPointerError if the token is not even
     * a valid pointer token (bad escape), else a PtrError wrapping {@link Cause#INDEX}.
     */
    static JsonPointerException badIndexError(String ptr, String token) {
        try {
            Escape.unescapeString(token);
        } catch (SentinelError e) {
            return new BadPointerError(ptr, e.cause());
        }
        return new PtrError(ptr, Cause.INDEX);
    }

    static String unescapeKey(String ptr, String token) {
        try {
            return Escape.unescapeString(token);
        } catch (SentinelError e) {
            throw new BadPointerError(ptr, e.cause());
        }
    }

    // Reports a JSON decoding error: a syntax error is not located, any other error is at ptr.
    static DocumentError jsonError(String ptr, Json.DecodeException e) {
        boolean syntax = e.reason().equals("syntax") || e.reason().equals("depth");
        return new DocumentError(syntax ? "" : ptr, e);
    }

    static <T extends JsonPointerException> T rebased(T e, String base) {
        e.rebase(base);
        return e;
    }

    // Walks a streamed document with its tokens, skipping the values off the path.
    static Object getJSON(JsonDecoder decoder, String ptr) {
        int p = 1;
        String cur = ptr.substring(1);
        while (true) {
            int q = cur.indexOf('/');
            if (q != -1) {
                cur = cur.substring(0, q);
            } else {
                q = cur.length();
            }
            p += q;

            Object tok;
            try {
                tok = decoder.token();
            } catch (Json.DecodeException e) {
                throw jsonError(ptr.substring(0, p), e);
            }
            if (!(tok instanceof Character delim)) {
                throw new DocumentError(ptr.substring(0, p - q - 1), Cause.NOT_CONTAINER);
            }
            if (delim == '{') {
                String key = unescapeKey(ptr.substring(0, p), cur);
                boolean found = false;
                // more() is false once the closing '}' is reached
                while (decoder.more()) {
                    Object k;
                    try {
                        k = decoder.token();
                    } catch (Json.DecodeException e) {
                        throw jsonError(ptr.substring(0, p), e);
                    }
                    if (!(k instanceof String)) {
                        // This should not happen
                        throw new IllegalStateException("unexpected key type");
                    }
                    if (!decoder.more()) {
                        throw new IllegalStateException("unexpected missing value in object");
                    }
                    if (k.equals(key)) {
                        found = true;
                        break;
                    }
                    // skip value
                    try {
                        decoder.decodeRaw();
                    } catch (Json.DecodeException e) {
                        throw jsonError(ptr.substring(0, p), e);
                    }
                }
                if (!found) {
                    throw new PtrError(ptr.substring(0, p), Cause.PROPERTY);
                }
            } else if (delim == '[') {
                long n;
                try {
                    n = arrayIndex(cur);
                } catch (SentinelError e) {
                    throw badIndexError(ptr.substring(0, p), cur);
                }
                if (n < 0) {
                    throw new PtrError(ptr.substring(0, p), Cause.INDEX);
                }
                long i = -1;
                while (decoder.more()) {
                    i++;
                    if (i == n) {
                        // Continue deeper in the structure
                        break;
                    }
                    try {
                        decoder.decodeRaw();
                    } catch (Json.DecodeException e) {
                        throw jsonError(ptr.substring(0, p), e);
                    }
                }
                if (i < n) {
                    throw new PtrError(ptr.substring(0, p), Cause.INDEX);
                }
            }

            p++;
            if (p > ptr.length()) {
                break;
            }
            cur = ptr.substring(p);
        }

        try {
            return decoder.decode();
        } catch (Json.DecodeException e) {
            throw jsonError(ptr, e);
        }
    }

    static Object getRaw(RawMessage doc, String ptr) {
        return getJSON(Json.newDecoder(doc.bytes()), ptr);
    }

    // Decodes a raw or streamed leaf; any other value is returned as is.
    static Object getLeaf(Object doc) {
        try {
            if (doc instanceof RawMessage raw) {
                return Json.parse(raw.bytes());
            }
            if (doc instanceof JsonDecoder decoder) {
                return decoder.decode();
            }
        } catch (Json.DecodeException e) {
            throw jsonError("", e);
        }
        return doc;
    }

    /**
     * Get extracts a value from a JSON-like data tree.
     *
     * <p>document may be a decoded document made of {@link List}, {@link Map} or any terminal
     * value, a {@link RawMessage}, a {@link JsonDecoder} for streamed decoding, or a partially
     * decoded document ({@link RawObject}, {@link RawArray}); those containers may be mixed at any
     * level of the tree. In case of error a {@link BadPointerError}, a {@link PtrError} or a
     * {@link DocumentError} is thrown.
     */
    public static Object get(Object document, String pointer) {
        if (pointer.isEmpty()) {
            return getLeaf(document);
        }
        if (pointer.charAt(0) != '/') {
            throw new BadPointerError(pointer, Cause.SYNTAX);
        }
        Object doc = document;
        String cur = pointer.substring(1);
        int p = 1;
        while (true) {
            int q = cur.indexOf('/');
            if (q == -1) {
                q = cur.length();
            }
            p += q;
            String token = cur.substring(0, q);
            if (doc instanceof Map<?, ?> here) {
                String key = unescapeKey(pointer.substring(0, p), token);
                if (!here.containsKey(key)) {
                    throw new PtrError(pointer.substring(0, p), Cause.PROPERTY);
                }
                doc = here.get(key);
            } else if (doc instanceof List<?> here) {
                long n;
                try {
                    n = arrayIndex(token);
                } catch (SentinelError e) {
                    throw badIndexError(pointer.substring(0, p), token);
                }
                if (n < 0 || n >= here.size()) {
                    throw new PtrError(pointer.substring(0, p), Cause.INDEX);
                }
                doc = here.get((int) n);
            } else if (doc instanceof JsonDecoder here) {
                try {
                    return getJSON(here, pointer.substring(p - q - 1));
                } catch (JsonPointerException e) {
                    throw rebased(e, pointer.substring(0, p - q - 1));
                }
            } else if (doc instanceof RawMessage here) {
                try {
                    return getRaw(here, pointer.substring(p - q - 1));
                } catch (JsonPointerException e) {
                    throw rebased(e, pointer.substring(0, p - q - 1));
                }
            } else {
                // Report the location of the value which can't be traversed
                throw new DocumentError(pointer.substring(0, p - q - 1), Cause.NOT_CONTAINER);
            }
            if (p >= pointer.length()) {
                break;
            }
            p++;
            cur = pointer.substring(p);
        }
        try {
            return getLeaf(doc);
        } catch (JsonPointerException e) {
            throw rebased(e, pointer);
        }
    }

    /**
     * Set stores value at the location pointed by pointer in the document referenced by doc.
     *
     * <p>The document may be any document accepted by {@link #get}. The root of the document
     * (pointer "") may be replaced. Otherwise the parent of the location must exist: only the leaf
     * is created if it doesn't exist. In an array, index "-" appends the value, and an index beyond
     * the end extends the array with nulls.
     *
     * <p>value is stored as is, except a {@link JsonDecoder} which is drained of its next value into
     * a {@link RawMessage}.
     *
     * <p>Every container on the path to the value is rewritten in the tree as a Map or a List (a
     * {@link RawObject} or {@link RawArray} on the path is converted, its members are kept raw). A
     * {@link RawMessage} or {@link JsonDecoder} on the path is decoded lazily, one layer each, so
     * the values outside the path are kept raw.
     *
     * <p>In case of error the document is left unchanged, except that a {@link JsonDecoder} on the
     * path is replaced by the raw value read from it (a JsonDecoder given as value may also have
     * been read).
     */
    public static void set(Ref doc, String pointer, Object value) {
        if (value instanceof JsonDecoder dec) {
            try {
                value = dec.decodeRaw();
            } catch (Json.DecodeException e) {
                throw new DocumentError("", e);
            }
        }
        if (!pointer.isEmpty() && pointer.charAt(0) != '/') {
            throw new BadPointerError(pointer, Cause.SYNTAX);
        }
        setIn(doc, pointer, value);
    }

    // Replaces a JsonDecoder held by doc with the raw value it reads.
    private static void drain(Ref doc) {
        if (doc.get() instanceof JsonDecoder decoder) {
            try {
                doc.set(decoder.decodeRaw());
            } catch (Json.DecodeException e) {
                throw jsonError("", e);
            }
        }
    }

    // The container held by doc, a raw value being decoded one layer.
    private static Object layer(Ref doc) {
        if (doc.get() instanceof RawMessage raw) {
            try {
                return Json.decodeLayer(raw.bytes());
            } catch (Json.DecodeException e) {
                throw jsonError("", e);
            }
        }
        return doc.get();
    }

    // One container on the path of set or delete: the reference holding it, the container decoded
    // one layer, the member followed (key for an object, index n for an array), the location of
    // that member relative to the reference, and the reference to the member.
    private static final class Step {
        final Ref doc;
        final Object parent;
        final String key;
        final long n;
        final String curPtr;
        final Ref child;

        Step(Ref doc, Object parent, String key, long n, String curPtr, Ref child) {
            this.doc = doc;
            this.parent = parent;
            this.key = key;
            this.n = n;
            this.curPtr = curPtr;
            this.child = child;
        }
    }

    // The container held by doc, after draining a JsonDecoder and decoding one raw layer.
    private static Object container(Ref doc) {
        drain(doc);
        return layer(doc);
    }

    // The end of the first token of ptr, a non-empty pointer.
    private static int tokenEnd(String ptr) {
        int end = ptr.indexOf('/', 1);
        return end < 0 ? ptr.length() : end;
    }

    // The index named by token in an array, -1 for "-".
    private static long index(String curPtr, String prop) {
        try {
            return arrayIndex(prop);
        } catch (SentinelError e) {
            throw badIndexError(curPtr, prop);
        }
    }

    // A failed set or delete: each decoded container on the path, innermost first, keeps the raw
    // bytes of a JsonDecoder child that has been read, and the error is rebased on the location of
    // the member followed.
    @SuppressWarnings("unchecked")
    private static JsonPointerException failed(Deque<Step> path, JsonPointerException e) {
        while (!path.isEmpty()) {
            Step step = path.pop();
            if (step.child.get() instanceof RawMessage raw
                    && !(step.parent instanceof RawObject) && !(step.parent instanceof RawArray)) {
                // A JsonDecoder child has been read: keep its raw bytes in the tree
                if (step.parent instanceof Map<?, ?> map) {
                    ((Map<String, Object>) map).put(step.key, raw);
                } else {
                    ((List<Object>) step.parent).set((int) step.n, raw);
                }
            }
            rebased(e, step.curPtr);
        }
        return e;
    }

    // setIn follows ptr into doc one token at a time, keeping the containers on the path, sets the
    // value, then stores each child back into its container, innermost first. Errors are located
    // relatively to doc: the caller rebases them. The path is kept on an explicit stack rather than
    // on the call stack, so pointers of any length work on any thread.
    @SuppressWarnings("unchecked")
    private static void setIn(Ref doc, String ptr, Object value) {
        Deque<Step> path = new ArrayDeque<>();
        Ref cur = doc;
        String rest = ptr;
        try {
            while (!rest.isEmpty()) {
                Object parent = container(cur);
                // Split rest as "/" + prop + nextPtr
                int end = tokenEnd(rest);
                String prop = rest.substring(1, end); // first token, the child of cur to follow
                String curPtr = rest.substring(0, end); // "/" + prop: location of that child, relative to cur
                String nextPtr = rest.substring(end); // rest of the path, relative to the child

                Ref child;
                String key = null;
                long n = 0;
                if (parent instanceof Map<?, ?> map) {
                    key = unescapeKey(curPtr, prop);
                    if (!map.containsKey(key) && !nextPtr.isEmpty()) {
                        // Only the leaf may be created
                        throw new PtrError(curPtr, Cause.PROPERTY);
                    }
                    child = new Ref(map.get(key));
                } else if (parent instanceof List<?> list) {
                    n = index(curPtr, prop);
                    child = new Ref(null);
                    if (n >= 0 && n < list.size()) {
                        child.set(list.get((int) n));
                    } else if (!nextPtr.isEmpty()) {
                        // Only the leaf may be created
                        throw new PtrError(curPtr, Cause.INDEX);
                    }
                } else {
                    // Report the location of parent itself: the caller rebases it
                    throw new DocumentError("", Cause.NOT_CONTAINER);
                }
                path.push(new Step(cur, parent, key, n, curPtr, child));
                cur = child;
                rest = nextPtr;
            }
            cur.set(value);
        } catch (JsonPointerException e) {
            throw failed(path, e);
        }
        while (!path.isEmpty()) {
            Step step = path.pop();
            Object child = step.child.get();
            if (step.parent instanceof RawObject members) {
                // Convert to a Map to store the decoded child
                Map<String, Object> m = new LinkedHashMap<>(members);
                m.put(step.key, child);
                step.doc.set(m);
            } else if (step.parent instanceof Map<?, ?> map) {
                ((Map<String, Object>) map).put(step.key, child);
                step.doc.set(map);
            } else if (step.parent instanceof RawArray elements) {
                long n = step.n == -1 ? elements.size() : step.n;
                // Convert to a List, padded with nulls up to n if necessary
                List<Object> arr = new ArrayList<>(elements);
                while (arr.size() <= n) {
                    arr.add(null);
                }
                arr.set((int) n, child);
                step.doc.set(arr);
            } else {
                List<Object> elements = (List<Object>) step.parent;
                long n = step.n == -1 ? elements.size() : step.n;
                // Pad with nulls up to n
                while (elements.size() <= n) {
                    elements.add(null);
                }
                step.doc.set(elements);
                elements.set((int) n, child);
            }
        }
    }

    /**
     * Delete removes the value at the location pointed by pointer in the document referenced by
     * doc, and returns it. An array element is removed by shifting the remaining ones.
     *
     * <p>The document may be any document accepted by {@link #get}. The root of the document can't
     * be deleted ({@link Cause#DELETE_ROOT}). The value must exist ({@link Cause#PROPERTY},
     * {@link Cause#INDEX}); index "-" is not accepted.
     *
     * <p>The returned value is the one stored in the tree, so it is a {@link RawMessage} when the
     * container of the value is a {@link RawObject} or a {@link RawArray} (including when it results
     * from lazy decoding). Every container on the path to the value, except the container of the
     * value itself, is rewritten in the tree as a Map or a List; a {@link RawMessage} or
     * {@link JsonDecoder} on the path is decoded lazily, one layer each.
     *
     * <p>In case of error the document is left unchanged, except that a {@link JsonDecoder} on the
     * path is replaced by the raw value read from it.
     */
    public static Object delete(Ref doc, String pointer) {
        if (pointer.isEmpty()) {
            throw new BadPointerError(pointer, Cause.DELETE_ROOT);
        }
        if (pointer.charAt(0) != '/') {
            throw new BadPointerError(pointer, Cause.SYNTAX);
        }
        return deleteIn(doc, pointer);
    }

    // deleteIn follows ptr into doc one token at a time, keeping the containers on the path, removes
    // the value from its container, then stores each child back into its container, innermost
    // first. Errors are located relatively to doc: the caller rebases them. The path is kept on an
    // explicit stack rather than on the call stack, so pointers of any length work on any thread.
    @SuppressWarnings("unchecked")
    private static Object deleteIn(Ref doc, String ptr) {
        Deque<Step> path = new ArrayDeque<>();
        Ref cur = doc;
        String rest = ptr;
        Object removed;
        try {
            while (true) {
                Object parent = container(cur);
                int end = tokenEnd(rest);
                String prop = rest.substring(1, end);
                String curPtr = rest.substring(0, end);
                String nextPtr = rest.substring(end);

                Ref child;
                String key = null;
                long n = 0;
                if (parent instanceof Map<?, ?> map) {
                    key = unescapeKey(curPtr, prop);
                    if (!map.containsKey(key)) {
                        throw new PtrError(curPtr, Cause.PROPERTY);
                    }
                    if (nextPtr.isEmpty()) {
                        removed = map.remove(key);
                        if (parent instanceof RawObject) {
                            cur.set(parent);
                        }
                        break;
                    }
                    child = new Ref(map.get(key));
                } else if (parent instanceof List<?> list) {
                    n = index(curPtr, prop);
                    if (n < 0 || n >= list.size()) {
                        throw new PtrError(curPtr, Cause.INDEX);
                    }
                    if (nextPtr.isEmpty()) {
                        removed = list.remove((int) n);
                        cur.set(list);
                        break;
                    }
                    child = new Ref(list.get((int) n));
                } else {
                    // Report the location of parent itself: the caller rebases it
                    throw new DocumentError("", Cause.NOT_CONTAINER);
                }
                path.push(new Step(cur, parent, key, n, curPtr, child));
                cur = child;
                rest = nextPtr;
            }
        } catch (JsonPointerException e) {
            throw failed(path, e);
        }
        while (!path.isEmpty()) {
            Step step = path.pop();
            Object child = step.child.get();
            if (step.parent instanceof RawObject members) {
                // Convert to a Map to store the decoded child
                Map<String, Object> m = new LinkedHashMap<>(members);
                m.put(step.key, child);
                step.doc.set(m);
            } else if (step.parent instanceof Map<?, ?> map) {
                ((Map<String, Object>) map).put(step.key, child);
            } else if (step.parent instanceof RawArray elements) {
                // Convert to a List to store the decoded child
                List<Object> arr = new ArrayList<>(elements);
                arr.set((int) step.n, child);
                step.doc.set(arr);
            } else {
                ((List<Object>) step.parent).set((int) step.n, child);
            }
        }
        return removed;
    }
}
