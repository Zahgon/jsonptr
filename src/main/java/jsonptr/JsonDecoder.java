// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

/**
 * JsonDecoder is a subset of the interface of Go's encoding/json Decoder, for streamed decoding. It
 * can be used as an input to {@link JsonPtr#get}. {@link Json#newDecoder} returns the
 * implementation that reads like Go's json.Decoder. Errors are {@link Json.DecodeException}s.
 */
public interface JsonDecoder {
    /**
     * Returns the next JSON token of the stream: a Character for the delimiters '[', ']', '{' and
     * '}', else a String, Double, Boolean or null. At the end of the input it throws the eof error.
     */
    Object token();

    /** Reports whether there is another element in the current array or object being parsed. */
    boolean more();

    /** Reads the next whole JSON value and returns it decoded. */
    Object decode();

    /** Reads the next whole JSON value and returns its raw bytes. */
    RawMessage decodeRaw();
}
