// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A partially decoded JSON array (Go's []json.RawMessage): the elements are kept raw.
 */
public final class RawArray extends ArrayList<RawMessage> {
    private static final long serialVersionUID = 1L;

    /** Creates an empty partially decoded array. */
    public RawArray() {
    }

    /** Creates a partially decoded array holding the given elements. */
    public RawArray(Collection<RawMessage> elements) {
        super(elements);
    }
}
