package com.linkwright;

import com.linkwright.fml.ModInfoRules;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModInfoRulesTest {
    private static final DefaultArtifactVersion INSTALLED = new DefaultArtifactVersion("1.2.0");

    @Test
    void relaxesFinalFieldInPlace() throws Exception {
        com.linkwright.fakefml.classic.FMLLoader.reset();
        ModInfoRules rules = new ModInfoRules("com.linkwright.fakefml.classic");
        assertTrue(rules.present());
        assertFalse(rules.discovered());

        var info = com.linkwright.fakefml.classic.FMLLoader.install("powergrid", "tfmg", "[1.3.0,1.4.0)");
        var before = info.getDependencies().get(0);
        assertFalse(before.getVersionRange().containsVersion(INSTALLED));
        assertTrue(rules.discovered());

        assertEquals(1, rules.relax(Map.of("powergrid", List.of("tfmg"))));
        var after = info.getDependencies().get(0);
        assertSame(before, after);
        assertTrue(after.getVersionRange().containsVersion(INSTALLED));
        assertTrue(after.isMandatory());
    }

    @Test
    void replacesRecordsThroughHolder() throws Exception {
        com.linkwright.fakefml.records.FMLLoader.reset();
        ModInfoRules rules = new ModInfoRules("com.linkwright.fakefml.records");
        var info = com.linkwright.fakefml.records.FMLLoader.install("powergrid", "tfmg", "[1.3.0,1.4.0)");
        var before = info.getDependencies().get(0);

        assertEquals(1, rules.relax(Map.of("powergrid", List.of("tfmg"), "other", List.of("x"))));
        var after = info.getDependencies().get(0);
        VersionRange range = after.getVersionRange();
        assertTrue(range.containsVersion(INSTALLED));
        assertEquals("tfmg", after.getModId());
        assertSame(before.owner(), after.owner());
        assertTrue(after.isMandatory());
    }

    @Test
    void leavesOtherRulesAlone() throws Exception {
        com.linkwright.fakefml.classic.FMLLoader.reset();
        var info = com.linkwright.fakefml.classic.FMLLoader.install("powergrid", "tfmg", "[1.3.0,1.4.0)");
        assertEquals(0, new ModInfoRules("com.linkwright.fakefml.classic").relax(Map.of("powergrid", List.of("create"))));
        assertFalse(info.getDependencies().get(0).getVersionRange().containsVersion(INSTALLED));
    }
}
