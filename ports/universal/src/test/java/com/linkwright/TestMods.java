package com.linkwright;

import com.linkwright.link.PatchSet;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small test mods from Java source, and runs their code once patched. */
public final class TestMods {
    private TestMods() {
    }

    /** Compiles {@code sources} (class name to source) against {@code classpath}; returns internal name to bytes. */
    public static Map<String, byte[]> compile(Path work, Map<String, String> sources, Map<String, byte[]> classpath)
            throws IOException {
        Path src = Files.createTempDirectory(work, "src");
        Path out = Files.createTempDirectory(work, "out");
        Path cp = Files.createTempDirectory(work, "cp");
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-d", out.toString(), "-cp", cp.toString()));
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path file = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            args.add(file.toString());
        }
        for (Map.Entry<String, byte[]> e : classpath.entrySet()) {
            Path file = cp.resolve(e.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        if (javac.run(null, null, errors, args.toArray(new String[0])) != 0) {
            throw new IllegalStateException(errors.toString(StandardCharsets.UTF_8));
        }
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String rel = out.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith(".class")) {
                    classes.put(rel.substring(0, rel.length() - 6), Files.readAllBytes(file));
                }
            }
        }
        return classes;
    }

    public static String toml(String modId, String version, String deps) {
        return "modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\nlicense = \"MIT\"\n[[mods]]\nmodId = \"" + modId
                + "\"\nversion = \"" + version + "\"\n" + deps;
    }

    public static String requires(String modId, String dependency, String range) {
        return "[[dependencies." + modId + "]]\nmodId = \"" + dependency + "\"\ntype = \"required\"\nversionRange = \""
                + range + "\"\nordering = \"NONE\"\nside = \"BOTH\"\n";
    }

    public static void jar(Path file, Map<String, byte[]> classes, String toml, Map<String, String> resources)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(toml.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zip.write(e.getValue());
            }
            for (Map.Entry<String, String> e : resources.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Patches {@code dependent} through the byte path the LaunchWrapper and agent hooks use, loads
     * it next to {@code dependency} (the JVM verifies everything it defines), and calls the static
     * no-argument method {@code cls.method}.
     */
    public static Object run(Map<String, byte[]> dependency, Map<String, byte[]> dependent, PatchSet patches,
                             String cls, String method) throws Exception {
        Map<String, byte[]> all = new HashMap<>(dependency);
        for (Map.Entry<String, byte[]> e : dependent.entrySet()) {
            byte[] patched = patches.apply(e.getKey(), e.getValue());
            all.put(e.getKey(), patched != null ? patched : e.getValue());
        }
        ClassLoader loader = new ClassLoader(TestMods.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = all.get(name.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Method m = loader.loadClass(cls).getMethod(method);
        return m.invoke(null);
    }
}
