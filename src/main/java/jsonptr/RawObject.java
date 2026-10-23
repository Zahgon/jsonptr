// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A partially decoded JSON object (Go's map[string]json.RawMessage): the members are kept raw.
 */
public final class RawObject extends LinkedHashMap<String, RawMessage> {
    private static final long serialVersionUID = 1L;

    /** Creates an empty partially decoded object. */
    public RawObject() {
    }

    /** Creates a partially decoded object holding the given members. */
    public RawObject(Map<String, RawMessage> members) {
        super(members);
    }
}
