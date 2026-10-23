// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class EscapeTest {
    @Test
    void exampleEscapeString() {
        assertEquals("a~0b c~1d", Escape.escapeString("a~b c/d"));
    }

    @Test
    void exampleUnescapeString() {
        assertEquals("a~b c/d", Escape.unescapeString("a~0b c~1d"));
    }

    @Test
    void exampleUnescapeStringError() {
        SentinelError err = assertThrows(SentinelError.class, () -> Escape.unescapeString("a~x"));
        assertEquals(Cause.SYNTAX, err.cause());
    }

    @Test
    void testEscape() {
        String[][] cases = {
            {"", ""},
            {"a", "a"},
            {"a~", "a~0"},
            {"~a", "~0a"},
            {"a~b", "a~0b"},
            {"a/", "a~1"},
            {"/a", "~1a"},
            {"a/b", "a~1b"},
            {"a/~b", "a~1~0b"},
            {"a~/b", "a~0~1b"},
            {"a~~~b", "a~0~0~0b"},
            {"a///b", "a~1~1~1b"},
            {"a/b/c/d", "a~1b~1c~1d"},
            {"é~é", "é~0é"},
            {"é~", "é~0"},
            {"~é", "~0é"},
        };
        for (String[] tc : cases) {
            assertEquals(tc[1], Escape.escapeString(tc[0]), "EscapeString " + tc[0]);
            for (int cap = 0; cap <= 11; cap++) {
                StringBuilder in = new StringBuilder(cap);
                assertEquals(tc[1], Escape.appendEscape(in, tc[0]).toString(), "AppendEscape cap=" + cap + " " + tc[0]);
            }
            for (byte[] in : List.of(new byte[0], "p".getBytes(StandardCharsets.UTF_8))) {
                byte[] out = Escape.appendEscape(in, tc[0]);
                assertEquals(new String(in, StandardCharsets.UTF_8) + tc[1], new String(out, StandardCharsets.UTF_8),
                        "AppendEscape bytes " + tc[0]);
            }
        }
    }

    // The benchmark cases with their escaped form, written out so that each implementation is
    // checked against fixed values.
    private static final String[][] ESCAPE_BENCHMARK_CASES = {
        {"property", "property"},
        {"name", "name"},
        {"id", "id"},
        {"/usr/local/go/pkg/", "~1usr~1local~1go~1pkg~1"},
        {"https://github.com/dolmen-go/jsonptr/", "https:~1~1github.com~1dolmen-go~1jsonptr~1"},
        {"~/.ssh/authorized_keys", "~0~1.ssh~1authorized_keys"},
    };

    static String escapeWithReplacer(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    private static void benchmarkEscape(UnaryOperator<String> escapeFunc) {
        for (int n = 0; n < 1000; n++) {
            for (String[] tc : ESCAPE_BENCHMARK_CASES) {
                assertEquals(tc[1], escapeFunc.apply(tc[0]), tc[0]);
            }
        }
    }

    @Test
    void benchmarkEscape() {
        benchmarkEscape(Escape::escapeString);
    }

    @Test
    void benchmarkEscapeWithReplacer() {
        benchmarkEscape(EscapeTest::escapeWithReplacer);
    }

    @Test
    void testUnescape() {
        Object[][] cases = {
            {"", "", null},
            {"x", "x", null},
            {"~0", "~", null},
            {"~1", "/", null},
            {"~0~1", "~/", null},
            {"~1~0", "/~", null},
            {"x~1~0", "x/~", null},
            {"x~1y~0", "x/y~", null},
            {"~", "", Cause.SYNTAX},
            {"~~", "", Cause.SYNTAX},
            {"~1~", "", Cause.SYNTAX},
            {"~0~", "", Cause.SYNTAX},
            {"~a", "", Cause.SYNTAX},
            {"~a ", "", Cause.SYNTAX},
            {"a ~", "", Cause.SYNTAX},
            {"a ~ x", "", Cause.SYNTAX},
            {"a ~0 ~x", "", Cause.SYNTAX},
            // The following cases are declared as 'undefined result' in the Go documentation
            {"/", "", Cause.USAGE},
            {"~0 /", "", Cause.USAGE},
            {"~0 / ", "", Cause.USAGE},
        };
        for (Object[] test : cases) {
            String in = (String) test[0];
            if (test[2] == null) {
                assertEquals(test[1], Escape.unescapeString(in), in);
            } else {
                SentinelError err = assertThrows(SentinelError.class, () -> Escape.unescapeString(in), in);
                assertEquals(test[2], err.cause(), in);
            }

            byte[] bytes = in.getBytes(StandardCharsets.UTF_8);
            if (in.contains("/")) {
                SentinelError err = assertThrows(SentinelError.class, () -> Escape.unescape(bytes), in);
                assertEquals(Cause.USAGE, err.cause(), in);
            } else if (test[2] == null) {
                assertEquals(test[1], new String(Escape.unescape(bytes), StandardCharsets.UTF_8), in);
            } else {
                SentinelError err = assertThrows(SentinelError.class, () -> Escape.unescape(bytes), in);
                assertEquals(test[2], err.cause(), in);
            }
        }
    }
}
