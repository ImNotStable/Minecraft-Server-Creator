package io.github.imnotstable.msc.bot;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The test player's actions, one per script line. Each runs on the tick thread until it reports a result. */
final class Script {
    static final String HELP = "goto x,y,z|x,z [sprint] [jump], forward ticks [sprint] [jump], jump, look yaw pitch, "
            + "lookat x,y,z, wait ticks, use, use x,y,z, attack [type], interact [type], slot 1-9, say text, /command, "
            + "tp x,y,z, entities, inventory, hud, close";
    /** How far from the eyes to an entity's middle attack and interact look, as before: hitbox reach is about 3. */
    private static final double REACH = 4.5;

    /**
     * The result of {@code use x,y,z}. The packet is sent from any distance and many servers (Minestom) accept it, so
     * say when a real player could not have reached the block: a test that passes only from there proves nothing.
     */
    static String clicked(double eyeDistance) {
        return eyeDistance <= REACH + 0.5 ? "clicked the block"
                : String.format(Locale.ROOT, "clicked the block from %.1f blocks: beyond a player's reach (%.1f), goto closer", eyeDistance, REACH);
    }
    private static final int MAX_TICKS = 20 * 60;

    private Script() {
    }

    abstract static class Step {
        private final String text;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        protected long startTick;

        Step(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }

        /** Called once, under the player's lock, before the step's first tick. */
        void start(TestPlayer p) {
            startTick = p.ticks();
        }

        Physics.Input input(TestPlayer p) {
            return Physics.Input.NONE;
        }

        /** After each tick: true when the step is over (and has finished). */
        boolean check(TestPlayer p) {
            return result.isDone();
        }

        long elapsed(TestPlayer p) {
            return p.ticks() - startTick;
        }

        void finish(String r) {
            result.complete(r);
        }

        String await() throws InterruptedException {
            try {
                return result.get(MAX_TICKS / 20 + 10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                return "error: " + e.getCause();
            } catch (TimeoutException e) {
                return "stopped: no result (the server may have stopped)";
            }
        }
    }

    static Step parse(String raw) {
        String line = raw.strip();
        if (line.startsWith("/")) {
            return once(line, p -> {
                p.chat(line);
                return "sent";
            }, 10);
        }
        // Coordinates may be written "x,y,z", "x, y, z" or "x y z": join the numbers into one comma list.
        String[] tokens = line.split("[\\s,]+");
        List<String> words = new java.util.ArrayList<>();
        words.add(tokens[0]);
        StringBuilder nums = new StringBuilder();
        int i = 1;
        while (i < tokens.length && tokens[i].matches("[-+]?\\d*\\.?\\d+")) {
            nums.append(nums.isEmpty() ? "" : ",").append(tokens[i++]);
        }
        if (!nums.isEmpty()) {
            words.add(nums.toString());
        }
        while (i < tokens.length) {
            words.add(tokens[i++]);
        }
        String[] w = words.toArray(String[]::new);
        String op = w[0].toLowerCase(Locale.ROOT);
        List<String> flags = List.of(w).subList(Math.min(2, w.length), w.length);
        boolean sprint = flags.contains("sprint");
        boolean jump = flags.contains("jump");
        return switch (op) {
            case "goto" -> {
                double[] v = numbers(arg(w, line), 2, 3, line);
                yield new Goto(line, v[0], v.length == 3 ? v[1] : Double.NaN, v[v.length - 1], sprint, jump);
            }
            case "forward" -> new Forward(line, (int) numbers(arg(w, line), 1, 1, line)[0], sprint, jump);
            case "jump" -> new Jump(line);
            case "wait" -> new Wait(line, (int) numbers(arg(w, line), 1, 1, line)[0]);
            case "look" -> {
                double[] v = numbers(arg(w, line), 2, 2, line);
                float yaw = (float) v[0];
                float pitch = (float) v[1];
                yield once(line, p -> {
                    p.yaw = yaw;
                    p.pitch = pitch;
                    return "ok";
                }, 0);
            }
            case "lookat" -> {
                double[] v = numbers(arg(w, line), 3, 3, line);
                yield once(line, p -> {
                    double dx = v[0] - p.body.x;
                    double dy = v[1] - (p.body.y + 1.62);
                    double dz = v[2] - p.body.z;
                    p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    p.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    return String.format("yaw %.0f pitch %.0f", p.yaw, p.pitch);
                }, 0);
            }
            case "use" -> {
                if (w.length == 1) {
                    yield once(line, p -> {
                        p.useItem();
                        return "used held item";
                    }, 5);
                }
                double[] v = numbers(w[1], 3, 3, line);
                yield once(line, p -> {
                    int bx = (int) Math.floor(v[0]);
                    int by = (int) Math.floor(v[1]);
                    int bz = (int) Math.floor(v[2]);
                    p.useOn(bx, by, bz);
                    return clicked(Math.sqrt(Math.pow(bx + 0.5 - p.body.x, 2) + Math.pow(by + 0.5 - (p.body.y + 1.62), 2)
                            + Math.pow(bz + 0.5 - p.body.z, 2)));
                }, 5);
            }
            case "attack" -> {
                String type = entityType(w);
                yield once(line, p -> {
                    String hit = p.attackNearest(type, REACH);
                    return hit == null ? "no " + (type == null ? "entity" : type) + " within reach" : "hit " + hit;
                }, 5);
            }
            case "interact" -> {
                String type = entityType(w);
                yield once(line, p -> {
                    String hit = p.interactNearest(type, REACH);
                    return hit == null ? "no " + (type == null ? "entity" : type) + " within reach" : "clicked " + hit;
                }, 5);
            }
            case "inventory" -> once(line, p -> "\n" + indent(p.inventoryReport()), 0);
            case "hud" -> once(line, p -> "\n" + indent(p.hudReport()), 0);
            case "close" -> once(line, p -> p.closeMenu() ? "closed the menu" : "no menu open", 2);
            case "slot" -> {
                int slot = (int) numbers(arg(w, line), 1, 1, line)[0];
                if (slot < 1 || slot > 9) {
                    throw new IllegalArgumentException("slot is 1 to 9: " + line);
                }
                yield once(line, p -> {
                    p.selectSlot(slot - 1);
                    return "ok";
                }, 1);
            }
            case "say" -> {
                String text = line.substring(3).strip();
                yield once(line, p -> {
                    p.chat(text);
                    return "sent";
                }, 10);
            }
            case "tp" -> {
                double[] v = numbers(arg(w, line), 3, 3, line);
                yield once(line, p -> {
                    p.chat("/tp " + p.name() + " " + v[0] + " " + v[1] + " " + v[2]);
                    return "sent";
                }, 10);
            }
            case "entities" -> once(line, p -> {
                List<String> near = p.nearby(8);
                return near.isEmpty() ? "none tracked" : String.join("; ", near);
            }, 0);
            default -> throw new IllegalArgumentException("unknown action \"" + w[0] + "\". Actions: " + HELP);
        };
    }

    /** The optional entity type after attack or interact, as the entities action names it. */
    private static String entityType(String[] w) {
        return w.length > 1 ? w[1].toLowerCase(Locale.ROOT).replace("minecraft:", "") : null;
    }

    private static String indent(String report) {
        return "  " + report.replace("\n", "\n  ");
    }

    private static String arg(String[] w, String line) {
        if (w.length < 2) {
            throw new IllegalArgumentException("missing value: " + line);
        }
        return w[1];
    }

    static double[] numbers(String text, int min, int max, String line) {
        String[] parts = text.split(",");
        if (parts.length < min || parts.length > max) {
            throw new IllegalArgumentException("expected " + (min == max ? min : min + " or " + max)
                    + " numbers in \"" + text + "\": " + line);
        }
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: \"" + parts[i] + "\" in " + line);
            }
        }
        return out;
    }

    @FunctionalInterface
    interface Act {
        String run(TestPlayer p);
    }

    /** An action done at once, then a few ticks of waiting so its effects show up in the report. */
    private static Step once(String text, Act act, int settleTicks) {
        return new Step(text) {
            private String done;

            @Override
            void start(TestPlayer p) {
                super.start(p);
                done = act.run(p);
                if (settleTicks == 0) {
                    finish(done);
                }
            }

            @Override
            boolean check(TestPlayer p) {
                if (elapsed(p) >= settleTicks) {
                    finish(done);
                }
                return super.check(p);
            }
        };
    }

    static final class Wait extends Step {
        private final int ticks;

        Wait(String text, int ticks) {
            super(text);
            this.ticks = Math.min(ticks, MAX_TICKS);
        }

        @Override
        boolean check(TestPlayer p) {
            if (elapsed(p) >= ticks) {
                finish(String.format("waited %.1fs", ticks / 20.0));
            }
            return super.check(p);
        }
    }

    static final class Jump extends Step {
        private boolean pressed;
        private boolean left;
        private double startY;
        private double top;

        Jump(String text) {
            super(text);
        }

        @Override
        Physics.Input input(TestPlayer p) {
            // Wait for the ground first: right after joining or a teleport the player may still be settling.
            boolean press = !pressed && p.body.onGround;
            if (press) {
                pressed = true;
                startY = p.body.y;
                top = startY;
            }
            return new Physics.Input(0, 0, press, false, p.yaw);
        }

        @Override
        boolean check(TestPlayer p) {
            if (pressed) {
                top = Math.max(top, p.body.y);
                left |= !p.body.onGround;
                if (left && p.body.onGround) {
                    finish(String.format("rose %.2f, landed at y=%.2f", top - startY, p.body.y));
                }
            }
            if (elapsed(p) > 60) {
                finish(pressed ? String.format("rose %.2f, still in the air at y=%.2f", top - startY, p.body.y)
                        : "could not jump: not standing on anything");
            }
            return super.check(p);
        }
    }

    static final class Forward extends Step {
        private final int ticks;
        private final boolean sprint;
        private final boolean jump;
        private double sx;
        private double sz;

        Forward(String text, int ticks, boolean sprint, boolean jump) {
            super(text);
            this.ticks = Math.min(ticks, MAX_TICKS);
            this.sprint = sprint;
            this.jump = jump;
        }

        @Override
        void start(TestPlayer p) {
            super.start(p);
            sx = p.body.x;
            sz = p.body.z;
        }

        @Override
        Physics.Input input(TestPlayer p) {
            return new Physics.Input(1, 0, jump, sprint, p.yaw);
        }

        @Override
        boolean check(TestPlayer p) {
            if (elapsed(p) >= ticks) {
                finish(String.format("moved %.2f blocks", Math.hypot(p.body.x - sx, p.body.z - sz)));
            }
            return super.check(p);
        }
    }

    /**
     * Walk (or sprint) to a point, turning toward it every tick. With {@code jump} it jumps at ledges and over
     * one-block steps, the way a player crosses parkour.
     */
    static final class Goto extends Step {
        private final double tx;
        private final double ty;
        private final double tz;
        private final boolean sprint;
        private final boolean jump;
        private double best = Double.MAX_VALUE;
        private long bestTick;
        private double lowest;
        private int jumps;

        Goto(String text, double tx, double ty, double tz, boolean sprint, boolean jump) {
            super(text);
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.sprint = sprint;
            this.jump = jump;
        }

        @Override
        void start(TestPlayer p) {
            super.start(p);
            lowest = p.body.y;
            bestTick = p.ticks();
        }

        private double distance(TestPlayer p) {
            return Math.hypot(tx - p.body.x, tz - p.body.z);
        }

        @Override
        Physics.Input input(TestPlayer p) {
            double d = distance(p);
            p.yaw = (float) Math.toDegrees(Math.atan2(-(tx - p.body.x), tz - p.body.z));
            if (d < 0.25) {
                return new Physics.Input(0, 0, false, false, p.yaw);
            }
            boolean jumpNow = false;
            if (jump && p.body.onGround) {
                double lead = sprint ? 3.0 : 2.5;
                boolean ledge = !p.body.supportedAt(p.body.x + p.body.vx * lead, p.body.z + p.body.vz * lead, p.view());
                jumpNow = p.body.horizontalCollision || ledge && d > 0.8;
                if (jumpNow) {
                    jumps++;
                }
            }
            // Ease off near the target so friction stops the player on it.
            double forward = d < 0.6 ? 0.3 : 1;
            return new Physics.Input(forward, 0, jumpNow, sprint && d > 1.5, p.yaw);
        }

        @Override
        boolean check(TestPlayer p) {
            double d = distance(p);
            lowest = Math.min(lowest, p.body.y);
            boolean levelOk = Double.isNaN(ty) || Math.abs(p.body.y - ty) < 1.01;
            if (d < 0.35 && p.body.onGround && levelOk) {
                finish(String.format("reached in %.1fs%s", elapsed(p) / 20.0, jumps > 0 ? ", " + jumps + " jumps" : ""));
            } else if (!Double.isNaN(ty) && p.body.onGround && p.body.y < ty - 2.5) {
                finish(String.format("fell: landed at y=%.2f, %.1f blocks from the target", p.body.y, d));
            } else if (d < best - 0.1) {
                best = d;
                bestTick = p.ticks();
            } else if (p.ticks() - bestTick > 40) {
                finish(String.format("blocked: no progress for 2s, %.1f blocks from the target", d));
            } else if (elapsed(p) > MAX_TICKS) {
                finish(String.format("timed out after 60s, %.1f blocks from the target", d));
            }
            return super.check(p);
        }
    }
}
