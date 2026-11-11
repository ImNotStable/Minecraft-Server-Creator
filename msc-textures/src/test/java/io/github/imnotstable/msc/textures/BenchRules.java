package io.github.imnotstable.msc.textures;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** The msc-bench PNG rules for its ruby tasks, copied so this module's output is held to them. */
final class BenchRules {
    private BenchRules() {
    }

    /** 16x16, at most 8 colours, 20-85% opaque, half the opaque pixels red (330-15 deg, saturation 0.35+). */
    static List<String> rubyProblems(Texture t) {
        List<String> problems = new ArrayList<>();
        if (t.width() != 16 || t.height() != 16) {
            problems.add("size");
        }
        int opaque = 0;
        int hits = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < t.height(); y++) {
            for (int x = 0; x < t.width(); x++) {
                int c = t.get(x, y);
                if (c >>> 24 == 0) {
                    continue;
                }
                opaque++;
                Color.RGBtoHSB((c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff, hsb);
                float h = hsb[0] * 360f;
                if (hsb[1] >= 0.35 && (h >= 330 || h <= 15)) {
                    hits++;
                }
            }
        }
        double f = opaque / 256.0;
        if (t.colors().size() > 8) {
            problems.add(t.colors().size() + " colors");
        }
        if (f < 0.2 || f > 0.85) {
            problems.add("opaque " + f);
        }
        if (opaque == 0 || hits / (double) opaque < 0.5) {
            problems.add("hue " + hits + "/" + opaque);
        }
        return problems;
    }
}
