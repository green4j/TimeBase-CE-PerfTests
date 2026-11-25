package io.github.green4j.timebase.perftests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --name=value} and {@code --flag} command line options; an option may repeat.
 */
final class Args {

    private final Map<String, List<String>> values;

    private Args(Map<String, List<String>> values) {
        this.values = values;
    }

    static Args parse(String[] args) {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--"))
                throw new IllegalArgumentException("Expected --name=value, got " + arg);
            final int eq = arg.indexOf('=');
            final String name = eq < 0 ? arg.substring(2) : arg.substring(2, eq);
            final String value = eq < 0 ? "true" : arg.substring(eq + 1);
            values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return new Args(values);
    }

    /** Rejects an option the command does not take, so that a misspelled one is not silently ignored. */
    Args only(String command, List<String> allowed) {
        for (String name : values.keySet()) {
            if (!allowed.contains(name))
                throw new IllegalArgumentException("Unknown option --" + name + " of " + command + "; options " + allowed);
        }
        return this;
    }

    boolean has(String name) {
        return values.containsKey(name);
    }

    /** Every value of a repeated option, in order. */
    List<String> all(String name) {
        return values.getOrDefault(name, List.of());
    }

    String string(String name) {
        final String value = last(name);
        if (value == null)
            throw new IllegalArgumentException(name + " is required");
        return value;
    }

    String string(String name, String defaultValue) {
        final String value = last(name);
        return value != null ? value : defaultValue;
    }

    int integer(String name) {
        return Integer.parseInt(string(name));
    }

    int integer(String name, int defaultValue) {
        final String value = last(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    long longValue(String name, long defaultValue) {
        final String value = last(name);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    boolean bool(String name, boolean defaultValue) {
        final String value = last(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    <E extends Enum<E>> E enumValue(String name, Class<E> type, E defaultValue) {
        final String value = last(name);
        return value != null ? Enum.valueOf(type, value) : defaultValue;
    }

    private String last(String name) {
        final List<String> list = values.get(name);
        return list == null ? null : list.get(list.size() - 1);
    }
}
