package com.linkwright.link;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Member lookup and assignability over the class index, falling back to the JDK through
 * reflection. Lookups that walk into classes nobody indexed (Minecraft when its jars could not be
 * located, libraries the pack does not ship) come back {@link Resolution#UNKNOWN} rather than
 * missing.
 */
public final class Hierarchy {
    public enum Resolution {
        FOUND, MISSING, UNKNOWN
    }

    private interface InfoTest {
        boolean test(ClassInfo info);
    }

    private interface JdkTest {
        boolean test(Class<?> cls);
    }

    private static final Set<String> OBJECT_GETTERS = new HashSet<>(Arrays.asList("getClass", "toString", "hashCode", "clone"));

    private final ClassIndex index;
    private final McNames names;
    private List<Conversion.Call> knownConversions;

    public Hierarchy(ClassIndex index, McNames names) {
        this.index = index;
        this.names = names;
    }

    public ClassIndex index() {
        return index;
    }

    synchronized List<Conversion.Call> knownConversions() {
        if (knownConversions == null) {
            knownConversions = Conversion.minecraftConversions(names, this);
        }
        return knownConversions;
    }

    public Resolution resolveMethod(String owner, final String name, final String desc) {
        return resolve(owner, new InfoTest() {
            @Override
            public boolean test(ClassInfo info) {
                return info.method(name, desc) != null;
            }
        }, new JdkTest() {
            @Override
            public boolean test(Class<?> cls) {
                return jdkHasMethod(cls, name, desc);
            }
        }, !"<init>".equals(name) && !"<clinit>".equals(name));
    }

    public Resolution resolveField(String owner, final String name, final String desc) {
        return resolve(owner, new InfoTest() {
            @Override
            public boolean test(ClassInfo info) {
                return info.field(name, desc) != null;
            }
        }, new JdkTest() {
            @Override
            public boolean test(Class<?> cls) {
                return jdkHasField(cls, name, desc);
            }
        }, true);
    }

    /** Looks a member up by name alone, for mixin selectors that omit the descriptor. */
    public Resolution resolveMethodName(String owner, final String name) {
        return resolve(owner, new InfoTest() {
            @Override
            public boolean test(ClassInfo info) {
                for (ClassInfo.Member m : info.methods.values()) {
                    if (m.name().equals(name)) {
                        return true;
                    }
                }
                return false;
            }
        }, new JdkTest() {
            @Override
            public boolean test(Class<?> cls) {
                return jdkHasMethodName(cls, name);
            }
        }, !"<init>".equals(name));
    }

    public Resolution resolveFieldName(String owner, final String name) {
        return resolve(owner, new InfoTest() {
            @Override
            public boolean test(ClassInfo info) {
                for (ClassInfo.Member f : info.fields.values()) {
                    if (f.name().equals(name)) {
                        return true;
                    }
                }
                return false;
            }
        }, new JdkTest() {
            @Override
            public boolean test(Class<?> cls) {
                return jdkHasFieldName(cls, name);
            }
        }, true);
    }

    private Resolution resolve(String owner, InfoTest inIndex, JdkTest inJdk, boolean inherit) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        boolean unknown = false;
        while (!queue.isEmpty()) {
            String cls = queue.poll();
            if (!seen.add(cls)) {
                continue;
            }
            ClassInfo info = index.info(cls);
            if (info != null) {
                if (inIndex.test(info)) {
                    return Resolution.FOUND;
                }
                if (inherit) {
                    queue.addAll(info.supertypes());
                }
                continue;
            }
            Class<?> jdk = jdkClass(cls);
            if (jdk != null) {
                if (inJdk.test(jdk)) {
                    return Resolution.FOUND;
                }
            } else {
                unknown = true;
            }
        }
        return unknown ? Resolution.UNKNOWN : Resolution.MISSING;
    }

    /** Methods with this name visible from {@code owner}, nearest declaration first. */
    public List<ClassInfo.Member> methodsNamed(String owner, String name) {
        Map<String, ClassInfo.Member> found = new LinkedHashMap<>();
        boolean declaredOnly = "<init>".equals(name);
        for (ClassInfo info : ancestors(owner)) {
            for (ClassInfo.Member m : info.methods.values()) {
                if (m.name().equals(name) && !found.containsKey(m.desc())) {
                    found.put(m.desc(), m);
                }
            }
            if (declaredOnly) {
                break;
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Fields with this name visible from {@code owner}, nearest declaration first. */
    public List<ClassInfo.Member> fieldsNamed(String owner, String name) {
        Map<String, ClassInfo.Member> found = new LinkedHashMap<>();
        for (ClassInfo info : ancestors(owner)) {
            for (ClassInfo.Member f : info.fields.values()) {
                if (f.name().equals(name) && !found.containsKey(f.desc())) {
                    found.put(f.desc(), f);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Public instance methods taking no arguments and returning an object. */
    public List<ClassInfo.Member> zeroArgGetters(String owner) {
        Map<String, ClassInfo.Member> found = new LinkedHashMap<>();
        for (ClassInfo info : ancestors(owner)) {
            for (ClassInfo.Member m : info.methods.values()) {
                if (m.desc().startsWith("()L") && !m.isStatic() && (m.access() & Opcodes.ACC_PUBLIC) != 0
                        && (m.access() & Opcodes.ACC_SYNTHETIC) == 0
                        && !m.name().startsWith("<") && !OBJECT_GETTERS.contains(m.name())
                        && !found.containsKey(m.name() + m.desc())) {
                    found.put(m.name() + m.desc(), m);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** The class itself and every indexed supertype, breadth first. */
    public List<ClassInfo> ancestors(String owner) {
        List<ClassInfo> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String cls = queue.poll();
            if (!seen.add(cls)) {
                continue;
            }
            ClassInfo info = index.info(cls);
            if (info != null) {
                result.add(info);
                queue.addAll(info.supertypes());
            }
        }
        return result;
    }

    /**
     * Whether a value of type {@code sub} can be used where {@code sup} is expected. Returns
     * {@code null} when that cannot be decided without classes nobody indexed.
     */
    public Boolean isAssignable(Type sub, Type sup) {
        if (sub.equals(sup)) {
            return Boolean.TRUE;
        }
        if (!isReference(sub) || !isReference(sup)) {
            return Boolean.FALSE;
        }
        if (sup.getDescriptor().equals("Ljava/lang/Object;")) {
            return Boolean.TRUE;
        }
        if (sub.getSort() == Type.ARRAY || sup.getSort() == Type.ARRAY) {
            if (sub.getSort() == Type.ARRAY && sup.getSort() == Type.ARRAY && sub.getDimensions() == sup.getDimensions()) {
                return isAssignable(sub.getElementType(), sup.getElementType());
            }
            return Boolean.FALSE;
        }
        return isSubclass(sub.getInternalName(), sup.getInternalName());
    }

    private Boolean isSubclass(String sub, String sup) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(sub);
        boolean unknown = false;
        while (!queue.isEmpty()) {
            String cls = queue.poll();
            if (cls.equals(sup)) {
                return Boolean.TRUE;
            }
            if (!seen.add(cls)) {
                continue;
            }
            ClassInfo info = index.info(cls);
            if (info != null) {
                queue.addAll(info.supertypes());
                continue;
            }
            Class<?> jdk = jdkClass(cls);
            Class<?> jdkSup = jdkClass(sup);
            if (jdk != null) {
                if (jdkSup != null && jdkSup.isAssignableFrom(jdk)) {
                    return Boolean.TRUE;
                }
            } else {
                unknown = true;
            }
        }
        return unknown ? null : Boolean.FALSE;
    }

    private static boolean isReference(Type t) {
        return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
    }

    static boolean isJdkName(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/");
    }

    private static Class<?> jdkClass(String internalName) {
        if (!isJdkName(internalName)) {
            return null;
        }
        try {
            // The bootstrap loader: JDK classes only, on Java 8 and on 9+ alike
            return Class.forName(internalName.replace('/', '.'), false, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean jdkHasMethod(Class<?> cls, String name, String desc) {
        if ("<init>".equals(name)) {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (Type.getConstructorDescriptor(c).equals(desc)) {
                    return true;
                }
            }
            return false;
        }
        for (Class<?> c : jdkLineage(cls)) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jdkHasMethodName(Class<?> cls, String name) {
        for (Class<?> c : jdkLineage(cls)) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jdkHasField(Class<?> cls, String name, String desc) {
        for (Class<?> c : jdkLineage(cls)) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && Type.getDescriptor(f.getType()).equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jdkHasFieldName(Class<?> cls, String name) {
        for (Class<?> c : jdkLineage(cls)) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Class<?>> jdkLineage(Class<?> cls) {
        List<Class<?>> result = new ArrayList<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(cls);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (result.contains(c)) {
                continue;
            }
            result.add(c);
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            } else if (c.isInterface()) {
                queue.add(Object.class);
            }
            queue.addAll(Arrays.asList(c.getInterfaces()));
        }
        return result;
    }
}
