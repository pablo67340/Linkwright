package com.linkwright.launch;

import com.linkwright.LwLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds entries to the dependency overrides FML loaded from {@code fml.toml}, in memory only.
 * {@code FMLConfig.load()} replaces the whole map, so this has to run after it.
 */
final class FmlOverrides {
    private static final String CONFIG = "net.neoforged.fml.loading.FMLConfig";
    private static final String OVERRIDE = CONFIG + "$DependencyOverride";

    private FmlOverrides() {
    }

    static boolean configLoaded() {
        try {
            Class<?> config = Class.forName(CONFIG, true, FmlOverrides.class.getClassLoader());
            Field instance = config.getDeclaredField("INSTANCE");
            Field data = config.getDeclaredField("configData");
            instance.setAccessible(true);
            data.setAccessible(true);
            return data.get(instance.get(null)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static void add(Map<String, List<String>> removals) throws ReflectiveOperationException {
        Class<?> config = Class.forName(CONFIG, true, FmlOverrides.class.getClassLoader());
        Constructor<?> override = Class.forName(OVERRIDE, true, config.getClassLoader()).getConstructor(String.class, boolean.class);
        Field field = config.getDeclaredField("dependencyOverrides");
        field.setAccessible(true);

        Map<String, List<Object>> current = (Map<String, List<Object>>) field.get(null);
        Map<String, List<Object>> merged = new HashMap<>(current == null ? Map.of() : current);
        int added = 0;
        for (Map.Entry<String, List<String>> entry : removals.entrySet()) {
            List<Object> list = new ArrayList<>(merged.getOrDefault(entry.getKey(), List.of()));
            for (String dependency : entry.getValue()) {
                Object value = override.newInstance(dependency, true);
                if (!list.contains(value)) {
                    list.add(value);
                    added++;
                }
            }
            merged.put(entry.getKey(), List.copyOf(list));
        }
        field.set(null, Collections.unmodifiableMap(merged));
        if (added > 0) {
            LwLog.info("Linkwright added " + added + " dependency override(s) to FML: " + removals);
        }
    }
}
