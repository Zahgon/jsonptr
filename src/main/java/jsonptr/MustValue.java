// Copyright 2019-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.util.concurrent.Callable;

/** MustValue wraps a call to some jsonptr function which returns a value, to turn any error into a panic. */
public final class MustValue {
    private MustValue() {
    }

    /**
     * Returns the value of call. Any exception call throws is rethrown as an IllegalStateException
     * whose cause is that exception.
     */
    public static Object of(Callable<Object> call) {
        try {
            return call.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
