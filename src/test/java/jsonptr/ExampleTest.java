// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static jsonptr.GetTester.list;
import static jsonptr.GetTester.map;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExampleTest {
    /** Example from https://tools.ietf.org/html/rfc6901#section-5. */
    @Test
    void example() {
        String json = """
                {
                   "foo": ["bar", "baz"],
                   "": 0,
                   "a/b": 1,
                   "c%d": 2,
                   "e^f": 3,
                   "g|h": 4,
                   "i\\\\j": 5,
                   "k\\"l": 6,
                   " ": 7,
                   "m~n": 8
                }""";
        Object doc = Json.parse(json);
        Object[][] expected = {
            {"/foo", list("bar", "baz")},
            {"/foo/0", "bar"},
            {"/", 0.0},
            {"/a~1b", 1.0},
            {"/c%d", 2.0},
            {"/e^f", 3.0},
            {"/g|h", 4.0},
            {"/i\\j", 5.0},
            {"/k\"l", 6.0},
            {"/ ", 7.0},
            {"/m~0n", 8.0},
        };
        for (Object[] row : expected) {
            assertEquals(row[1], JsonPtr.get(doc, (String) row[0]), (String) row[0]);
        }
    }

    /** Shows how to extract from a streamed JSON document. */
    @Test
    void exampleJsonDecoder() {
        byte[] json = "{\"a\": 42,\"b\":5}".getBytes(StandardCharsets.UTF_8);
        // Hands out one byte per read, like Go's iotest.OneByteReader
        InputStream stream = new ByteArrayInputStream(json) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        JsonDecoder decoder = Json.newDecoder(stream);
        assertEquals(42.0, JsonPtr.get(decoder, "/a"));
    }

    @Test
    void exampleSet() {
        Object[][] steps = {
            {"", map()},
            {"/arr", list()},
            {"/arr/-", 3},
            {"/arr/-", 2},
            {"/arr/-", 1},
            {"/obj", map()},
            {"/obj/str", "hello"},
            {"/obj/bool", true},
            {"/arr/-", 0},
            {"/obj/", null},
        };
        JsonPtr.Ref doc = new JsonPtr.Ref(null);
        for (Object[] step : steps) {
            JsonPtr.set(doc, (String) step[0], step[1]);
        }
        assertEquals("{\"arr\":[3,2,1,0],\"obj\":{\"\":null,\"bool\":true,\"str\":\"hello\"}}", Json.write(doc.get()));
    }
}
