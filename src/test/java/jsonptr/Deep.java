// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;

/** Test helper: reflect.DeepEqual for the document model, telling raw containers from decoded ones. */
final class Deep {
    private Deep() {
    }

    static boolean equal(Object a, Object b) {
        if (a instanceof RawObject != b instanceof RawObject || a instanceof RawArray != b instanceof RawArray) {
            return false;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) {
                return false;
            }
            for (Object k : ma.keySet()) {
                if (!equal(ma.get(k), mb.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!equal(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    static void assertDeepEquals(Object expected, Object got, String message) {
        if (!equal(expected, got)) {
            fail(message + "\n  expected: " + expected + "\n       got: " + got);
        }
    }
}
