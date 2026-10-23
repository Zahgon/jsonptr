# jsonptr - JSON Pointer ([RFC 6901](https://tools.ietf.org/html/rfc6901)) for Java

[![codecov](https://codecov.io/gh/dolmen-go/jsonptr/branch/master/graph/badge.svg?token=tdH8IAOTo7)](https://codecov.io/gh/dolmen-go/jsonptr)

A Java 17 library (package `jsonptr`) and command line, ported from the Go library
`github.com/dolmen-go/jsonptr`.

## Features

Goals:

1. First-class interface
    * Short and complete
    * Structured errors, not just strings: `BadPointerError`, `PtrError`, `DocumentError` and
      `SentinelError`, each with its `kind()`, the `pointer()` prefix where the error occurred and
      its `cause()`
    * Working at JSON data model level (a tree of `List` and `Map` values, as decoded by `Json.parse`)
    * Raw (`RawMessage`), streamed (`JsonDecoder`) and partially decoded (`RawObject`, `RawArray`)
      documents, decoded lazily: only the containers on the path are decoded
2. Correctness
    * Full test suite
    * Reject invalid escapes (regexp `/~[^01]/`)
    * Allow any JSON value as leaf node
    * Allow any JSON value as root (not just an object)
    * Allow to get/set the root of the document with the empty pointer `""`

## Library

```java
import jsonptr.Json;
import jsonptr.JsonPtr;
import jsonptr.Pointer;

// JSON: { "a": [ 1 ] }
Object document = Json.parse("{\"a\":[1]}");

Object val = JsonPtr.get(document, "/a/0");             // 1.0
JsonPtr.Ref doc = new JsonPtr.Ref(document);
JsonPtr.set(doc, "/a/-", "x");                          // doc.get() is {"a":[1,"x"]}
Object removed = JsonPtr.delete(doc, "/a/0");           // 1.0

// Streamed: only the tokens on the path are read
Object b = JsonPtr.get(Json.newDecoder("{\"a\":1,\"b\":[true]}"), "/b/0");   // true
// Raw: the containers on the path are decoded one layer each, the rest stays raw
JsonPtr.Ref raw = new JsonPtr.Ref(new RawMessage("{\"a\":{\"b\":1},\"c\":[2]}"));
JsonPtr.set(raw, "/a/x", 3);                            // {"a":{"b":1,"x":3},"c":[2]}, "c" still raw

Pointer ptr = Pointer.parse("/a~1b/0");                 // tokens ["a/b", "0"]
ptr.up().property("c").index(2);                        // "/a~1b/c/2"
```

* `JsonPtr.get`, `JsonPtr.set` and `JsonPtr.delete` read and modify a document (set and delete
  through a `JsonPtr.Ref`, as they may replace the root).
* `Pointer` is a mutable parsed pointer: `parse`, `mustParse`, `property`, `index`, `up`, `pop`,
  `copy`, `grow`, `leafName`, `leafIndex`, `in`, `set`, `delete`, `marshalText`, `unmarshalText`.
* `Escape.escapeString`, `Escape.appendEscape`, `Escape.unescapeString` and `Escape.unescape` (bytes)
  handle reference tokens.
* `MustValue.of` turns any error of a call into an unchecked exception.
* `Json.parse` decodes JSON text with the rules of Go's `encoding/json` (binary64 numbers, at most
  10000 nested containers, U+FFFD for invalid UTF-8 and lone surrogates); `Json.newDecoder` reads a
  stream like Go's `json.Decoder`; `Json.write` and `Json.encode` encode like Go's `json.Encoder`.

## Command line

Build with `mvn -B package`, then:

```
java -jar target/jsonptr.jar get    DOC POINTER
java -jar target/jsonptr.jar set    DOC POINTER VALUE
java -jar target/jsonptr.jar delete DOC POINTER
java -jar target/jsonptr.jar in     DOC POINTER
java -jar target/jsonptr.jar parse  POINTER
java -jar target/jsonptr.jar pointer POINTER [up|pop|property:NAME|index:N]...
java -jar target/jsonptr.jar escape NAME
java -jar target/jsonptr.jar append-escape PREFIX NAME
java -jar target/jsonptr.jar unescape TOKEN
```

`get-raw`, `set-raw`, `delete-raw` and `in-raw` hand the DOC file to the library as a `RawMessage`;
`get-stream`, `set-stream`, `delete-stream` and `in-stream` as a `JsonDecoder` (and `set-stream`
gives VALUE as a `JsonDecoder` too).

Each command prints one JSON object and exits 0 on success, 1 on a library error
(`{"ok":false,"error":{"kind":...,"pointer":...,"cause":...}}`, with the `offset` of the JSON error when
the cause is `json-syntax`, `json-depth` or `json-number-range`) and 2 on a usage error
(`{"ok":false,"usage":{"input":...,"reason":...}}`, with the byte `offset` of a syntax or depth error).

## Tests

`mvn -B verify` builds the library and runs the JUnit 5 suite.
`mvn -B verify -Pcoverage` also writes a JaCoCo coverage report to `target/site/jacoco/jacoco.xml`,
which the GitHub workflow uploads to Codecov.

## Release helpers

* `make mvn-version` prints the version of the newest commit that changed `pom.xml` or `src/main/java`,
  `0.0.0-<commit time in UTC as YYYYMMDDHHMMSS>-<12-digit commit hash>`.
* `make mvn-get` prints the `mvn dependency:get` command that fetches the library at that version.

## License

Copyright 2016-2020 Olivier Mengué

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
