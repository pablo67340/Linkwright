package com.linkwright;

import com.linkwright.scan.Dependency;
import com.linkwright.scan.LegacyForgeReader;
import com.linkwright.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyForgeReaderTest {
    @Test
    void readsFmlDependencyStrings() {
        List<Dependency> rules = LegacyForgeReader.parseDependencies(
                "required-after:Forge@[10.13.4,);after:NotEnoughItems;required-before:b@[2.0,3.0);"
                        + "after:*;client-required-after:jei@[4.0,);before:c@1.0;nonsense:d;");
        assertEquals(5, rules.size());
        assertRule(rules.get(0), "Forge", "[10.13.4,)", Dependency.Type.REQUIRED, Dependency.Side.BOTH);
        assertRule(rules.get(1), "NotEnoughItems", "", Dependency.Type.OPTIONAL, Dependency.Side.BOTH);
        assertRule(rules.get(2), "b", "[2.0,3.0)", Dependency.Type.REQUIRED, Dependency.Side.BOTH);
        assertRule(rules.get(3), "jei", "[4.0,)", Dependency.Type.REQUIRED, Dependency.Side.CLIENT);
        assertRule(rules.get(4), "c", "1.0", Dependency.Type.OPTIONAL, Dependency.Side.BOTH);
    }

    @Test
    void emptyDependencyStringHasNoRules() {
        assertTrue(LegacyForgeReader.parseDependencies("").isEmpty());
        assertTrue(LegacyForgeReader.parseDependencies(null).isEmpty());
    }

    @Test
    void parsesLooseMcmodInfo() {
        Object parsed = Json.parse("\uFEFF[\n  // hand-written\n  {\n    'modid': \"ex\",\n    \"version\": \"${version}\",\n"
                + "    \"requiredMods\": [\"Forge@[10.13,)\",],\n    \"useDependencyInformation\": true,\n"
                + "    \"logoFile\": null, \"count\": 3, \"ratio\": 1.5,\n  },\n]");
        List<?> list = (List<?>) parsed;
        Map<?, ?> mod = (Map<?, ?>) list.get(0);
        assertEquals("ex", mod.get("modid"));
        assertEquals("${version}", mod.get("version"));
        assertEquals(List.of("Forge@[10.13,)"), mod.get("requiredMods"));
        assertEquals(Boolean.TRUE, mod.get("useDependencyInformation"));
        assertTrue(mod.containsKey("logoFile"));
        assertEquals(3L, mod.get("count"));
        assertEquals(1.5, mod.get("ratio"));
    }

    @Test
    void rejectsBrokenJson() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": [1, 2"));
    }

    private static void assertRule(Dependency rule, String modId, String range, Dependency.Type type, Dependency.Side side) {
        assertEquals(modId, rule.modId());
        assertEquals(range, rule.rangeSpec());
        assertEquals(type, rule.type());
        assertEquals(side, rule.side());
    }
}
