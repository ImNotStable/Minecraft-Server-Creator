package io.github.imnotstable.msc.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place that makes a server's Java speak UTF-8 on its console. MSC reads a server's output and writes its
 * console commands as UTF-8, but a JVM encodes {@code System.out}/{@code System.err} and decodes {@code System.in}
 * in the platform's native encoding (Cp1252 on Windows, US-ASCII under a C locale): Minestom's slf4j-simple log, a
 * plugin's {@code System.out.println} and Paper's console reader would garble any non-ASCII text.
 *
 * <p>The flags, for every platform (Paper, Purpur, Fabric, Quilt, NeoForge, Forge, Minestom): {@code stdout.encoding}
 * and {@code stderr.encoding} for {@code System.out}/{@code System.err} (Java 19 and later); {@code stdin.encoding}
 * for console readers that follow it, such as JLine in Paper (vanilla's reader decodes UTF-8 itself); and
 * {@code file.encoding}, already UTF-8 by default since Java 18, for a server run on an older Java (Log4j's console
 * output, used by every platform but Minestom, is UTF-8 on its own).
 *
 * <p>Flags go only into a command whose program is a Java launcher, right after it, and a flag the command already
 * sets is left as it is. A configured server command that starts something else (a script, a wrapper) is passed
 * unchanged: it must start its Java with {@link #ENCODING_FLAGS} itself.
 */
public final class ServerJava {
    /** The system properties a server's Java is started with. */
    public static final List<String> ENCODING_FLAGS = List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8");

    private ServerJava() {
    }

    /** {@code command} with {@link #ENCODING_FLAGS} after its program when that is a Java launcher, else as it is. */
    public static List<String> withEncoding(List<String> command) {
        if (command.isEmpty() || !isJava(command.getFirst())) {
            return List.copyOf(command);
        }
        List<String> out = new ArrayList<>(command.size() + ENCODING_FLAGS.size());
        out.add(command.getFirst());
        for (String flag : ENCODING_FLAGS) {
            String property = flag.substring(0, flag.indexOf('=') + 1);
            if (command.stream().noneMatch(arg -> arg.startsWith(property))) {
                out.add(flag);
            }
        }
        out.addAll(command.subList(1, command.size()));
        return List.copyOf(out);
    }

    /** True for {@code java}, {@code javaw} or {@code java.exe} given by name or by path. */
    static boolean isJava(String program) {
        String name = program.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - ".exe".length());
        }
        return name.equals("java") || name.equals("javaw");
    }
}
