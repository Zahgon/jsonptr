// Copyright 2019-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static jsonptr.GetTester.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UtilTest {
    @Test
    void testMustValue() {
        // Should not raise exception
        assertEquals(42.0, MustValue.of(() -> JsonPtr.get(list(42.0), "/0")));

        // Should raise exception
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MustValue.of(() -> JsonPtr.get(null, "z")));
        assertTrue(e.getCause() instanceof BadPointerError, "BadPointerError expected as cause but got " + e.getCause());
    }
}
