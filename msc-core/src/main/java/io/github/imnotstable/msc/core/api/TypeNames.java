package io.github.imnotstable.msc.core.api;

import java.util.ArrayList;
import java.util.List;

/** Turns JVM descriptors and generic signatures into short Java-like type names. */
final class TypeNames {
    private final String s;
    private int i;

    private TypeNames(String s) {
        this.s = s;
    }

    /** Parameter types then return type, from a method descriptor or signature. */
    static List<String> method(String signature) {
        TypeNames p = new TypeNames(signature);
        p.skipTypeParameters();
        List<String> out = new ArrayList<>();
        p.expect('(');
        while (p.peek() != ')') {
            out.add(p.type());
        }
        p.expect(')');
        out.add(p.type());
        return out;
    }

    /** A field descriptor or signature. */
    static String field(String signature) {
        return new TypeNames(signature).type();
    }

    /** {@code a/b/Outer$Inner} to {@code Outer.Inner}. */
    static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }

    private char peek() {
        return s.charAt(i);
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("bad signature " + s + " at " + i);
        }
        i++;
    }

    private void skipTypeParameters() {
        if (peek() != '<') {
            return;
        }
        i++;
        while (peek() != '>') {
            i = s.indexOf(':', i);
            while (peek() == ':') {
                i++;
                if (peek() != ':') {
                    type();
                }
            }
        }
        i++;
    }

    private String type() {
        char c = s.charAt(i++);
        switch (c) {
            case 'B': return "byte";
            case 'C': return "char";
            case 'D': return "double";
            case 'F': return "float";
            case 'I': return "int";
            case 'J': return "long";
            case 'S': return "short";
            case 'Z': return "boolean";
            case 'V': return "void";
            case '[': return type() + "[]";
            case 'T': {
                int end = s.indexOf(';', i);
                String name = s.substring(i, end);
                i = end + 1;
                return name;
            }
            case 'L': {
                StringBuilder name = new StringBuilder();
                int start = i;
                while (true) {
                    char d = s.charAt(i);
                    if (d == '<') {
                        name.append(simple(s.substring(start, i)));
                        i++;
                        name.append('<');
                        boolean first = true;
                        while (peek() != '>') {
                            if (!first) {
                                name.append(", ");
                            }
                            first = false;
                            char w = peek();
                            if (w == '*') {
                                i++;
                                name.append('?');
                            } else if (w == '+') {
                                i++;
                                name.append("? extends ").append(type());
                            } else if (w == '-') {
                                i++;
                                name.append("? super ").append(type());
                            } else {
                                name.append(type());
                            }
                        }
                        i++;
                        name.append('>');
                        start = i;
                    } else if (d == '.') {
                        name.append(simple(s.substring(start, i))).append('.');
                        i++;
                        start = i;
                    } else if (d == ';') {
                        name.append(simple(s.substring(start, i)));
                        i++;
                        return name.toString();
                    } else {
                        i++;
                    }
                }
            }
            default:
                throw new IllegalArgumentException("bad signature " + s + " at " + (i - 1));
        }
    }
}
