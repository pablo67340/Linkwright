package com.linkwright.fabric;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;

/**
 * Waits for Fabric Loader's {@code DependencyOverrides}, then loads the core in a class loader
 * whose parent is Fabric Loader's, so the core sees the same ASM and loader classes Fabric does.
 */
final class AgentBridge implements ClassFileTransformer {
    static final String OVERRIDES = "net/fabricmc/loader/impl/metadata/DependencyOverrides";

    private final URL agentJar;
    private volatile ClassFileTransformer core;

    AgentBridge(URL agentJar) {
        this.agentJar = agentJar;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) throws IllegalClassFormatException {
        ClassFileTransformer started = core;
        if (started == null) {
            if (!OVERRIDES.equals(className)) {
                return null;
            }
            started = start(loader);
            if (started == null) {
                return null;
            }
        }
        return started.transform(loader, className, redefined, domain, bytes);
    }

    private synchronized ClassFileTransformer start(ClassLoader fabric) {
        if (core == null) {
            try {
                Object started = new CoreLoader(agentJar, fabric).loadClass("com.linkwright.fabric.FabricCore")
                        .getConstructor().newInstance();
                FabricHook.attach(started);
                core = (ClassFileTransformer) started;
            } catch (Throwable t) {
                System.out.println("[Linkwright] Linkwright could not start; Fabric Loader will check mod versions as usual");
                t.printStackTrace(System.out);
            }
        }
        return core;
    }

    /** Loads Linkwright's own classes itself and everything else from Fabric Loader's side. */
    private static final class CoreLoader extends URLClassLoader {
        CoreLoader(URL agentJar, ClassLoader parent) {
            super(new URL[]{agentJar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.linkwright.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
