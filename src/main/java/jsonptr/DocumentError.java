// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/**
 * DocumentError signals a document that can't be processed by this library: the value found at
 * {@link #pointer()} is not an object or an array but the pointer goes deeper, or a raw or streamed
 * part of the document does not decode. The pointer is empty when the location is not known or not
 * relevant (JSON syntax errors for example).
 */
public final class DocumentError extends JsonPointerException {
    private static final long serialVersionUID = 1L;

    /** Creates an error for the value located at ptr. */
    public DocumentError(String ptr, Cause cause) {
        super(ptr, cause);
    }

    /** Creates an error for the decoding error err, located at ptr; its cause follows err's reason. */
    public DocumentError(String ptr, Json.DecodeException err) {
        super(ptr, Cause.of(err), err);
    }

    @Override
    public String kind() {
        return "DocumentError";
    }
}
