package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Typed access to a tool call's arguments, with error messages a model can act on. */
public final class Args {
    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public boolean has(String name) {
        return json.has(name) && !json.get(name).isJsonNull();
    }

    /**
     * A one-line string argument. Control characters (NUL, a line break, a bell) are refused here, for every argument
     * of every tool, so no value can break a one-line answer that quotes it or reach a file name; {@link #text} is
     * the one exception, for arguments that are several lines.
     */
    public String str(String name) {
        return checked(name, raw(name), false);
    }

    /** A string argument of several lines, such as build_batch's steps: line breaks and tabs, no other control. */
    public String text(String name) {
        return checked(name, raw(name), true);
    }

    /**
     * A string argument of at most {@code maxLines} lines, such as pack's description, which Minecraft shows on two
     * lines: line breaks ({@code \r\n} becomes {@code \n}), no other control. {@code fallback} when missing.
     */
    public String lines(String name, String fallback, int maxLines) {
        if (!has(name)) {
            return fallback;
        }
        String value = raw(name).replace("\r\n", "\n");
        int lines = 1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                lines++;
            } else if (Character.isISOControl(c)) {
                throw new ToolException(String.format("argument %s has a control character (U+%04X); only a line break"
                        + " may be in it", name, (int) c));
            }
        }
        if (lines > maxLines) {
            throw new ToolException("argument " + name + " has " + lines + " lines; at most " + maxLines + " are shown");
        }
        return value;
    }

    private String raw(String name) {
        if (!has(name)) {
            throw new ToolException("missing argument: " + name);
        }
        JsonElement e = json.get(name);
        if (!e.isJsonPrimitive()) {
            throw new ToolException("argument " + name + " must be a string");
        }
        return e.getAsString();
    }

    private static String checked(String name, String value, boolean lines) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(lines && (c == '\n' || c == '\r' || c == '\t'))) {
                throw new ToolException(String.format("argument %s has a control character (U+%04X)%s", name, (int) c,
                        lines ? "; only line breaks and tabs may be in it" : "; give it on one line"));
            }
        }
        return value;
    }

    public String str(String name, String fallback) {
        return has(name) ? str(name) : fallback;
    }

    /** A path argument, checked and resolved by {@code rule}; see {@link PathArg}. */
    public java.nio.file.Path path(String name, PathArg rule) {
        return rule.resolve(name, str(name));
    }

    /** Like {@link #path(String, PathArg)}, with {@code fallback} when the argument is missing. */
    public java.nio.file.Path path(String name, String fallback, PathArg rule) {
        return rule.resolve(name, str(name, fallback));
    }

    /** A one-value argument as text; an object or array is refused as not being {@code what}. */
    private String scalar(String name, String what) {
        if (has(name) && !json.get(name).isJsonPrimitive()) {
            throw new ToolException("argument " + name + " must be " + what);
        }
        return str(name);
    }

    public int integer(String name) {
        String s = scalar(name, "an integer").trim();
        try {
            double d = Double.parseDouble(s);
            if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
                throw new NumberFormatException(s);
            }
            return (int) d;
        } catch (NumberFormatException e) {
            throw new ToolException("argument " + name + " must be an integer");
        }
    }

    public int integer(String name, int fallback) {
        return has(name) ? integer(name) : fallback;
    }

    /**
     * An integer from {@code min} to {@code max}, {@code fallback} when missing; anything outside is refused with the
     * range, never clamped silently, so a caller never asks for more than it gets.
     */
    public int integer(String name, int fallback, int min, int max) {
        int value = integer(name, fallback);
        if (value < min || value > max) {
            throw new ToolException("argument " + name + " must be " + min + " to " + max + ", not " + value);
        }
        return value;
    }

    /** A finite number; {@code "0.5"} is accepted like {@code 0.5}. */
    public double number(String name) {
        String s = scalar(name, "a number").trim();
        try {
            double d = Double.parseDouble(s);
            if (!Double.isFinite(d)) {
                throw new NumberFormatException(name);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new ToolException("argument " + name + " must be a number");
        }
    }

    public double number(String name, double fallback) {
        return has(name) ? number(name) : fallback;
    }

    public boolean bool(String name, boolean fallback) {
        if (!has(name)) {
            return fallback;
        }
        String s = scalar(name, "true or false");
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        throw new ToolException("argument " + name + " must be true or false");
    }

    /** An array of strings; a single string is accepted as a one-element list. Missing means empty. */
    public List<String> strings(String name) {
        List<String> out = new ArrayList<>();
        if (!has(name)) {
            return out;
        }
        JsonElement e = json.get(name);
        if (e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                if (!item.isJsonPrimitive()) {
                    throw new ToolException("argument " + name + " must be an array of strings");
                }
                out.add(checked(name, item.getAsString(), false));
            }
        } else if (e.isJsonPrimitive()) {
            out.add(checked(name, e.getAsString(), false));
        } else {
            throw new ToolException("argument " + name + " must be an array of strings");
        }
        return out;
    }
}
