// Copyright 2016-2026 Olivier Mengué. All rights reserved.
// Use of this source code is governed by the Apache 2.0 license that
// can be found in the LICENSE file.

package jsonptr;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The command line: document commands (get, set, delete, in, each also over a raw or a streamed
 * document) and pointer commands (parse, pointer, escape, append-escape, unescape). Prints one JSON
 * object on standard output and exits 0 on success, 1 on a library error and 2 on a usage error.
 * A library error caused by a JSON syntax, depth or number-range error also prints the offset of that
 * error.
 */
public final class Main {
    private static final Pattern INDEX = Pattern.compile("[+-]?[0-9]+");
    private static final Map<String, Integer> DOCUMENT_ARGS = Map.of("get", 3, "set", 4, "delete", 3, "in", 3);
    private static final List<String> FORMS = List.of("", "-raw", "-stream");

    private Main() {
    }

    /** A usage error: the command exits 2. */
    private static final class UsageError extends Exception {
        private static final long serialVersionUID = 1L;

        private final String input;
        private final String reason;
        private final long offset;

        UsageError(String input, String reason) {
            this(input, reason, -1);
        }

        UsageError(String input, String reason, long offset) {
            super(input + ": " + reason, null, false, false);
            this.input = input;
            this.reason = reason;
            this.offset = offset;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        PrintStream out = new PrintStream(System.out, false);
        // The command runs on a worker thread: a failure it does not report ends the worker with no
        // output, and the process exits 3 like the reference driver when it recovers from a panic.
        int[] status = {3};
        Thread worker = new Thread(() -> status[0] = run(args, out), "jsonptr");
        worker.start();
        worker.join();
        out.flush();
        System.exit(status[0]);
    }

    static int run(String[] args, PrintStream out) {
        Map<String, Object> result = new LinkedHashMap<>();
        int status = 0;
        try {
            result.put("ok", Boolean.TRUE);
            command(args, result);
        } catch (UsageError e) {
            result.clear();
            result.put("ok", Boolean.FALSE);
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("input", e.input);
            usage.put("reason", e.reason);
            if (e.offset >= 0) {
                usage.put("offset", e.offset);
            }
            result.put("usage", usage);
            status = 2;
        } catch (JsonPointerException e) {
            result.clear();
            result.put("ok", Boolean.FALSE);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("kind", e.kind());
            error.put("pointer", e.pointer());
            error.put("cause", e.cause().label());
            if ((e.cause() == Cause.JSON_SYNTAX || e.cause() == Cause.JSON_DEPTH
                    || e.cause() == Cause.JSON_NUMBER_RANGE) && e.getCause() instanceof Json.DecodeException decode) {
                error.put("offset", decode.offset());
            }
            result.put("error", error);
            status = 1;
        }
        out.writeBytes(Json.encode(result));
        out.write('\n');
        return status;
    }

    private static void command(String[] args, Map<String, Object> result) throws UsageError {
        if (args.length == 0) {
            throw new UsageError("ARGS", "arguments");
        }
        switch (args[0]) {
            case "parse" -> {
                arity(args, 2);
                Pointer p = Pointer.parse(args[1]);
                result.put("tokens", p.tokens());
                result.put("string", p.toString());
            }
            case "escape" -> {
                arity(args, 2);
                result.put("value", Escape.escapeString(args[1]));
            }
            case "append-escape" -> {
                arity(args, 3);
                result.put("value", Escape.appendEscape(new StringBuilder(args[1]), args[2]).toString());
            }
            case "unescape" -> {
                arity(args, 2);
                result.put("value", Escape.unescapeString(args[1]));
            }
            case "pointer" -> {
                if (args.length < 2) {
                    throw new UsageError("ARGS", "arguments");
                }
                pointer(args, result);
            }
            default -> document(args, result);
        }
    }

    private static void arity(String[] args, int n) throws UsageError {
        if (args.length != n) {
            throw new UsageError("ARGS", "arguments");
        }
    }

    private static Object decode(String input, byte[] raw) throws UsageError {
        try {
            return Json.parse(raw);
        } catch (Json.DecodeException e) {
            // The usage object carries the offset of a syntax or depth error only
            boolean located = e.reason().equals("syntax") || e.reason().equals("depth");
            throw new UsageError(input, e.reason(), located ? e.offset() : -1);
        }
    }

    private static void document(String[] args, Map<String, Object> result) throws UsageError {
        if (args.length < 3) {
            throw new UsageError("ARGS", "arguments");
        }
        int dash = args[0].indexOf('-');
        String op = dash < 0 ? args[0] : args[0].substring(0, dash);
        String form = dash < 0 ? "" : args[0].substring(dash);
        Integer want = DOCUMENT_ARGS.get(op);
        boolean known = want != null && FORMS.contains(form);
        String ptr = args[2];
        byte[] raw;
        try {
            raw = Files.readAllBytes(Path.of(args[1]));
        } catch (IOException | InvalidPathException e) {
            throw new UsageError("DOC", "unreadable");
        }
        Object doc;
        if (known && form.equals("-raw")) {
            doc = new RawMessage(raw);
        } else if (known && form.equals("-stream")) {
            doc = Json.newDecoder(raw);
        } else {
            doc = decode("DOC", raw);
        }
        if (!known || want != args.length) {
            throw new UsageError("ARGS", "arguments");
        }
        JsonPtr.Ref ref = new JsonPtr.Ref(doc);
        switch (op) {
            case "get" -> result.put("value", JsonPtr.get(doc, ptr));
            case "in" -> result.put("value", Pointer.parse(ptr).in(doc));
            case "set" -> {
                byte[] text = args[3].getBytes(StandardCharsets.UTF_8);
                Object value = form.equals("-stream") ? Json.newDecoder(text) : decode("VALUE", text);
                JsonPtr.set(ref, ptr, value);
                result.put("document", ref.get());
            }
            default -> {
                Object removed = JsonPtr.delete(ref, ptr);
                result.put("document", ref.get());
                result.put("value", removed);
            }
        }
    }

    private static void pointer(String[] args, Map<String, Object> result) throws UsageError {
        List<String> moves = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String op = args[i];
            boolean valid = op.equals("up") || op.equals("pop") || op.startsWith("property:")
                    || (op.startsWith("index:") && validIndex(op.substring("index:".length())));
            if (!valid) {
                throw new UsageError("ARGS", "arguments");
            }
            moves.add(op);
        }
        Pointer ptr = Pointer.parse(args[1]);
        List<String> popped = new ArrayList<>();
        for (String op : moves) {
            if (op.equals("up")) {
                ptr.up();
            } else if (op.equals("pop")) {
                popped.add(ptr.pop());
            } else if (op.startsWith("property:")) {
                ptr.property(op.substring("property:".length()));
            } else {
                ptr.index(Long.parseLong(op.substring("index:".length())));
            }
        }
        result.put("string", ptr.toString());
        result.put("root", ptr.isRoot());
        result.put("popped", popped);
        String leafName = null;
        String leafIndex = null;
        if (!ptr.isRoot()) {
            leafName = ptr.leafName();
            try {
                leafIndex = Long.toString(ptr.leafIndex());
            } catch (SentinelError e) {
                leafIndex = null;
            }
        }
        result.put("leaf_name", leafName);
        result.put("leaf_index", leafIndex);
    }

    private static boolean validIndex(String n) {
        if (!INDEX.matcher(n).matches()) {
            return false;
        }
        try {
            Long.parseLong(n);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
