package com.linkwright;

import com.linkwright.scan.Dependency;
import com.linkwright.scan.FabricModReader;
import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricModReaderTest {
    @TempDir
    Path dir;

    @Test
    void readsRulesAliasesAndNestedJars() throws Exception {
        Path file = jar("{\n"
                + "  \"schemaVersion\": 1, \"id\": \"example\", \"version\": \"2.1.0+mc1.20.1\",\n"
                + "  \"environment\": \"client\",\n"
                + "  \"provides\": [\"example_api\"],\n"
                + "  \"jars\": [{\"file\": \"META-INF/jars/inner.jar\"}, {\"file\": \"META-INF/jars/missing.jar\"}],\n"
                + "  \"depends\": {\"fabricloader\": \">=0.14\", \"lib\": [\"1.2.x\", \">=2.0 <3\"], \"any\": \"*\"},\n"
                + "  \"recommends\": {\"extra\": \">=4\"},\n"
                + "  \"breaks\": {\"old\": \"<1\"}\n"
                + "}", "META-INF/jars/inner.jar");
        FabricModReader reader = new FabricModReader();
        try (ModJar jar = ModJar.top(file)) {
            List<ModEntry> mods = reader.read(jar);
            assertEquals(2, mods.size());
            ModEntry mod = mods.get(0);
            assertEquals("example", mod.modId());
            assertEquals("2.1.0+mc1.20.1", mod.version());
            assertEquals("example_api", mods.get(1).modId());
            assertEquals("2.1.0+mc1.20.1", mods.get(1).version());

            List<Dependency> rules = mod.dependencies();
            assertEquals(5, rules.size());
            assertRule(rules.get(0), "fabricloader", ">=0.14", Dependency.Type.REQUIRED);
            assertRule(rules.get(1), "lib", "1.2.x || >=2.0 <3", Dependency.Type.REQUIRED);
            assertRule(rules.get(2), "any", "", Dependency.Type.REQUIRED);
            assertRule(rules.get(3), "extra", ">=4", Dependency.Type.ADVISORY);
            assertRule(rules.get(4), "old", "<1", Dependency.Type.INCOMPATIBLE);
            for (Dependency rule : rules) {
                assertEquals(Dependency.Side.CLIENT, rule.side());
            }

            assertEquals(List.of("META-INF/jars/inner.jar"), reader.nestedJars(jar));
        }
    }

    @Test
    void ignoresJarsWithoutMetadata() throws Exception {
        try (ModJar jar = ModJar.top(jar(null))) {
            assertTrue(new FabricModReader().read(jar).isEmpty());
            assertTrue(new FabricModReader().nestedJars(jar).isEmpty());
        }
    }

    private Path jar(String metadata, String... extraEntries) throws Exception {
        Path file = Files.createTempFile(dir, "mod", ".jar");
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            if (metadata != null) {
                zip.putNextEntry(new ZipEntry(FabricModReader.FILE));
                zip.write(metadata.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (String entry : extraEntries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static void assertRule(Dependency rule, String modId, String range, Dependency.Type type) {
        assertEquals(modId, rule.modId());
        assertEquals(range, rule.rangeSpec());
        assertEquals(type, rule.type());
    }
}
