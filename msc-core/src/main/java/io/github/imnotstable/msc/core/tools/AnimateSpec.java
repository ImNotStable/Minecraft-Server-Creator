package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.ToolException;

/**
 * The parsed texture {@code animate} value. Each form takes exactly the parts it reads, so text it would not read is
 * refused instead of ignored (#528): {@code scroll}, {@code scroll dx,dy} or {@code pulse}.
 *
 * @param pulse true for pulse, false for scroll
 * @param dx    scroll step right per frame
 * @param dy    scroll step down per frame
 */
record AnimateSpec(boolean pulse, int dx, int dy) {

    static final String FORMS = "animate must be scroll, \"scroll dx,dy\" or pulse";

    static AnimateSpec parse(String text) {
        String[] words = text.trim().split("\\s+", 2);
        String rest = words.length > 1 ? words[1] : null;
        return switch (words[0]) {
            case "pulse" -> {
                if (rest != null) {
                    throw new ToolException(FORMS);
                }
                yield new AnimateSpec(true, 0, 0);
            }
            case "scroll" -> {
                if (rest == null) {
                    yield new AnimateSpec(false, 0, 1);
                }
                String[] d = rest.split(",", -1);
                if (d.length != 2) {
                    throw new ToolException(FORMS);
                }
                yield new AnimateSpec(false, step(d[0]), step(d[1]));
            }
            default -> throw new ToolException(FORMS);
        };
    }

    private static int step(String part) {
        String t = part.trim();
        if (!t.matches("-?\\d{1,4}")) {
            throw new ToolException(FORMS);
        }
        return Integer.parseInt(t);
    }
}
