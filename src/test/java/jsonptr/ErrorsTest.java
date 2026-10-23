// Copyright 2019-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Port of errors_test.go: every error of the library unwraps to the sentinel it wraps. */
class ErrorsTest {
    @Test
    void testWrapper() {
        Json.DecodeException syntax = new Json.DecodeException("syntax", 0);
        List<JsonPointerException> wrappers = List.of(
                new PtrError("/1", Cause.INDEX),
                new PtrError("/a", Cause.PROPERTY),
                new BadPointerError("azerty", Cause.SYNTAX),
                new DocumentError("", Cause.NOT_CONTAINER),
                new DocumentError("", syntax));
        List<Cause> unwrapped = List.of(Cause.INDEX, Cause.PROPERTY, Cause.SYNTAX, Cause.NOT_CONTAINER, Cause.JSON_SYNTAX);
        for (int i = 0; i < wrappers.size(); i++) {
            assertEquals(unwrapped.get(i), wrappers.get(i).cause(), wrappers.get(i).kind());
        }
        assertSame(syntax, wrappers.get(4).getCause());
    }
}
