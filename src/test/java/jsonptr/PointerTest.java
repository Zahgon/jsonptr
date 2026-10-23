// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static jsonptr.GetTester.list;
import static jsonptr.GetTester.map;
import static jsonptr.GetTester.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PointerTest {
    /** One row of the parse table: either the tokens, or the expected error. */
    private record ParseCase(String in, List<String> out, String kind, String badPtr) {
        static ParseCase ok(String in, String... out) {
            return new ParseCase(in, List.of(out), null, null);
        }

        static ParseCase sentinel(String in) {
            return new ParseCase(in, null, "Sentinel", "");
        }

        static ParseCase bad(String in, String badPtr) {
            return new ParseCase(in, null, "BadPointerError", badPtr);
        }
    }

    private static final List<ParseCase> PARSE_TESTS = List.of(
            ParseCase.ok(""),
            ParseCase.sentinel("a"),
            ParseCase.sentinel("~"),
            ParseCase.ok("/", ""),
            ParseCase.ok("////", "", "", "", ""),
            ParseCase.ok("/a", "a"),
            ParseCase.bad("/~", "/~"),
            ParseCase.bad("/~x", "/~x"),
            ParseCase.bad("/a~", "/a~"),
            ParseCase.bad("/a~x", "/a~x"),
            ParseCase.bad("/abc/~", "/abc/~"),
            ParseCase.bad("/abc/~/b", "/abc/~"),
            ParseCase.bad("/~2/x", "/~2"),
            ParseCase.bad("/a/b~2/x", "/a/b~2"),
            ParseCase.bad("/abc/~x", "/abc/~x"),
            ParseCase.bad("/abc/a~", "/abc/a~"),
            ParseCase.ok("/~0", "~"),
            ParseCase.ok("/~1", "/"),
            ParseCase.ok("/~0~0", "~~"),
            ParseCase.ok("/~0~1", "~/"),
            ParseCase.ok("/~1~0", "/~"),
            ParseCase.ok("/~1~1", "//"),
            ParseCase.ok("/abc/def~0/ghi", "abc", "def~", "ghi"),
            ParseCase.ok("/abc/def~0/g~1hi", "abc", "def~", "g/hi"),
            // Real test cases
            ParseCase.ok("/definitions/Location", "definitions", "Location"),
            ParseCase.ok("/paths/~1home~1dolmen", "paths", "/home/dolmen"),
            ParseCase.ok("/paths/~0dolmen", "paths", "~dolmen"));

    /** The two parse targets of the Go test: Parse with String, UnmarshalText with MarshalText. */
    private record Target(Function<String, Pointer> parse, Function<Pointer, String> stringify) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target(Pointer::parse, Pointer::toString),
            new Target(s -> {
                Pointer p = new Pointer();
                p.unmarshalText(s.getBytes(StandardCharsets.UTF_8));
                return p;
            }, p -> new String(p.marshalText(), StandardCharsets.UTF_8)));

    @Test
    void testPointerParse() {
        for (ParseCase test : PARSE_TESTS) {
            for (Target target : TARGETS) {
                if (test.out() != null) {
                    Pointer out = target.parse().apply(test.in());
                    assertEquals(test.out(), out.tokens(), test.in());
                    assertEquals(test.in(), target.stringify().apply(out), "roundtrip " + test.in());
                } else {
                    JsonPointerException err = assertThrows(JsonPointerException.class,
                            () -> target.parse().apply(test.in()), test.in());
                    assertEquals(test.kind(), err.kind(), test.in());
                    assertEquals(test.badPtr(), err.pointer(), test.in());
                    assertEquals(Cause.SYNTAX, err.cause(), test.in());
                }
            }
        }
    }

    /** Checks that unmarshalText neither modifies nor retains the text it is given. */
    @Test
    void testPointerUnmarshalTextInput() {
        String in = "/a~1b/c~0d";
        byte[] text = in.getBytes(StandardCharsets.UTF_8);
        Pointer p = new Pointer();
        p.unmarshalText(text);
        assertEquals(in, new String(text, StandardCharsets.UTF_8), "input modified");
        assertEquals(new Pointer("a/b", "c~d"), p);
        // Clobber the input: the parsed pointer must not change
        byte[] clobber = "/xxxxxxxxx".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(clobber, 0, text, 0, clobber.length);
        assertEquals(new Pointer("a/b", "c~d"), p, "pointer aliases the input");

        p.unmarshalText(null);
        assertTrue(p.isRoot(), "empty text");
    }

    /**
     * Another implementation of parse that aimed to be faster, but isn't for the most common case
     * (no escape). See benchmarkParse for comparison.
     */
    static Pointer altParse(String pointer) {
        if (pointer.isEmpty()) {
            return new Pointer();
        }
        if (pointer.charAt(0) != '/') {
            throw new SentinelError(Cause.SYNTAX);
        }
        String[] ptr = pointer.substring(1).split("/", -1);
        int p = 1;
        int i = 0;
        while (true) {
            int q = p;
            while (q < pointer.length()) {
                if (pointer.charAt(q) == '~') {
                    break;
                }
                q++;
            }
            if (q == pointer.length()) {
                break;
            }
            while (p + ptr[i].length() < q) {
                i++;
                p += ptr.length + 1;
            }
            ptr[i] = Escape.unescapeString(ptr[i]);
            i++;
            if (i == ptr.length) {
                break;
            }
        }
        return new Pointer(ptr);
    }

    @Test
    void benchmarkParse() {
        Map<String, Function<String, Pointer>> implementations = new LinkedHashMap<>();
        implementations.put("Pointer.parse", Pointer::parse);
        implementations.put("altParse", PointerTest::altParse);
        for (ParseCase test : PARSE_TESTS) {
            for (Map.Entry<String, Function<String, Pointer>> impl : implementations.entrySet()) {
                long start = System.nanoTime();
                for (int n = 0; n < 1000; n++) {
                    try {
                        impl.getValue().apply(test.in());
                    } catch (JsonPointerException e) {
                        // Errors are part of the measured work
                    }
                }
                assertTrue(System.nanoTime() >= start, test.in() + "/" + impl.getKey());
            }
        }
    }

    private record Move(Supplier<Pointer> move, String expected) {
    }

    @Test
    void testPointer() {
        Pointer p = new Pointer();
        assertTrue(p.isRoot(), "Empty must give a root pointer");
        assertEquals("", p.toString(), "A root pointer is \"\"");

        Supplier<Pointer> up = p::up;
        Function<String, Supplier<Pointer>> property = name -> () -> {
            Pointer q = p.property(name);
            assertEquals(name, q.leafName(), "LeafName() mismatch");
            return q;
        };
        java.util.function.LongFunction<Supplier<Pointer>> index = i -> () -> {
            Pointer q = p.index(i);
            assertEquals(i, q.leafIndex(), "LeafIndex() mismatch");
            return q;
        };
        Function<List<Supplier<Pointer>>, Supplier<Pointer>> chain = moves -> () -> {
            Pointer q = p;
            for (Supplier<Pointer> m : moves) {
                q = m.get();
            }
            return q;
        };

        for (Move test : List.of(
                new Move(property.apply("foo"), "/foo"),
                new Move(property.apply("bar"), "/foo/bar"),
                new Move(index.apply(0), "/foo/bar/0"),
                new Move(up, "/foo/bar"),
                new Move(up, "/foo"),
                new Move(index.apply(4), "/foo/4"),
                new Move(up, "/foo"),
                new Move(property.apply("a/a"), "/foo/a~1a"),
                new Move(chain.apply(List.of(up, property.apply("a~a"))), "/foo/a~0a"),
                new Move(chain.apply(List.of(up, property.apply("~~//"))), "/foo/~0~0~1~1"),
                new Move(chain.apply(List.of(up, property.apply("~01"))), "/foo/~001"),
                new Move(chain.apply(List.of(up, up)), ""))) {
            Pointer q = test.move().get();
            assertSame(p, q, "Chaining failure");

            String got = p.toString();
            assertEquals(test.expected(), got);
            assertEquals(got.isEmpty(), p.isRoot(), "IsRoot failure");

            if (!p.isRoot()) {
                got = p.property(p.pop()).toString();
                assertEquals(test.expected(), got, "Pop+Property");
            }

            assertEquals(test.expected(), Pointer.parse(test.expected()).toString());

            Pointer r = p.copy();
            assertEquals(test.expected(), r.toString(), "Clone error");
            r.property("baz");
            assertEquals(test.expected() + "/baz", r.toString(), "Property() failure in clone");
            // Check that p has not changed
            assertEquals(test.expected(), p.toString(), "Cloning failure: p is altered");
        }
        assertTrue(p.isRoot(), "IsRoot failure");
    }

    @Test
    void examplePointerConversion() {
        assertEquals("/foo/bar/a~1b/x~0y", new Pointer("foo", "bar", "a/b", "x~y").toString());
        assertEquals("", new Pointer().toString());
        assertEquals("", new Pointer(new String[0]).toString());
        assertEquals("/a/b", new Pointer("a", "b").toString());
        assertEquals("/a~0~1b", new Pointer("a~/b").toString());
    }

    @Test
    void examplePointerNavigation() {
        Pointer ptr = new Pointer();
        assertEquals("", ptr.toString());
        ptr.property("foo").index(3).property("a/b");
        assertEquals("/foo/3/a~1b", ptr.toString());
        ptr.up();
        assertEquals("/foo/3", ptr.toString());
        ptr.property("c~d");
        assertEquals("/foo/3/c~0d", ptr.toString());
    }

    @Test
    void testPointerClone() {
        Pointer orig = new Pointer("foo");
        Pointer clone = orig.copy();
        assertEquals("/foo", clone.toString());
        orig.up().property("bar");
        assertEquals("/foo", clone.toString());
        assertFalse(orig.equals(clone));
    }

    private record InCase(Pointer ptr, Object doc, String loc) {
    }

    /** Checks the location reported by Pointer.in when the pointer goes through a non-container. */
    @Test
    void testPointerInDocumentError() {
        for (InCase test : List.of(
                new InCase(new Pointer("a"), 42.0, ""),
                new InCase(new Pointer("a"), null, ""),
                new InCase(new Pointer("a"), "x", ""),
                new InCase(new Pointer("a", "b"), map("a", 1.0), "/a"),
                new InCase(new Pointer("0", "b"), list(true), "/0"),
                new InCase(new Pointer("a", "b", "c"), map("a", map("b", null)), "/a/b"),
                // Same, through the streamed decoder
                new InCase(new Pointer("a"), raw("42"), ""),
                new InCase(new Pointer("a", "b"), raw("{\"a\":1}"), "/a"),
                new InCase(new Pointer("a", "b"), map("a", raw("1")), "/a"),
                new InCase(new Pointer("a", "b", "c"), map("a", raw("{\"b\":1}")), "/a/b"))) {
            JsonPointerException err = assertThrows(JsonPointerException.class, () -> test.ptr().in(test.doc()),
                    test.ptr() + " in " + test.doc());
            assertTrue(err instanceof DocumentError, "got " + err.kind());
            assertEquals(test.loc(), err.pointer());
        }
    }

    /** Tests parse() and Pointer.in(). */
    @Test
    void testPointerIn() {
        new GetTester((doc, pointer) -> Pointer.parse(pointer).in(doc)).runTest();
    }
}
