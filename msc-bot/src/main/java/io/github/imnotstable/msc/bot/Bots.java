package io.github.imnotstable.msc.bot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Joins a test player that speaks the server's protocol version. MCProtocolLib supports one Minecraft version per
 * release, so the bot is compiled against the newest one, and each older one ships as a folder of jars (such as
 * {@code bot/26.2/protocol-26.2.jar}) that gets its own class loader with a fresh copy of the bot classes.
 */
public final class Bots {
    /** A protocol the test player can speak, and where its classes come from. */
    record Speaker(int protocol, String minecraft, ClassLoader loader) {
    }

    private static final Map<Path, Map<Integer, Speaker>> FOLDERS = new ConcurrentHashMap<>();

    private Bots() {
    }

    /**
     * Ask the server which version it runs, then join with the matching client.
     *
     * @param protocols folder of version subfolders for older protocols, or null for the built-in one only
     */
    public static Bot join(String name, String host, int port, BlockSource blocks, Duration timeout, Path protocols)
            throws InterruptedException {
        return join(name, host, port, blocks, null, timeout, protocols);
    }

    /** As {@link #join(String, String, int, BlockSource, Duration, Path)}, with a planner for the walk action. */
    public static Bot join(String name, String host, int port, BlockSource blocks, RoutePlanner planner,
                           Duration timeout, Path protocols) throws InterruptedException {
        return join(name, host, port, blocks, planner, null, timeout, protocols);
    }

    /**
     * As {@link #join(String, String, int, BlockSource, RoutePlanner, Duration, Path)}, with a locator that says which
     * world the player is in after the server puts it in one; without it the bot goes by the dimension.
     */
    public static Bot join(String name, String host, int port, BlockSource blocks, RoutePlanner planner,
                           WorldLocator locator, Duration timeout, Path protocols) throws InterruptedException {
        ServerPing.Version version;
        try {
            version = ServerPing.version(host, port, Duration.ofSeconds(5));
        } catch (IOException e) {
            throw new IllegalStateException("no Minecraft server answers at " + host + ":" + port + " (" + e.getMessage()
                    + ")", e);
        }
        Map<Integer, Speaker> speakers = speakers(protocols);
        Speaker speaker = speakers.get(version.protocol());
        if (speaker == null) {
            List<String> known = speakers.values().stream().map(s -> s.minecraft() + " (" + s.protocol() + ")").toList();
            throw new IllegalStateException("the server runs " + version.name() + " (protocol " + version.protocol()
                    + "); the test player speaks " + String.join(", ", known));
        }
        if (speaker.loader() == Bots.class.getClassLoader()) {
            return TestPlayer.join(name, host, port, blocks, planner, locator, timeout);
        }
        Object bot = call(speaker.loader(), "join", new Class<?>[] {String.class, String.class, int.class,
                BlockSource.class, RoutePlanner.class, WorldLocator.class, Duration.class}, name, host, port, blocks,
                planner, locator, timeout);
        return (Bot) bot;
    }

    /** The built-in protocol, then every version folder under {@code protocols}, newest protocol first. */
    static Map<Integer, Speaker> speakers(Path protocols) {
        Map<Integer, Speaker> all = new TreeMap<>((a, b) -> Integer.compare(b, a));
        all.put(TestPlayer.protocolVersion(), new Speaker(TestPlayer.protocolVersion(), TestPlayer.minecraftVersion(),
                Bots.class.getClassLoader()));
        if (protocols != null && Files.isDirectory(protocols)) {
            all.putAll(FOLDERS.computeIfAbsent(protocols.toAbsolutePath().normalize(), Bots::load));
        }
        return all;
    }

    private static Map<Integer, Speaker> load(Path protocols) {
        Map<Integer, Speaker> found = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(protocols)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                List<URL> urls = new ArrayList<>();
                urls.add(botCode());
                try (Stream<Path> jars = Files.list(dir)) {
                    for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                        urls.add(jar.toUri().toURL());
                    }
                }
                ClassLoader loader = new ChildFirst(urls.toArray(URL[]::new), Bots.class.getClassLoader());
                int protocol = (Integer) call(loader, "protocolVersion", new Class<?>[0]);
                String minecraft = (String) call(loader, "minecraftVersion", new Class<?>[0]);
                found.put(protocol, new Speaker(protocol, minecraft, loader));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return Map.copyOf(found);
    }

    private static URL botCode() throws MalformedURLException {
        try {
            return Bots.class.getProtectionDomain().getCodeSource().getLocation().toURI().toURL();
        } catch (URISyntaxException e) {
            throw new MalformedURLException(e.getMessage());
        }
    }

    /** Calls a static method of the {@link TestPlayer} that {@code loader} holds, passing its exceptions through. */
    private static Object call(ClassLoader loader, String method, Class<?>[] types, Object... args)
            throws InterruptedException {
        try {
            Method m = loader.loadClass(TestPlayer.class.getName()).getMethod(method, types);
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            switch (e.getCause()) {
                case InterruptedException i -> throw i;
                case RuntimeException r -> throw r;
                case Error err -> throw err;
                default -> throw new IllegalStateException(e.getCause());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test player classes for another protocol failed to load: " + e, e);
        }
    }

    /**
     * Loads MCProtocolLib and the bot's own classes from its URLs first, so they link against that MCProtocolLib.
     * {@link Bot}, {@link BlockSource}, {@link RoutePlanner} and {@link WorldLocator} stay shared with the core, and
     * so does everything else (Netty, Adventure, msc-protocol).
     */
    static final class ChildFirst extends URLClassLoader {
        private static final Set<String> SHARED = Set.of(Bot.class.getName(), BlockSource.class.getName(),
                RoutePlanner.class.getName(), WorldLocator.class.getName());

        static {
            registerAsParallelCapable();
        }

        ChildFirst(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        private static boolean own(String name) {
            return (name.startsWith("org.geysermc.mcprotocollib.") || name.startsWith("io.github.imnotstable.msc.bot."))
                    && !SHARED.contains(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!own(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = findClass(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        public URL getResource(String name) {
            // MCProtocolLib reads its registry data (networkCodec.nbt and others) as resources of its own version.
            URL own = findResource(name);
            return own != null ? own : super.getResource(name);
        }
    }
}
