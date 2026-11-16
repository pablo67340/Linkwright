package com.linkwright.oldml;

import com.linkwright.fml.ModInfoRules;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * FML before Forge 1.17, which locates, reads and sorts the mods in one call during its scan, so
 * there is no gap between reading and checking for Linkwright to use from outside.
 *
 * <p>From Forge 1.14 FML also asks mod locators found in the mods folder for files, after its own
 * mods folder locator, and reads each file's metadata in that order before sorting. Linkwright
 * registers its own jar as such a locator ({@link LinkwrightLocator}), so its turn to look at its
 * file comes after every other mod's metadata is read and before anything is checked. Forge 1.13
 * has no such hook, but loads no mixins from the scan, so there the sort is simply redone.
 */
final class OldFml {
    private static final String LOADING = "net.minecraftforge.fml.loading";

    private OldFml() {
    }

    /** Adds the jar to the mod locators FML will load; false on Forge 1.13, which has none. */
    static boolean registerLocator(Path jar) {
        try {
            Class<?> discoverer = load("ModDirTransformerDiscoverer");
            @SuppressWarnings("unchecked")
            List<Path> locators = (List<Path>) discoverer.getMethod("getExtraLocators").invoke(null);
            if (!locators.contains(jar)) {
                locators.add(jar);
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** The mod files FML's jar locators have found so far, read or not. */
    static List<Object> locatedModFiles() throws ReflectiveOperationException {
        Object discoverer = field(load("FMLLoader"), "modDiscoverer").get(null);
        List<Object> files = new ArrayList<Object>();
        if (discoverer == null) {
            return files;
        }
        for (Object locator : (List<?>) field(discoverer.getClass(), "locatorList").get(discoverer)) {
            Field jars = findField(locator.getClass(), "modJars");
            if (jars != null) {
                files.addAll(((Map<?, ?>) jars.get(locator)).keySet());
            }
        }
        return files;
    }

    /**
     * When FML's sort failed, relaxes the rules and sorts again into the same mod list. Only for
     * Forge 1.13, where a failed sort still keeps every mod file and has already queued them all
     * for scanning, core mods and access transformers; returns how many rules were relaxed.
     */
    static int resortFailed(ModInfoRules rules, Map<String, List<String>> overrides) throws ReflectiveOperationException {
        Object list = load("FMLLoader").getMethod("getLoadingModList").invoke(null);
        if (list == null || ((List<?>) call(list, "getErrors")).isEmpty()) {
            return 0;
        }
        List<Object> files = new ArrayList<Object>();
        for (Object info : (List<?>) call(list, "getModFiles")) {
            files.add(call(info, "getFile"));
        }
        int relaxed = rules.relax(files, overrides);
        if (relaxed == 0) {
            return 0;
        }
        Class<?> listType = list.getClass();
        Method sort = load("ModSorter").getMethod("sort", List.class);
        Object sorted = sort.invoke(null, files);
        // sort() makes its result the current list; keep the one FML already handed out
        Field instance = findField(listType, "INSTANCE");
        if (instance != null) {
            instance.set(null, list);
        }
        for (String name : new String[]{"modFiles", "sortedList", "fileById", "preLoadErrors"}) {
            replaceContents(field(listType, name), list, sorted);
        }
        return relaxed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void replaceContents(Field field, Object target, Object source) throws IllegalAccessException {
        Object to = field.get(target);
        Object from = field.get(source);
        if (to instanceof Collection) {
            ((Collection) to).clear();
            ((Collection) to).addAll((Collection) from);
        } else if (to instanceof Map) {
            ((Map) to).clear();
            ((Map) to).putAll((Map) from);
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(LOADING + "." + name, true, OldFml.class.getClassLoader());
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = findField(type, name);
        if (f == null) {
            throw new NoSuchFieldException(type.getName() + "." + name);
        }
        return f;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
