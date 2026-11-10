package io.github.imnotstable.msc.core.api;

import java.util.List;

/**
 * The public face of one class, already rendered for display.
 *
 * @param name       fully qualified, with dots for nested classes
 * @param simpleName name without the package; nested classes keep their outer class, as in {@code Map.Entry}
 * @param kind       class, interface, enum, annotation or record
 * @param supertypes internal names of the superclass and interfaces, as fully qualified dotted names
 * @param constants  enum constant names
 * @param members    public and protected fields and methods
 */
public record ClassInfo(String name, String simpleName, String kind, List<String> supertypes, boolean deprecated,
                        List<String> constants, List<Member> members) {
    /** @param text for example {@code static Component text(String content)} */
    public record Member(String name, String text, boolean deprecated, boolean method) {
    }
}
