package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.DurableCommandProtocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Content-addressed helper classes work from both the test classpath and a packaged application. */
public final class DurableHelperArchive {
    private DurableHelperArchive() { }
    public static Path write(Path directory, Class<?> main, Class<?>... dependencies) throws IOException {
        var bytes = new ByteArrayOutputStream(); var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main.getName());
        try (var jar = new JarOutputStream(bytes)) {
            var entry = new JarEntry("META-INF/MANIFEST.MF"); entry.setTime(0); jar.putNextEntry(entry); manifest.write(jar); jar.closeEntry();
            var classes = new TreeSet<String>(); var roots = new ArrayList<>(List.of(dependencies)); roots.add(main);
            for (var root : roots) for (var type : root.getNestMembers()) classes.add(type.getName().replace('.', '/') + ".class");
            for (var name : classes) {
                try (var stream = main.getClassLoader().getResourceAsStream(name)) {
                    if (stream == null) throw new IOException("Helper class is missing");
                    entry = new JarEntry(name); entry.setTime(0); jar.putNextEntry(entry); stream.transferTo(jar); jar.closeEntry();
                }
            }
        }
        byte[] body = bytes.toByteArray(); check(directory); Files.createDirectories(directory); check(directory);
        Path jar = directory.resolve(hash(body) + ".jar"); publish(jar, body); return jar;
    }
}
