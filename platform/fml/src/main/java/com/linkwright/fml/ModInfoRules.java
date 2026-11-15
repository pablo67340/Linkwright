package com.linkwright.fml;

import com.linkwright.LwLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Relaxes version rules in the mod metadata FML has discovered but not yet sorted, for loaders
 * without a dependency override setting (Forge, and NeoForge before FML 4).
 *
 * <p>FML keeps the discovered mods in {@code FMLLoader.modValidator} from its
 * {@code beginScanning} until {@code ModSorter} checks versions in its {@code completeScan}. Each
 * approved rule's range is replaced by one that admits every version; nothing else about the
 * rule (ordering, side, whether it is mandatory) changes. Over the years the rule has been a
 * class with a final field and, since Forge 1.21.11, a record inside a record, so everything here
 * is looked up by shape rather than compiled against one version.
 */
public final class ModInfoRules {
    /** A Maven soft requirement: shown as written, satisfied by any version. */
    private static final String ANY = "*";

    private final String loadingPackage;

    /** @param loadingPackage {@code net.minecraftforge.fml.loading} or {@code net.neoforged.fml.loading} */
    public ModInfoRules(String loadingPackage) {
        this.loadingPackage = loadingPackage;
    }

    public boolean present() {
        try {
            Class.forName(loadingPackage + ".FMLLoader", false, ModInfoRules.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether FML has discovered the mods and not yet let go of them. */
    public boolean discovered() {
        try {
            return candidates() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private List<?> candidates() throws ReflectiveOperationException {
        Class<?> loader = Class.forName(loadingPackage + ".FMLLoader", true, ModInfoRules.class.getClassLoader());
        Object validator = field(loader, "modValidator").get(null);
        if (validator == null) {
            return null;
        }
        return (List<?>) field(validator.getClass(), "candidateMods").get(validator);
    }

    /** Returns how many rules were relaxed. */
    public int relax(Map<String, List<String>> overrides) throws ReflectiveOperationException {
        List<?> candidates = candidates();
        if (candidates == null) {
            throw new IllegalStateException("FML has not discovered mods yet");
        }
        int relaxed = 0;
        for (Object modFile : candidates) {
            for (Object info : (List<?>) call(modFile, "getModInfos")) {
                List<String> dependencies = overrides.get((String) call(info, "getModId"));
                if (dependencies != null) {
                    relaxed += relaxInfo(info, dependencies);
                }
            }
        }
        if (relaxed > 0) {
            LwLog.info("Linkwright relaxed " + relaxed + " dependency rule(s) in FML: " + overrides);
        }
        return relaxed;
    }

    private int relaxInfo(Object info, List<String> dependencies) throws ReflectiveOperationException {
        List<?> current = (List<?>) call(info, "getDependencies");
        List<Object> updated = new ArrayList<Object>(current);
        boolean replaced = false;
        int relaxed = 0;
        for (int i = 0; i < updated.size(); i++) {
            Object rule = updated.get(i);
            if (!dependencies.contains((String) call(rule, "getModId"))) {
                continue;
            }
            Object range = call(rule, "getVersionRange");
            Object any = range.getClass().getMethod("createFromVersionSpec", String.class).invoke(null, ANY);
            Object result = withRange(rule, range.getClass(), any);
            if (result != rule) {
                updated.set(i, result);
                replaced = true;
            }
            relaxed++;
        }
        if (replaced) {
            setDependencies(info, current, updated);
        }
        return relaxed;
    }

    /** Sets the range in place, or returns a copy when the rule is a record. */
    private static Object withRange(Object rule, Class<?> rangeType, Object any) throws ReflectiveOperationException {
        Class<?> type = rule.getClass();
        if (!isRecord(type)) {
            for (Field f : type.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType().isAssignableFrom(rangeType)) {
                    f.setAccessible(true);
                    f.set(rule, any);
                    return rule;
                }
            }
            throw new NoSuchFieldException("no version range field in " + type.getName());
        }
        Object[] components = (Object[]) Class.class.getMethod("getRecordComponents").invoke(type);
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        boolean set = false;
        for (int i = 0; i < components.length; i++) {
            Class<?> componentType = (Class<?>) components[i].getClass().getMethod("getType").invoke(components[i]);
            Method accessor = (Method) components[i].getClass().getMethod("getAccessor").invoke(components[i]);
            accessor.setAccessible(true);
            types[i] = componentType;
            if (!set && componentType.isAssignableFrom(rangeType)) {
                values[i] = any;
                set = true;
            } else {
                values[i] = accessor.invoke(rule);
            }
        }
        if (!set) {
            throw new NoSuchFieldException("no version range component in " + type.getName());
        }
        Constructor<?> canonical = type.getDeclaredConstructor(types);
        canonical.setAccessible(true);
        return canonical.newInstance(values);
    }

    @SuppressWarnings("unchecked")
    private static void setDependencies(Object info, List<?> current, List<Object> updated) throws ReflectiveOperationException {
        try {
            List<Object> list = (List<Object>) current;
            for (int i = 0; i < updated.size(); i++) {
                if (list.get(i) != updated.get(i)) {
                    list.set(i, updated.get(i));
                }
            }
            return;
        } catch (UnsupportedOperationException immutable) {
            // Fall through and swap the whole list
        }
        Field field = field(info.getClass(), "dependencies");
        Object holder = field.get(info);
        if (holder instanceof List) {
            field.set(info, updated);
        } else {
            // Forge 1.21.11+: a mutable holder inside the ModInfo record
            field(holder.getClass(), "value").set(holder, updated);
        }
    }

    private static boolean isRecord(Class<?> type) {
        try {
            return (Boolean) Class.class.getMethod("isRecord").invoke(type);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    /** Calls a public no-argument method, even when it is declared on a class that is not public. */
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
