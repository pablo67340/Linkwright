package com.linkwright.fabric;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Called from Fabric Loader's {@code DependencyOverrides}, which resolves this class next to the
 * agent. Forwards to the {@link FabricCore} the agent started.
 */
public final class FabricHook {
    private static volatile Object core;
    private static volatile Method prepare;
    private static volatile Method apply;

    private FabricHook() {
    }

    static void attach(Object started) throws NoSuchMethodException {
        prepare = started.getClass().getMethod("prepare", Object.class);
        apply = started.getClass().getMethod("apply", Object.class);
        core = started;
    }

    /** Start of {@code DependencyOverrides}' constructor: once per launch, before any mod is read. */
    public static void prepare(Object overridesClass) {
        forward(prepare, overridesClass);
    }

    /** Start of {@code DependencyOverrides.apply}, for each mod as Fabric Loader reads it. */
    public static void apply(Object metadata) {
        forward(apply, metadata);
    }

    private static void forward(Method method, Object argument) {
        Object target = core;
        if (target == null) {
            return;
        }
        try {
            method.invoke(target, argument);
        } catch (InvocationTargetException | IllegalAccessException e) {
            System.out.println("[Linkwright] Linkwright hook failed: " + e);
        }
    }
}
