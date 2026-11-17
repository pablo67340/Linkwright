package com.linkwright.link;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The shape of one class: its supertypes and member signatures, without any code. */
public final class ClassInfo {
    public static final class Member {
        private final String owner;
        private final String name;
        private final String desc;
        private final int access;
        private final String signature;

        public Member(String owner, String name, String desc, int access) {
            this(owner, name, desc, access, null);
        }

        public Member(String owner, String name, String desc, int access, String signature) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.access = access;
            this.signature = signature;
        }

        /** The generic signature, or {@code null} when the member's type is not generic. */
        public String signature() {
            return signature;
        }

        public String owner() {
            return owner;
        }

        public String name() {
            return name;
        }

        public String desc() {
            return desc;
        }

        public int access() {
            return access;
        }

        public boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

        public boolean isAbstract() {
            return (access & Opcodes.ACC_ABSTRACT) != 0;
        }
    }

    public final String name;
    public final String superName;
    public final List<String> interfaces;
    public final int access;
    /** The generic class signature, or {@code null} when neither the class nor its supertypes are generic. */
    public final String signature;
    public final Map<String, Member> methods;
    public final Map<String, Member> fields;

    private ClassInfo(String name, String superName, List<String> interfaces, int access, String signature,
                      Map<String, Member> methods, Map<String, Member> fields) {
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces;
        this.access = access;
        this.signature = signature;
        this.methods = methods;
        this.fields = fields;
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public Member method(String name, String desc) {
        return methods.get(name + desc);
    }

    public Member field(String name, String desc) {
        return fields.get(name + ":" + desc);
    }

    public List<String> supertypes() {
        List<String> result = new ArrayList<>(interfaces.size() + 1);
        if (superName != null) {
            result.add(superName);
        }
        result.addAll(interfaces);
        return result;
    }

    static ClassInfo parse(byte[] bytes) {
        final Map<String, Member> methods = new LinkedHashMap<>();
        final Map<String, Member> fields = new LinkedHashMap<>();
        final String[] header = new String[3];
        final List<String> interfaces = new ArrayList<>();
        final int[] access = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Asm.API) {
            @Override
            public void visit(int version, int acc, String name, String signature, String superName, String[] ifaces) {
                header[0] = name;
                header[1] = superName;
                header[2] = signature;
                access[0] = acc;
                if (ifaces != null) {
                    interfaces.addAll(Arrays.asList(ifaces));
                }
            }

            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String signature, Object value) {
                fields.put(name + ":" + desc, new Member(header[0], name, desc, acc, signature));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String signature, String[] exceptions) {
                methods.put(name + desc, new Member(header[0], name, desc, acc, signature));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassInfo(header[0], header[1], Collections.unmodifiableList(interfaces), access[0], header[2],
                methods, fields);
    }
}
