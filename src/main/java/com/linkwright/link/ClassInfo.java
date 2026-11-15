package com.linkwright.link;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The shape of one class: its supertypes and member signatures, without any code. */
public final class ClassInfo {
    public record Member(String owner, String name, String desc, int access) {
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
    public final Map<String, Member> methods;
    public final Map<String, Member> fields;

    private ClassInfo(String name, String superName, List<String> interfaces, int access,
                      Map<String, Member> methods, Map<String, Member> fields) {
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces;
        this.access = access;
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
        Map<String, Member> methods = new LinkedHashMap<>();
        Map<String, Member> fields = new LinkedHashMap<>();
        String[] header = new String[2];
        List<String> interfaces = new ArrayList<>();
        int[] access = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int acc, String name, String signature, String superName, String[] ifaces) {
                header[0] = name;
                header[1] = superName;
                access[0] = acc;
                if (ifaces != null) {
                    interfaces.addAll(List.of(ifaces));
                }
            }

            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String signature, Object value) {
                fields.put(name + ":" + desc, new Member(header[0], name, desc, acc));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String signature, String[] exceptions) {
                methods.put(name + desc, new Member(header[0], name, desc, acc));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassInfo(header[0], header[1], List.copyOf(interfaces), access[0], methods, fields);
    }
}
