package io.github.imnotstable.msc.templates;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces {@code {{key}}} placeholders. An unknown key is an error, so typos never reach a scaffolded project. */
final class TemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

    private TemplateRenderer() {}

    static String render(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("Unknown template placeholder {{" + m.group(1) + "}}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
