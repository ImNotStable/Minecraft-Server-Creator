package io.github.imnotstable.msc.core;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Stands in for the Quilt, NeoForge and Forge installers: run with their command lines, it writes the files each real
 * installer leaves behind, so server_setup can be run end to end without a download. Its version is taken from its
 * own jar name, as the real installers carry theirs.
 */
public final class FakeLoaderInstaller {
    private FakeLoaderInstaller() {
    }

    public static void main(String[] args) throws IOException, URISyntaxException {
        String jar = Path.of(FakeLoaderInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getFileName().toString();
        List<String> a = List.of(args);
        if (a.getFirst().equals("install")) {
            // install server <minecraft> <loader> --download-server --install-dir=. (run inside the server folder)
            Path dir = Path.of("").toAbsolutePath();
            Files.createDirectories(dir.resolve("libraries/org/quiltmc/quilt-loader/" + a.get(3)));
            Files.writeString(dir.resolve("quilt-server-launch.jar"), "quilt server");
            // --download-server: the vanilla jar the launcher starts.
            Files.writeString(dir.resolve("server.jar"), "quilt vanilla");
        } else if (a.getFirst().equals("--install-server")) {
            Path dir = Path.of(a.get(1));
            String version = jar.substring("neoforge-".length(), jar.length() - "-installer.jar".length());
            Files.createDirectories(dir.resolve("libraries/net/neoforged/neoforge/" + version));
            Files.writeString(dir.resolve("server.jar"), "neoforge server");
        } else if (a.getFirst().equals("--installServer")) {
            Path dir = Path.of(a.get(1));
            String version = jar.substring("forge-".length(), jar.length() - "-installer.jar".length());
            Files.writeString(dir.resolve("forge-" + version + "-shim.jar"), "forge server");
        } else {
            throw new IllegalArgumentException("unknown installer call " + a);
        }
    }

    /** The bytes of a runnable installer jar holding only this class. */
    static byte[] jar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeLoaderInstaller.class.getName());
        String entry = FakeLoaderInstaller.class.getName().replace('.', '/') + ".class";
        var bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes, manifest);
             var in = FakeLoaderInstaller.class.getResourceAsStream("/" + entry)) {
            out.putNextEntry(new JarEntry(entry));
            in.transferTo(out);
        }
        return bytes.toByteArray();
    }
}
