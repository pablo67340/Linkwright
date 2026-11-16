package com.linkwright.scan;

import com.linkwright.util.Json;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads mods the way Forge 1.7 to 1.12 do: from {@code @Mod} annotations on classes, with
 * {@code mcmod.info} and {@code version.properties} filling in. A mod's version is the
 * annotation's, else {@code <modid>.version} in {@code version.properties}, else the
 * {@code mcmod.info} entry's, else {@code 1.0}. Its rules come from the annotation's
 * {@code dependencies} string, unless it sets {@code useMetadata} and its {@code mcmod.info} entry
 * sets {@code useDependencyInformation}.
 */
public final class LegacyForgeReader implements ModReader {
    private static final String MOD_1_7 = "Lcpw/mods/fml/common/Mod;";
    private static final String MOD_1_8 = "Lnet/minecraftforge/fml/common/Mod;";
    /** In the constant pool of every class that names either annotation. */
    private static final byte[] MARKER = "fml/common/Mod;".getBytes(StandardCharsets.UTF_8);

    @Override
    public List<ModEntry> read(ModJar jar) throws IOException {
        Map<String, Map<String, Object>> metadata = null;
        Properties versions = null;
        List<ModEntry> mods = new ArrayList<>();
        for (String entry : jar.entryNames()) {
            if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
                continue;
            }
            byte[] bytes = jar.read(entry);
            if (bytes == null || !contains(bytes, MARKER)) {
                continue;
            }
            Map<String, Object> mod = modAnnotation(bytes);
            String modId = mod == null ? null : string(mod.get("modid"));
            if (modId == null) {
                continue;
            }
            if (metadata == null) {
                metadata = mcmodInfo(jar);
                versions = versionProperties(jar);
            }
            Map<String, Object> info = metadata.get(modId);
            String version = string(mod.get("version"));
            if (version == null) {
                version = string(versions.getProperty(modId + ".version"));
            }
            if (version == null && info != null) {
                version = string(info.get("version"));
            }
            List<Dependency> dependencies;
            if (Boolean.TRUE.equals(mod.get("useMetadata")) && info != null
                    && Boolean.TRUE.equals(info.get("useDependencyInformation"))) {
                dependencies = metadataDependencies(info);
            } else {
                dependencies = parseDependencies(string(mod.get("dependencies")));
            }
            mods.add(new ModEntry(modId, version == null ? "1.0" : version, jar, dependencies));
        }
        return mods;
    }

    @Override
    public List<String> nestedJars(ModJar jar) {
        return Collections.emptyList();
    }

    /**
     * Reads an annotation dependency string such as {@code required-after:foo@[1.2,);after:bar}.
     * {@code required} rules must be present; {@code before} and {@code after} rules are checked
     * when present. Forge 1.12 also accepts {@code client} and {@code server}, limiting a rule to
     * that side.
     */
    public static List<Dependency> parseDependencies(String dependencies) {
        List<Dependency> parsed = new ArrayList<>();
        if (dependencies == null) {
            return parsed;
        }
        for (String rule : dependencies.split(";")) {
            int colon = rule.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String target = rule.substring(colon + 1).trim();
            if (target.isEmpty() || target.startsWith("*")) {
                continue;
            }
            boolean required = false;
            boolean ordered = false;
            Dependency.Side side = Dependency.Side.BOTH;
            for (String instruction : rule.substring(0, colon).trim().split("-")) {
                switch (instruction.trim()) {
                    case "required": required = true; break;
                    case "before":
                    case "after": ordered = true; break;
                    case "client": side = Dependency.Side.CLIENT; break;
                    case "server": side = Dependency.Side.SERVER; break;
                    default: break;
                }
            }
            if (required || ordered) {
                parsed.add(reference(target, required ? Dependency.Type.REQUIRED : Dependency.Type.OPTIONAL, side));
            }
        }
        return parsed;
    }

    /** {@code modid} or {@code modid@range}, as FML's {@code VersionParser} reads them. */
    private static Dependency reference(String target, Dependency.Type type, Dependency.Side side) {
        int at = target.indexOf('@');
        return at < 0
                ? new Dependency(target, "", type, side)
                : new Dependency(target.substring(0, at).trim(), target.substring(at + 1).trim(), type, side);
    }

    private static List<Dependency> metadataDependencies(Map<String, Object> info) {
        Map<String, Dependency> rules = new LinkedHashMap<>();
        for (String key : new String[]{"requiredMods", "dependencies", "dependants"}) {
            Object list = info.get(key);
            if (!(list instanceof List)) {
                continue;
            }
            Dependency.Type type = key.equals("requiredMods") ? Dependency.Type.REQUIRED : Dependency.Type.OPTIONAL;
            for (Object item : (List<?>) list) {
                String target = string(item);
                if (target != null && !target.startsWith("*")) {
                    Dependency rule = reference(target, type, Dependency.Side.BOTH);
                    String id = rule.modId() + "@" + rule.rangeSpec();
                    if (!rules.containsKey(id)) {
                        rules.put(id, rule);
                    }
                }
            }
        }
        return new ArrayList<>(rules.values());
    }

    private static Map<String, Object> modAnnotation(byte[] bytes) {
        final List<Map<String, Object>> found = new ArrayList<>(1);
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!desc.equals(MOD_1_7) && !desc.equals(MOD_1_8)) {
                        return null;
                    }
                    final Map<String, Object> values = new HashMap<>();
                    found.add(values);
                    return new AnnotationVisitor(Opcodes.ASM5) {
                        @Override
                        public void visit(String name, Object value) {
                            values.put(name, value);
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return null;
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /** {@code mcmod.info} entries by mod id; either a bare list or {@code {"modList": [...]}}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> mcmodInfo(ModJar jar) throws IOException {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        byte[] bytes = jar.read("mcmod.info");
        if (bytes == null) {
            return byId;
        }
        Object root;
        try {
            root = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            return byId;
        }
        if (root instanceof Map) {
            root = ((Map<String, Object>) root).get("modList");
        }
        if (root instanceof List) {
            for (Object item : (List<Object>) root) {
                if (item instanceof Map && string(((Map<String, Object>) item).get("modid")) != null) {
                    byId.put(string(((Map<String, Object>) item).get("modid")), (Map<String, Object>) item);
                }
            }
        }
        return byId;
    }

    private static Properties versionProperties(ModJar jar) throws IOException {
        Properties properties = new Properties();
        byte[] bytes = jar.read("version.properties");
        if (bytes != null) {
            properties.load(new ByteArrayInputStream(bytes));
        }
        return properties;
    }

    private static String string(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
