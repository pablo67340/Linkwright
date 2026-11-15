package com.linkwright.launch;

import com.linkwright.LwLog;
import com.linkwright.link.BridgePatcher;
import com.linkwright.link.Patch;
import cpw.mods.modlauncher.api.ITransformer;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies Linkwright's patches to the dependent mods' classes as they load.
 *
 * <p>Built as a proxy because {@code ITransformer} changed between ModLauncher versions: 11 added
 * an abstract {@code getTargetType()} returning a type the older ones lack, so no single compiled
 * class implements it everywhere.
 */
final class BridgeTransformer implements InvocationHandler {
    private static final String API = "cpw.mods.modlauncher.api.";

    private final Map<String, List<Patch>> byClass = new HashMap<>();
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    private BridgeTransformer(List<Patch> patches) {
        for (Patch patch : patches) {
            byClass.computeIfAbsent(patch.targetClass(), k -> new ArrayList<>()).add(patch);
        }
    }

    static ITransformer<?> create(List<Patch> patches) {
        ClassLoader loader = ITransformer.class.getClassLoader();
        return (ITransformer<?>) Proxy.newProxyInstance(loader, new Class<?>[]{ITransformer.class},
                new BridgeTransformer(patches));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "transform":
                return transform((ClassNode) args[0]);
            case "castVote":
                return constant("TransformerVoteResult", "YES");
            case "targets":
                return targets();
            case "getTargetType":
                return constant("TargetType", "CLASS");
            case "labels":
                return new String[]{"linkwright"};
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "Linkwright bridge transformer";
            default:
                throw new UnsupportedOperationException(method.toString());
        }
    }

    private ClassNode transform(ClassNode input) {
        List<Patch> patches = byClass.get(input.name);
        if (patches != null) {
            int patched = BridgePatcher.apply(input, patches);
            // Mixin reads target classes through the transformers before they really load
            if (logged.add(input.name)) {
                LwLog.info("Linkwright adapted " + patched + " spot(s) in " + input.name.replace('/', '.'));
            }
        }
        return input;
    }

    private Set<Object> targets() throws ReflectiveOperationException {
        Method targetClass = Class.forName(API + "ITransformer$Target", true, ITransformer.class.getClassLoader())
                .getMethod("targetClass", String.class);
        Set<Object> targets = new HashSet<>();
        for (String name : byClass.keySet()) {
            targets.add(targetClass.invoke(null, name.replace('/', '.')));
        }
        return targets;
    }

    private static Object constant(String type, String name) throws ReflectiveOperationException {
        return Class.forName(API + type, true, ITransformer.class.getClassLoader()).getField(name).get(null);
    }
}
