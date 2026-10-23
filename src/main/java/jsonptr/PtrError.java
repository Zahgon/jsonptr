// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/** PtrError signals JSON Pointer navigation errors: an index or a property that is not found. */
public final class PtrError extends JsonPointerException {
    private static final long serialVersionUID = 1L;

    /** Creates an error located at ptr, the substring of the original pointer where it occurred. */
    public PtrError(String ptr, Cause cause) {
        super(ptr, cause);
    }

    @Override
    public String kind() {
        return "PtrError";
    }
}
