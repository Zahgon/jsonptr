// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static jsonptr.GetTester.list;
import static jsonptr.GetTester.map;
import static jsonptr.GetTester.raw;
import static jsonptr.GetTester.rawArray;
import static jsonptr.GetTester.rawObject;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPtrTest {
    @Test
    void testGet() {
        new GetTester(JsonPtr::get).runTest();
    }

    private static String describe(Object doc) {
        return doc instanceof JsonDecoder ? "JsonDecoder" : String.valueOf(doc);
    }

    /** A JSON text given as a String is checked decoded and also as a RawMessage. */
    private static void checkSet(Object data, String ptr, Object value, String jsonOut) {
        if (data instanceof String json) {
            // Same test with input converted to a RawMessage
            checkSet(raw(json), ptr, value, jsonOut);
            data = Json.parse(json);
        }
        String what = describe(data) + " + \"" + ptr + "\" " + value;
        JsonPtr.Ref doc = new JsonPtr.Ref(data);
        assertDoesNotThrow(() -> JsonPtr.set(doc, ptr, value), what);
        String out = Json.write(doc.get());
        // jsonOut is written in the encoder's output form (sorted keys, no spaces): compare the bytes
        assertEquals(jsonOut, out, what);
    }

    private static void checkError(JsonPointerException err, Cause cause, String loc, String what) {
        if (cause == null) {
            assertTrue(err instanceof DocumentError, what + ": got " + err.kind());
        } else {
            assertEquals(cause, err.cause(), what);
            // SYNTAX and DELETE_ROOT are reported by a BadPointerError, PROPERTY and INDEX by a PtrError
            boolean bad = cause == Cause.SYNTAX || cause == Cause.DELETE_ROOT;
            assertTrue(bad ? err instanceof BadPointerError : err instanceof PtrError, what + ": got " + err.kind());
        }
        assertEquals(loc, err.pointer(), what);
    }

    /** Checks the error of a failed set: a DocumentError when cause is null. */
    private static void checkSetError(Object data, String ptr, Object value, Cause cause, String loc) {
        String what = describe(data) + " + \"" + ptr + "\"";
        JsonPtr.Ref doc = new JsonPtr.Ref(data);
        checkError(assertThrows(JsonPointerException.class, () -> JsonPtr.set(doc, ptr, value), what), cause, loc, what);
    }

    private record Shape(Object doc, String ptr, Object value, Object expected) {
    }

    private record Failed(Object doc, String ptr, Object expected, String get) {
    }

    @Test
    void testSet() {
        checkSet("null", "", "x", "\"x\"");
        checkSet("null", "", 1, "1");
        checkSet("null", "", list(), "[]");
        checkSet("null", "", map(), "{}");
        checkSet("[]", "", null, "null");
        checkSet("{}", "", null, "null");

        checkSet("[null]", "/0", null, "[null]");
        checkSet("[null]", "/0", true, "[true]");
        // Appending
        checkSet("[]", "/-", true, "[true]");
        checkSet(list(), "/-", true, "[true]");
        checkSet(list(), "/-", true, "[true]");
        checkSet("[]", "/0", true, "[true]");
        checkSet("[]", "/1", true, "[null,true]");
        checkSet("[]", "/2", true, "[null,null,true]");
        checkSet(list(), "/2", true, "[null,null,true]");
        checkSet("[0,1]", "/2", true, "[0,1,true]");
        checkSet("[0,1]", "/-", true, "[0,1,true]");
        checkSet("{}", "/ok", true, "{\"ok\":true}");
        checkSet("{\"x\":[]}", "/x/-", true, "{\"x\":[true]}");

        // RawMessage nested in the tree: it must be decoded and stored in the tree before being modified
        checkSet(map("a", raw("{}")), "/a/b", 1, "{\"a\":{\"b\":1}}");
        checkSet(map("a", raw("{\"x\":{\"y\":[]}}")), "/a/x/y/-", true, "{\"a\":{\"x\":{\"y\":[true]}}}");
        checkSet(map("a", raw("[]")), "/a/-", true, "{\"a\":[true]}");
        checkSet(list(raw("[1]")), "/0/-", 2, "[[1,2]]");
        checkSet(list(raw("[1]")), "/0/0", 2, "[[2]]");
        checkSet(map("a", raw("{\"b\":{\"c\":1}}")), "/a/b/c", 2, "{\"a\":{\"b\":{\"c\":2}}}");
        // Replacing the RawMessage itself
        checkSet(map("a", raw("{}")), "/a", 1, "{\"a\":1}");
        // Nested streamed decoder
        checkSet(map("a", Json.newDecoder("{\"x\":[1]}")), "/a/x/-", 2, "{\"a\":{\"x\":[1,2]}}");
        // Nested in a RawMessage root
        checkSet("{\"a\":{\"b\":[]}}", "/a/b/0", true, "{\"a\":{\"b\":[true]}}");

        // Invalid JSON in a nested RawMessage
        JsonPtr.Ref invalid = new JsonPtr.Ref(map("a", raw("{")));
        JsonPointerException invalidErr = assertThrows(JsonPointerException.class, () -> JsonPtr.set(invalid, "/a/b", 1));
        assertTrue(invalidErr instanceof DocumentError, "invalid nested JSON: got " + invalidErr.kind());

        // Partially decoded containers as direct parent: the value is stored as a RawMessage
        checkSet(rawObject(), "/a", 1, "{\"a\":1}");
        checkSet(new RawObject(), "/a", 1, "{\"a\":1}");
        checkSet(rawObject("a", "1"), "/a", "x", "{\"a\":\"x\"}");
        checkSet(rawObject("a", "1"), "/~1", List.of(1), "{\"/\":[1],\"a\":1}");
        checkSet(rawObject(), "/a", raw("{\"x\":1}"), "{\"a\":{\"x\":1}}");
        checkSet(rawObject(), "/a", Json.newDecoder("[1]"), "{\"a\":[1]}");
        checkSet(rawArray(), "/-", true, "[true]");
        checkSet(new RawArray(), "/-", true, "[true]");
        checkSet(rawArray("1"), "/0", "x", "[\"x\"]");
        checkSet(rawArray("1"), "/-", 2, "[1,2]");
        checkSet(rawArray("1"), "/3", 2, "[1,null,null,2]");
        checkSet(rawArray(), "/0", raw("{}"), "[{}]");
        checkSet(map("a", rawArray()), "/a/-", true, "{\"a\":[true]}");
        checkSet(map("a", rawObject()), "/a/b", null, "{\"a\":{\"b\":null}}");
        // Partially decoded containers traversed on the path
        checkSet(rawObject("a", "{\"b\":1}"), "/a/b", 2, "{\"a\":{\"b\":2}}");
        checkSet(rawObject("a", "{\"b\":1}", "c", "[]"), "/c/-", 2, "{\"a\":{\"b\":1},\"c\":[2]}");
        checkSet(rawArray("[1]", "{}"), "/0/-", 2, "[[1,2],{}]");
        checkSet(rawArray("[1]", "{}"), "/1/x", 2, "[[1],{\"x\":2}]");
        checkSet(rawObject("a", "[{\"b\":[]}]"), "/a/0/b/-", 2, "{\"a\":[{\"b\":[2]}]}");
        checkSet(map("a", rawObject("b", "[1]")), "/a/b/-", 2, "{\"a\":{\"b\":[1,2]}}");
        checkSet(list(rawArray("{\"x\":1}")), "/0/0/x", 2, "[[{\"x\":2}]]");

        // Shape of the tree after set: every container on the path (including a partially decoded
        // one, and the direct parent) ends up as a Map or a List, the untouched entries are kept raw,
        // and the value is stored as is (a JsonDecoder value is drained into a RawMessage)
        for (Shape test : List.of(
                // Partially decoded direct parent
                new Shape(rawObject("b", "2"), "/a", 1, map("a", 1, "b", raw("2"))),
                new Shape(rawArray("1"), "/-", 2, list(raw("1"), 2)),
                // Partially decoded container traversed on the path
                new Shape(rawObject("a", "{}", "b", "{\"c\":1}"), "/a/x", 1, map("a", map("x", 1), "b", raw("{\"c\":1}"))),
                // Lazy decoding of raw documents: only the containers on the path are decoded, one layer each
                new Shape(raw("{\"a\":{\"b\":1},\"c\":{\"d\":2}}"), "/a/x", 3,
                        map("a", map("b", raw("1"), "x", 3), "c", raw("{\"d\":2}"))),
                new Shape(raw("[[1],[2]]"), "/0/-", 9, list(list(raw("1"), 9), raw("[2]"))),
                new Shape(raw("{\"a\":[{\"b\":{\"c\":1},\"d\":2},{\"e\":3}],\"f\":4}"), "/a/0/b/x", true,
                        map("a", list(map("b", map("c", raw("1"), "x", true), "d", raw("2")), raw("{\"e\":3}")),
                                "f", raw("4"))),
                // Raw direct parent
                new Shape(raw("{\"a\":{\"b\":1}}"), "/x", 2, map("a", raw("{\"b\":1}"), "x", 2)),
                new Shape(raw("[[1]]"), "/-", 2, list(raw("[1]"), 2)),
                // Leading whitespace
                new Shape(raw(" \n\t{\"a\":1}"), "/b", 2, map("a", raw("1"), "b", 2)),
                // Streamed document
                new Shape(Json.newDecoder("{\"a\":{\"b\":1},\"c\":2}"), "/a/x", 3,
                        map("a", map("b", raw("1"), "x", 3), "c", raw("2"))),
                // Nested in a tree
                new Shape(map("a", raw("{\"b\":{},\"c\":1}")), "/a/b/x", 2,
                        map("a", map("b", map("x", 2), "c", raw("1")))),
                // Values: a RawMessage is stored as is, a JsonDecoder is drained into a RawMessage
                // (both at root and deeper)
                new Shape(map(), "/a", raw("{\"x\":1}"), map("a", raw("{\"x\":1}"))),
                new Shape(map(), "", raw("{\"x\":1}"), raw("{\"x\":1}")),
                new Shape(map(), "/a", Json.newDecoder("{\"x\":1} 2"), map("a", raw("{\"x\":1}"))),
                new Shape(map(), "", Json.newDecoder("[1] 2"), raw("[1]")))) {
            String what = "shape: " + describe(test.doc()) + " + \"" + test.ptr() + "\"";
            JsonPtr.Ref doc = new JsonPtr.Ref(test.doc());
            assertDoesNotThrow(() -> JsonPtr.set(doc, test.ptr(), test.value()), what);
            Deep.assertDeepEquals(test.expected(), doc.get(), what);
        }
        // A raw scalar can't be traversed; the document is left untouched on error
        JsonPtr.Ref scalar = new JsonPtr.Ref(raw(" 1"));
        JsonPointerException scalarErr = assertThrows(JsonPointerException.class, () -> JsonPtr.set(scalar, "/a", 1));
        assertTrue(scalarErr instanceof DocumentError, "raw scalar: got " + scalarErr.kind());
        Deep.assertDeepEquals(raw(" 1"), scalar.get(), "raw scalar");
        // On error, a JsonDecoder on the path that has been read is replaced in the tree by the raw
        // value read, so the document stays usable
        for (Failed test : List.of(
                new Failed(map("a", Json.newDecoder("{\"b\":1} \"next\"")), "/a/x/y", map("a", raw("{\"b\":1}")), "/a/b"),
                new Failed(list(Json.newDecoder("[1] \"next\"")), "/0/5/y", list(raw("[1]")), "/0/0"),
                // Failure below the decoder, in a lazily decoded layer
                new Failed(map("a", Json.newDecoder("{\"b\":{\"c\":1},\"d\":2}")), "/a/b/c/x",
                        map("a", raw("{\"b\":{\"c\":1},\"d\":2}")), "/a/b/c"),
                // Decoder at the root
                new Failed(Json.newDecoder("{\"b\":1}"), "/x/y", raw("{\"b\":1}"), "/b"))) {
            String what = "failed set through a JsonDecoder: " + test.ptr();
            JsonPtr.Ref doc = new JsonPtr.Ref(test.doc());
            assertThrows(JsonPointerException.class, () -> JsonPtr.set(doc, test.ptr(), 1), what);
            Deep.assertDeepEquals(test.expected(), doc.get(), what);
            // The document is still usable
            assertDoesNotThrow(() -> JsonPtr.get(doc.get(), test.get()), what + ": get " + test.get());
        }

        // An invalid JsonDecoder value is rejected, the document is left untouched
        JsonPtr.Ref untouched = new JsonPtr.Ref(map());
        JsonPointerException valueErr = assertThrows(JsonPointerException.class,
                () -> JsonPtr.set(untouched, "/a", Json.newDecoder("{")));
        assertTrue(valueErr instanceof DocumentError, "invalid JsonDecoder value: got " + valueErr.kind());
        Deep.assertDeepEquals(map(), untouched.get(), "invalid JsonDecoder value");

        // Navigation errors on the path to the parent
        checkSetError(map(), "a", 1, Cause.SYNTAX, "a");
        checkSetError(map(), "/~2/x", 1, Cause.SYNTAX, "/~2");
        checkSetError(map("a", map()), "/a/~2/x", 1, Cause.SYNTAX, "/a/~2");
        checkSetError(map("a", map()), "/a/~2", 1, Cause.SYNTAX, "/a/~2");
        checkSetError(map(), "/a/x", 1, Cause.PROPERTY, "/a");
        checkSetError(map("a", 1), "/a/x", 1, null, "/a");
        checkSetError(list(), "/x/y", 1, Cause.INDEX, "/x");
        checkSetError(list(list()), "/0/x/y", 1, Cause.INDEX, "/0/x");
        checkSetError(list(list()), "/0/~2/y", 1, Cause.SYNTAX, "/0/~2");
        checkSetError(list(), "/~", 1, Cause.SYNTAX, "/~");
        checkSetError(list(), "/0/y", 1, Cause.INDEX, "/0");
        checkSetError(list(), "/-/y", 1, Cause.INDEX, "/-");
        checkSetError(list(1), "/0/y", 1, null, "/0");
        checkSetError(rawObject(), "/~2/x", 1, Cause.SYNTAX, "/~2");
        checkSetError(rawObject("a", "{}"), "/a/~2/x", 1, Cause.SYNTAX, "/a/~2");
        checkSetError(rawArray(), "/x/y", 1, Cause.INDEX, "/x");
        checkSetError(rawArray("[]"), "/0/x/y", 1, Cause.INDEX, "/0/x");
        checkSetError(Json.newDecoder("{"), "/a", 1, null, "");
        checkSetError(Json.newDecoder("[x]"), "/0/a", 1, null, "");
        // Errors with partially decoded containers
        checkSetError(rawObject(), "/~2", 1, Cause.SYNTAX, "/~2");
        checkSetError(rawObject(), "/a/b", 1, Cause.PROPERTY, "/a");
        checkSetError(rawObject("a", "{"), "/a/b", 1, null, "/a");
        checkSetError(rawObject("a", "["), "/a/-", 1, null, "/a");
        checkSetError(rawObject("a", "x"), "/a/b", 1, null, "/a");
        checkSetError(rawObject("a", ""), "/a/b", 1, null, "/a");
        checkSetError(rawArray(), "/x", 1, Cause.INDEX, "/x");
        checkSetError(rawArray(), "/0/b", 1, Cause.INDEX, "/0");
        checkSetError(rawArray("1"), "/0/b", 1, null, "/0");
    }

    private static void checkDelete(Object data, String ptr, Object expectedValue, String jsonOut) {
        String what = describe(data) + " - \"" + ptr + "\"";
        JsonPtr.Ref doc = new JsonPtr.Ref(data);
        Object got = assertDoesNotThrow(() -> JsonPtr.delete(doc, ptr), what);
        Deep.assertDeepEquals(expectedValue, got, what + ": deleted value");
        assertEquals(jsonOut, Json.write(doc.get()), what);
    }

    /** Checks the error of a failed delete: a DocumentError when cause is null. */
    private static void checkDeleteError(Object data, String ptr, Cause cause, String loc) {
        String what = describe(data) + " - \"" + ptr + "\"";
        JsonPtr.Ref doc = new JsonPtr.Ref(data);
        checkError(assertThrows(JsonPointerException.class, () -> JsonPtr.delete(doc, ptr), what), cause, loc, what);
    }

    @Test
    void testDelete() {
        checkDelete(map("a", 1, "b", 2), "/a", 1, "{\"b\":2}");
        checkDelete(list(1, 2, 3), "/1", 2, "[1,3]");
        checkDelete(list(1, 2, 3), "/2", 3, "[1,2]");
        checkDelete(list(1), "/0", 1, "[]");
        checkDelete(map("a", list(1, 2)), "/a/0", 1, "{\"a\":[2]}");

        // RawMessage in the tree, at root or nested: the container is decoded lazily, so the deleted
        // value is returned raw
        checkDelete(raw("{\"a\":1,\"b\":2}"), "/a", raw("1"), "{\"b\":2}");
        checkDelete(raw("[1,2,3]"), "/1", raw("2"), "[1,3]");
        checkDelete(map("a", raw("{\"b\":1,\"c\":2}")), "/a/b", raw("1"), "{\"a\":{\"c\":2}}");
        checkDelete(map("a", raw("[1,2,3]")), "/a/1", raw("2"), "{\"a\":[1,3]}");
        checkDelete(map("a", raw("{\"b\":{\"c\":[true]}}")), "/a/b/c/0", raw("true"), "{\"a\":{\"b\":{\"c\":[]}}}");
        checkDelete(map("a", Json.newDecoder("{\"b\":1,\"c\":2}")), "/a/b", raw("1"), "{\"a\":{\"c\":2}}");

        // Lazy decoding: the tree shape after a delete through a raw document
        JsonPtr.Ref lazy = new JsonPtr.Ref(raw("{\"a\":{\"b\":1,\"c\":2},\"d\":{\"e\":3}}"));
        assertDoesNotThrow(() -> JsonPtr.delete(lazy, "/a/b"));
        Deep.assertDeepEquals(map("a", rawObject("c", "2"), "d", raw("{\"e\":3}")), lazy.get(), "lazy delete");

        // Partially decoded containers as direct parent: the raw value is returned and the container
        // type is preserved
        checkDelete(rawObject("a", "1", "b", "2"), "/a", raw("1"), "{\"b\":2}");
        checkDelete(rawObject("/", "1", "b", "2"), "/~1", raw("1"), "{\"b\":2}");
        checkDelete(rawArray("1", "2", "3"), "/1", raw("2"), "[1,3]");
        checkDelete(rawArray("1"), "/0", raw("1"), "[]");
        checkDelete(map("a", rawArray("1", "2")), "/a/0", raw("1"), "{\"a\":[2]}");
        checkDelete(list(rawObject("a", "1")), "/0/a", raw("1"), "[{}]");
        // Partially decoded containers traversed on the path
        checkDelete(rawObject("a", "[1,2]", "b", "{}"), "/a/0", raw("1"), "{\"a\":[2],\"b\":{}}");
        checkDelete(rawObject("a", "{\"b\":1,\"c\":2}"), "/a/b", raw("1"), "{\"a\":{\"c\":2}}");
        checkDelete(rawArray("[1,2]", "{}"), "/0/1", raw("2"), "[[1],{}]");
        checkDelete(rawArray("{\"a\":[true]}"), "/0/a/0", raw("true"), "[{\"a\":[]}]");

        JsonPtr.Ref kept = new JsonPtr.Ref(rawArray("1", "2"));
        assertDoesNotThrow(() -> JsonPtr.delete(kept, "/0"));
        Deep.assertDeepEquals(rawArray("2"), kept.get(), "RawArray kept");

        // On error, a JsonDecoder on the path that has been read is replaced in the tree by the raw
        // value read, so the document stays usable
        for (Failed test : List.of(
                new Failed(map("a", Json.newDecoder("{\"b\":1} \"next\"")), "/a/x/y", map("a", raw("{\"b\":1}")), "/a/b"),
                new Failed(list(Json.newDecoder("[1] \"next\"")), "/0/5", list(raw("[1]")), "/0/0"),
                new Failed(map("a", Json.newDecoder("{\"b\":{\"c\":1},\"d\":2}")), "/a/b/c/x",
                        map("a", raw("{\"b\":{\"c\":1},\"d\":2}")), "/a/b/c"),
                new Failed(Json.newDecoder("{\"b\":1}"), "/x", raw("{\"b\":1}"), "/b"))) {
            String what = "failed delete through a JsonDecoder: " + test.ptr();
            JsonPtr.Ref doc = new JsonPtr.Ref(test.doc());
            assertThrows(JsonPointerException.class, () -> JsonPtr.delete(doc, test.ptr()), what);
            Deep.assertDeepEquals(test.expected(), doc.get(), what);
            assertDoesNotThrow(() -> JsonPtr.get(doc.get(), test.get()), what + ": get " + test.get());
        }

        // Errors
        checkDeleteError(map(), "", Cause.DELETE_ROOT, "");
        checkDeleteError(map(), "a", Cause.SYNTAX, "a");
        checkDeleteError(map(), "/a", Cause.PROPERTY, "/a");
        checkDeleteError(map(), "/a/b", Cause.PROPERTY, "/a");
        checkDeleteError(map("a", map()), "/a/b/c", Cause.PROPERTY, "/a/b");
        checkDeleteError(map("a", map()), "/a/~2/c", Cause.SYNTAX, "/a/~2");
        checkDeleteError(map("a", 1), "/a/b", null, "/a");
        checkDeleteError(list(1), "/1", Cause.INDEX, "/1");
        checkDeleteError(list(1), "/-", Cause.INDEX, "/-");
        checkDeleteError(list(1), "/1/x", Cause.INDEX, "/1");
        checkDeleteError(list(1), "/-/x", Cause.INDEX, "/-");
        checkDeleteError(list(1), "/x", Cause.INDEX, "/x");
        checkDeleteError(list(list()), "/0/x/y", Cause.INDEX, "/0/x");
        checkDeleteError(list(list()), "/0/~2/y", Cause.SYNTAX, "/0/~2");
        checkDeleteError(list(1), "/~", Cause.SYNTAX, "/~");
        checkDeleteError(map("a", raw("{")), "/a/b", null, "/a");
        checkDeleteError(raw("{"), "/a", null, "");
        checkDeleteError(Json.newDecoder("[x]"), "/0/a", null, "");
        checkDeleteError(rawObject(), "/a", Cause.PROPERTY, "/a");
        checkDeleteError(rawObject(), "/~2", Cause.SYNTAX, "/~2");
        checkDeleteError(rawObject(), "/a/b", Cause.PROPERTY, "/a");
        checkDeleteError(rawObject("a", "{}"), "/a/~2/c", Cause.SYNTAX, "/a/~2");
        checkDeleteError(rawObject("a", "{"), "/a/b", null, "/a");
        checkDeleteError(rawArray("1"), "/1", Cause.INDEX, "/1");
        checkDeleteError(rawArray("1"), "/-", Cause.INDEX, "/-");
        checkDeleteError(rawArray("1"), "/x", Cause.INDEX, "/x");
        checkDeleteError(rawArray("1"), "/1/x", Cause.INDEX, "/1");
        checkDeleteError(rawArray("[]"), "/0/x/y", Cause.INDEX, "/0/x");
        checkDeleteError(rawArray("1"), "/0/x", null, "/0");
    }
}
