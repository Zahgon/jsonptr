// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/** The sentinel errors of the library; every {@link JsonPointerException} wraps one of them. */
public enum Cause {
    /** Invalid JSON pointer (ErrSyntax). */
    SYNTAX("syntax"),
    /** Invalid array index (ErrIndex). */
    INDEX("index"),
    /** Property not found (ErrProperty). */
    PROPERTY("property"),
    /** Can't delete root (ErrDeleteRoot). */
    DELETE_ROOT("delete-root"),
    /** A value on the path is not an object or an array (the error of a {@link DocumentError}). */
    NOT_CONTAINER("not-container"),
    /** Can't go up from root (ErrRoot). */
    ROOT("root"),
    /** Invalid use of {@link Escape#unescapeString} on a string with '/' (ErrUsage). */
    USAGE("usage"),
    /** A JSON syntax error in a raw or streamed document (the error of a {@link DocumentError}). */
    JSON_SYNTAX("json-syntax"),
    /** A raw or streamed document nested deeper than {@link Json#MAX_DEPTH}. */
    JSON_DEPTH("json-depth"),
    /** A number of a raw or streamed document out of the binary64 range. */
    JSON_NUMBER_RANGE("json-number-range"),
    /** The end of a streamed document reached where a value starts (io.EOF). */
    JSON_EOF("json-eof"),
    /** The end of a streamed document reached inside a value (io.ErrUnexpectedEOF). */
    JSON_UNEXPECTED_EOF("json-unexpected-eof");

    private final String label;

    /** Returns the cause of a DocumentError wrapping the decoding error e. */
    static Cause of(Json.DecodeException e) {
        return switch (e.reason()) {
            case "syntax" -> JSON_SYNTAX;
            case "depth" -> JSON_DEPTH;
            case "number-range" -> JSON_NUMBER_RANGE;
            case "eof" -> JSON_EOF;
            default -> JSON_UNEXPECTED_EOF;
        };
    }

    Cause(String label) {
        this.label = label;
    }

    /** Returns the name the command line prints for this cause. */
    public String label() {
        return label;
    }
}
