// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/** BadPointerError signals JSON Pointer parsing errors (and the deletion of the root). */
public final class BadPointerError extends JsonPointerException {
    private static final long serialVersionUID = 1L;

    /** Creates an error located at badPtr, the substring of the original pointer where it occurred. */
    public BadPointerError(String badPtr, Cause cause) {
        super(badPtr, cause);
    }

    @Override
    public String kind() {
        return "BadPointerError";
    }
}
