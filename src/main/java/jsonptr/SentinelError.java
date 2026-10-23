// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/**
 * A bare sentinel error, raised where the Go library returns (or panics with) an Err* value that
 * is not wrapped in a located error. Its pointer is always empty.
 */
public final class SentinelError extends JsonPointerException {
    private static final long serialVersionUID = 1L;

    /** Creates the bare sentinel error for cause. */
    public SentinelError(Cause cause) {
        super("", cause);
    }

    @Override
    public String kind() {
        return "Sentinel";
    }
}
