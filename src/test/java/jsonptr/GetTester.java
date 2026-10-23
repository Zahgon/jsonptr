// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** The shared table of TestGet and TestPointerIn (the Go getTester). */
final class GetTester {
    private final BiFunction<Object, String, Object> get;

    GetTester(BiFunction<Object, String, Object> get) {
        this.get = get;
    }

    static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static RawMessage raw(String json) {
        return new RawMessage(json);
    }

    static RawObject rawObject(Object... kv) {
        RawObject m = new RawObject();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], raw((String) kv[i + 1]));
        }
        return m;
    }

    static RawArray rawArray(String... items) {
        RawArray a = new RawArray();
        for (String item : items) {
            a.add(raw(item));
        }
        return a;
    }

    /**
     * Returns the same JSON document in all the forms accepted by get: decoded, raw, streamed, and
     * partially decoded (RawObject or RawArray) when the document is an object or an array. The
     * partially decoded forms are omitted for the root pointer, as get returns them as is.
     */
    static List<Object> docForms(String json, String ptr) {
        Object data = Json.parse(json);
        List<Object> docs = list(data, raw(json), Json.newDecoder(json));
        if (ptr.isEmpty()) {
            return docs;
        }
        if (data instanceof Map<?, ?> m) {
            RawObject partial = new RawObject();
            m.forEach((k, v) -> partial.put((String) k, raw(Json.write(v))));
            docs.add(partial);
        } else if (data instanceof List<?> l) {
            RawArray partial = new RawArray();
            l.forEach(v -> partial.add(raw(Json.write(v))));
            docs.add(partial);
        }
        return docs;
    }

    private static String form(Object doc) {
        return doc == null ? "null" : doc.getClass().getSimpleName();
    }

    private void checkGet(String json, String ptr, Object expected) {
        for (Object doc : docForms(json, ptr)) {
            Object got = get.apply(doc, ptr);
            Deep.assertDeepEquals(expected, got, form(doc) + ": " + json + " => \"" + ptr + "\"");
        }
    }

    /**
     * Checks that get fails with an error wrapping expected located at expectedPtr, for the same
     * document in all its forms: a BadPointerError for SYNTAX, a DocumentError for null, a PtrError
     * otherwise.
     */
    private void checkGetError(String json, String ptr, Cause expected, String expectedPtr) {
        for (Object doc : docForms(json, ptr)) {
            String what = form(doc) + ": " + json + " => \"" + ptr + "\" (error expected)";
            JsonPointerException err = assertThrows(JsonPointerException.class, () -> get.apply(doc, ptr), what);
            if (expected == null) {
                assertTrue(err instanceof DocumentError, what + ": got " + err.kind());
            } else if (expected == Cause.SYNTAX) {
                assertTrue(err instanceof BadPointerError, what + ": got " + err.kind());
                assertEquals(expected, err.cause(), what);
            } else {
                assertTrue(err instanceof PtrError, what + ": got " + err.kind());
                assertEquals(expected, err.cause(), what);
            }
            assertEquals(expectedPtr, err.pointer(), what);
        }
    }

    void runTest() {
        for (Object doc : list("x", 1, 1.0, true, false, null, list(), map())) {
            Deep.assertDeepEquals(doc, get.apply(doc, ""), form(doc));
        }

        checkGet("\"x\"", "", "x");
        checkGet("[\"x\"]", "", list("x"));
        checkGet("[\"a\",\"b\"]", "/0", "a");
        checkGet("[\"a\",\"b\"]", "/1", "b");
        checkGet("{\"a\":\"x\"}", "/a", "x");
        checkGet("{\"\":\"x\"}", "/", "x");
        checkGet("{\"\":{\"\":{\"\":true}}}", "///", true);
        checkGet("{\"~\":\"x\"}", "/~0", "x");
        checkGet("{\"/\":\"y\"}", "/~1", "y");
        checkGet("{\"~/\":\"z\"}", "/~0~1", "z");
        checkGet("{\"/~\":\"z\"}", "/~1~0", "z");
        checkGet("{\"~~~\":\"x\"}", "/~0~0~0", "x");
        checkGet("{\"~x~\":\"x\"}", "/~0x~0", "x");
        checkGet("{\"/~~/\":\"z\"}", "/~1~0~0~1", "z");
        checkGet("{\"1éé\":\"z\"}", "/1éé", "z");
        checkGet("{\"a\":{}}", "/a", map());
        checkGet("{\"a\":[]}", "/a", list());
        checkGet("{\"a\":[1,2]}", "/a/0", 1.0);
        checkGet("{\"a\":[1,2]}", "/a/1", 2.0);
        checkGet("{\"b\":null,\"a\":[1,2]}", "/a/1", 2.0);
        checkGet("{\"a\":[0,1,2,3,4,5,6,7,8,9,\"x\"]}", "/a/10", "x");

        // Property not found
        checkGetError("{}", "/a", Cause.PROPERTY, "/a");
        checkGetError("{\"b\":1}", "/a", Cause.PROPERTY, "/a");
        checkGetError("{\"b\":{\"a\":1},\"c\":[1,2],\"d\":null}", "/a", Cause.PROPERTY, "/a");
        checkGetError("{\"a\":{\"b\":1}}", "/a/c", Cause.PROPERTY, "/a/c");
        checkGetError("{\"a\":{\"b\":1}}", "/b/a", Cause.PROPERTY, "/b");
        checkGetError("{\"a\":[{\"b\":1}]}", "/a/0/c", Cause.PROPERTY, "/a/0/c");
        checkGetError("{\"~\":1}", "/~1", Cause.PROPERTY, "/~1");
        // Index out of range
        checkGetError("[]", "/0", Cause.INDEX, "/0");
        checkGetError("[1,2]", "/2", Cause.INDEX, "/2");
        checkGetError("[1,2]", "/-", Cause.INDEX, "/-");
        checkGetError("{\"a\":[[1],[2]]}", "/a/1/1", Cause.INDEX, "/a/1/1");
        checkGetError("{\"a\":[[1],[2]]}", "/a/2/0", Cause.INDEX, "/a/2");
        // Not an index: a navigation error, like a missing property
        checkGetError("[1]", "/x", Cause.INDEX, "/x");
        checkGetError("[1]", "/", Cause.INDEX, "/");
        checkGetError("[1]", "/01", Cause.INDEX, "/01");
        checkGetError("[1]", "/-1", Cause.INDEX, "/-1");
        checkGetError("[1]", "/1e0", Cause.INDEX, "/1e0");
        checkGetError("[1]", "/99999999999999999999", Cause.INDEX, "/99999999999999999999");
        checkGetError("{\"a\":[1]}", "/a/x/y", Cause.INDEX, "/a/x");
        checkGetError("{\"a\":[[1]]}", "/a/0/x", Cause.INDEX, "/a/0/x");
        // But a bad escape is a syntax error, whatever the container
        checkGetError("[1]", "/~2", Cause.SYNTAX, "/~2");
        checkGetError("{\"a\":[1]}", "/a/~/x", Cause.SYNTAX, "/a/~");
        // Invalid escape: the error is located at the bad token
        checkGetError("{\"a\":{\"b\":1}}", "/a/~2", Cause.SYNTAX, "/a/~2");
        checkGetError("{\"a\":{\"b\":1}}", "/a/~2/x", Cause.SYNTAX, "/a/~2");
        checkGetError("{\"a\":{\"b\":{\"c\":1}}}", "/a/b/~/x", Cause.SYNTAX, "/a/b/~");
        // Not an object or array: the error is located at the value which can't be traversed
        checkGetError("1", "/x", null, "");
        checkGetError("\"str\"", "/0/x", null, "");
        checkGetError("{\"s\":1}", "/s/x", null, "/s");
        checkGetError("{\"a\":{\"s\":1}}", "/a/s/x", null, "/a/s");
        checkGetError("{\"a\":{\"s\":1}}", "/a/s/x/y", null, "/a/s");
        checkGetError("{\"a\":null}", "/a/x", null, "/a");
        checkGetError("{\"a\":[1]}", "/a/0/x", null, "/a/0");
        checkGetError("[{\"s\":\"str\"}]", "/0/s/0", null, "/0/s");
        checkGetError("{\"a\":[{\"s\":true}]}", "/a/0/s/0", null, "/a/0/s");

        // Partially decoded containers mixed at various levels
        Map<String, Object> mixed = map(
                "a", rawArray("{\"b\":[1,2]}", "\"x\""),
                "c", rawObject("d", "[true]", "~", "null"),
                "e", list(rawObject("f", "{\"g\":3}")));
        Object[][] hits = {
            {"/a/0/b/1", 2.0},
            {"/a/1", "x"},
            {"/c/d/0", true},
            {"/c/~0", null},
            {"/e/0/f/g", 3.0},
            {"/e/0/f", map("g", 3.0)},
            // Partially decoded containers are returned as is when they are the leaf
            {"/a", mixed.get("a")},
            {"/c", mixed.get("c")},
            {"/e/0", ((List<?>) mixed.get("e")).get(0)},
        };
        for (Object[] test : hits) {
            Deep.assertDeepEquals(test[1], get.apply(mixed, (String) test[0]), "mixed => " + test[0]);
        }
        Object[][] misses = {
            {"/a/2", Cause.INDEX, "/a/2"},
            {"/a/-", Cause.INDEX, "/a/-"},
            {"/a/0/x", Cause.PROPERTY, "/a/0/x"},
            {"/c/x", Cause.PROPERTY, "/c/x"},
            {"/c/d/1", Cause.INDEX, "/c/d/1"},
            {"/e/0/x", Cause.PROPERTY, "/e/0/x"},
        };
        for (Object[] test : misses) {
            String what = "mixed => " + test[0] + " (error expected)";
            JsonPointerException err = assertThrows(JsonPointerException.class, () -> get.apply(mixed, (String) test[0]), what);
            assertTrue(err instanceof PtrError, what + ": got " + err.kind());
            assertEquals(test[1], err.cause(), what);
            assertEquals(test[2], err.pointer(), what);
        }
    }
}
