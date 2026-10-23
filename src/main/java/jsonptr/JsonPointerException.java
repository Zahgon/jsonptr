// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/**
 * Base of the structured errors of the library: the kind of error, the substring of the original
 * pointer where the error occurred and the sentinel it wraps.
 */
public abstract class JsonPointerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private String pointer;
    private final Cause cause;

    JsonPointerException(String pointer, Cause cause) {
        this(pointer, cause, null);
    }

    JsonPointerException(String pointer, Cause cause, Throwable wrapped) {
        super(null, wrapped, false, false);
        this.pointer = pointer;
        this.cause = cause;
    }

    /** Returns the error kind: BadPointerError, PtrError, DocumentError or Sentinel. */
    public abstract String kind();

    /** Returns the substring of the original pointer where the error occurred. */
    public String pointer() {
        return pointer;
    }

    /**
     * Returns the sentinel error this error wraps (the Go Unwrap); a DocumentError for a JSON
     * decoding error also carries that {@link Json.DecodeException} as {@link #getCause()}.
     */
    public Cause cause() {
        return cause;
    }

    /** Prefixes the location with base, for an error located relatively to a child value. */
    void rebase(String base) {
        pointer = base + pointer;
    }

    @Override
    public String getMessage() {
        if (pointer.isEmpty()) {
            return kind() + ": " + cause.label();
        }
        return kind() + " at \"" + pointer + "\": " + cause.label();
    }
}
