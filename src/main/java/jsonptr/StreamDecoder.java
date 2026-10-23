// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The {@link JsonDecoder} of {@link Json#newDecoder}, with the observable behaviour of Go's
 * json.Decoder: the same tokens, the same errors and the same error offsets. A syntax error found
 * while reading a whole value is located by the bytes read as values so far, a misplaced token by
 * its position in the input. Once a value read fails, every later read fails the same way.
 */
final class StreamDecoder implements JsonDecoder {
    private static final int CHUNK = 4096;

    /** Where the reader stands in the token grammar. */
    private enum Place {
        TOP, ARRAY_FIRST, ARRAY_ELEMENT, AFTER_ELEMENT, OBJECT_FIRST, OBJECT_KEY, AFTER_KEY, MEMBER_VALUE,
        AFTER_MEMBER
    }

    private final InputStream in;
    private byte[] data = new byte[CHUNK];
    private int count;
    private int pos;
    // Input bytes discarded from the front of data.
    private long dropped;
    // Bytes taken as part of whole values so far: the base of value syntax error offsets.
    private long valueBytes;
    private Json.DecodeException broken;

    private Place place = Place.TOP;
    private final Deque<Place> outer = new ArrayDeque<>();

    StreamDecoder(InputStream in) {
        this.in = in;
    }

    @Override
    public Object decode() {
        int begin = takeValue();
        try {
            return Json.decodeValue(Arrays.copyOfRange(data, begin, pos));
        } finally {
            valueDone();
        }
    }

    @Override
    public RawMessage decodeRaw() {
        int begin = takeValue();
        while (JsonSyntax.isSpace(data[begin] & 0xff)) {
            begin++;
        }
        RawMessage raw = new RawMessage(Arrays.copyOfRange(data, begin, pos));
        valueDone();
        return raw;
    }

    @Override
    public Object token() {
        while (true) {
            int c = nextNonSpace();
            if (c == '[' || c == '{') {
                if (!valueExpected()) {
                    throw misplaced();
                }
                pos++;
                outer.push(place);
                place = c == '[' ? Place.ARRAY_FIRST : Place.OBJECT_FIRST;
                return (char) c;
            }
            if (c == ']' || c == '}') {
                boolean fits = c == ']' ? place == Place.ARRAY_FIRST || place == Place.AFTER_ELEMENT
                        : place == Place.OBJECT_FIRST || place == Place.AFTER_MEMBER;
                if (!fits) {
                    throw misplaced();
                }
                pos++;
                place = outer.pop();
                valueDone();
                return (char) c;
            }
            if (c == ':') {
                if (place != Place.AFTER_KEY) {
                    throw misplaced();
                }
                pos++;
                place = Place.MEMBER_VALUE;
            } else if (c == ',') {
                if (place == Place.AFTER_ELEMENT) {
                    place = Place.ARRAY_ELEMENT;
                } else if (place == Place.AFTER_MEMBER) {
                    place = Place.OBJECT_KEY;
                } else {
                    throw misplaced();
                }
                pos++;
            } else if (c == '"' && (place == Place.OBJECT_FIRST || place == Place.OBJECT_KEY)) {
                return key();
            } else {
                if (!valueExpected()) {
                    throw misplaced();
                }
                return decode();
            }
        }
    }

    // An object key is read as a whole value of its own, outside the token grammar.
    private Object key() {
        Place saved = place;
        place = Place.TOP;
        Object name;
        try {
            name = decode();
        } finally {
            place = saved;
        }
        place = Place.AFTER_KEY;
        return name;
    }

    @Override
    public boolean more() {
        int c;
        try {
            c = nextNonSpace();
        } catch (Json.DecodeException e) {
            return false;
        }
        return c != ']' && c != '}';
    }

    // Reads the next whole value into data and returns where it starts (its leading space included).
    private int takeValue() {
        if (broken != null) {
            throw broken;
        }
        if (place == Place.AFTER_ELEMENT || place == Place.AFTER_KEY) {
            int separator = place == Place.AFTER_ELEMENT ? ',' : ':';
            if (nextNonSpace() != separator) {
                throw misplaced();
            }
            pos++;
            place = place == Place.AFTER_ELEMENT ? Place.ARRAY_ELEMENT : Place.MEMBER_VALUE;
        }
        if (!valueExpected()) {
            throw misplaced();
        }
        boolean atEnd = false;
        while (true) {
            int end;
            try {
                end = JsonSyntax.valueEnd(data, pos, count, atEnd, valueBytes);
            } catch (Json.DecodeException e) {
                broken = e;
                throw e;
            }
            if (end != JsonSyntax.INCOMPLETE) {
                int begin = pos;
                valueBytes += end - begin;
                pos = end;
                return begin;
            }
            if (atEnd) {
                broken = new Json.DecodeException(onlySpaceLeft() ? "eof" : "unexpected-eof", -1);
                throw broken;
            }
            atEnd = !fill();
        }
    }

    private boolean valueExpected() {
        return place == Place.TOP || place == Place.ARRAY_FIRST || place == Place.ARRAY_ELEMENT
                || place == Place.MEMBER_VALUE;
    }

    // After a whole value or a closed container, a separator is expected next.
    private void valueDone() {
        if (place == Place.ARRAY_FIRST || place == Place.ARRAY_ELEMENT) {
            place = Place.AFTER_ELEMENT;
        } else if (place == Place.MEMBER_VALUE) {
            place = Place.AFTER_MEMBER;
        }
    }

    private Json.DecodeException misplaced() {
        return new Json.DecodeException("syntax", dropped + pos);
    }

    // Moves pos to the next byte that is not space and returns it; at the end of the input, eof.
    private int nextNonSpace() {
        while (true) {
            for (int i = pos; i < count; i++) {
                int c = data[i] & 0xff;
                if (!JsonSyntax.isSpace(c)) {
                    pos = i;
                    return c;
                }
            }
            if (!fill()) {
                throw new Json.DecodeException("eof", -1);
            }
        }
    }

    private boolean onlySpaceLeft() {
        for (int i = pos; i < count; i++) {
            if (!JsonSyntax.isSpace(data[i] & 0xff)) {
                return false;
            }
        }
        return true;
    }

    // Reads more input after the unread bytes; returns false at the end of the input.
    private boolean fill() {
        if (pos > 0) {
            dropped += pos;
            count -= pos;
            System.arraycopy(data, pos, data, 0, count);
            pos = 0;
        }
        if (count == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        int n;
        try {
            n = in.read(data, count, data.length - count);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (n < 0) {
            return false;
        }
        count += n;
        return true;
    }
}
