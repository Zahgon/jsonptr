// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * RawMessage is a raw encoded JSON value (Go's json.RawMessage). It can be used to delay JSON
 * decoding: {@link JsonPtr} decodes a RawMessage found in a document lazily, one layer at a time.
 * Encoding writes its bytes as they are, without the whitespace outside strings.
 */
public final class RawMessage {
    private final byte[] bytes;

    /** Creates a raw value holding a copy of bytes. */
    public RawMessage(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    /** Creates a raw value holding the UTF-8 encoding of text. */
    public RawMessage(String text) {
        this.bytes = text.getBytes(StandardCharsets.UTF_8);
    }

    /** Returns a copy of the raw bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RawMessage o && Arrays.equals(bytes, o.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Returns the raw bytes decoded as UTF-8. */
    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
