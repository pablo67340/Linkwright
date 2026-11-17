package com.linkwright.link;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough of Java's generic signatures to prove what a wrapper holds: which type a
 * {@code Supplier}, {@code Optional} or {@code Holder} hands back for one particular field or
 * method. Anything the signatures do not pin down to a concrete class comes back {@code null}.
 */
final class Signatures {
    /** One generic type. */
    static final class GType {
        /** 'L' class, 'T' type variable, '[' array, 'P' primitive, '*' any, '+' extends, '-' super. */
        final char kind;
        /** Internal class name, variable name or primitive descriptor. */
        final String name;
        /** Type arguments of a class, or the element of an array, or the bound of a wildcard. */
        final List<GType> args;

        GType(char kind, String name, List<GType> args) {
            this.kind = kind;
            this.name = name;
            this.args = args;
        }
    }

    private static final int MAX_DEPTH = 16;

    private final String s;
    private int pos;

    private Signatures(String s) {
        this.s = s;
    }

    /** The type of a field, generic when it has a signature. */
    static GType fieldType(ClassInfo.Member field) {
        return parseType(field.signature() != null ? field.signature() : field.desc());
    }

    /** The return type of a method, generic when it has a signature. */
    static GType returnType(ClassInfo.Member method) {
        if (method.signature() == null) {
            return parseType(Type.getReturnType(method.desc()).getDescriptor());
        }
        try {
            Signatures p = new Signatures(method.signature());
            p.formals();
            p.expect('(');
            while (p.peek() != ')') {
                p.type();
            }
            p.pos++;
            return p.type();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    static GType parseType(String signature) {
        try {
            Signatures p = new Signatures(signature);
            GType t = p.type();
            return p.pos == signature.length() ? t : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The type variables a class declares, in order; empty when the class has no signature. */
    static List<String> typeParameters(ClassInfo info) {
        if (info.signature == null) {
            return Collections.emptyList();
        }
        try {
            return new Signatures(info.signature).formals();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * The concrete class {@code type} holds as type argument {@code argIndex} of {@code target},
     * found by walking the generic supertypes. {@code null} when that is not provable.
     */
    static Type argumentOf(GType type, String target, int argIndex, ClassIndex index) {
        GType held = find(type, target, argIndex, index, 0);
        if (held != null && held.kind == '+') {
            held = held.args.get(0);
        }
        return held == null ? null : erasure(held);
    }

    private static GType find(GType type, String target, int argIndex, ClassIndex index, int depth) {
        if (type == null || type.kind != 'L' || depth > MAX_DEPTH) {
            return null;
        }
        if (type.name.equals(target)) {
            return argIndex < type.args.size() ? type.args.get(argIndex) : null;
        }
        ClassInfo info = index.info(type.name);
        if (info == null) {
            return null;
        }
        List<String> formals;
        List<GType> supers = new ArrayList<>();
        if (info.signature == null) {
            formals = Collections.emptyList();
            for (String sup : info.supertypes()) {
                supers.add(new GType('L', sup, Collections.<GType>emptyList()));
            }
        } else {
            try {
                Signatures p = new Signatures(info.signature);
                formals = p.formals();
                while (p.pos < p.s.length()) {
                    supers.add(p.type());
                }
            } catch (RuntimeException malformed) {
                return null;
            }
        }
        Map<String, GType> binding = new HashMap<>();
        if (!formals.isEmpty() && type.args.size() == formals.size()) {
            for (int i = 0; i < formals.size(); i++) {
                binding.put(formals.get(i), type.args.get(i));
            }
        }
        for (GType sup : supers) {
            GType found = find(substitute(sup, binding), target, argIndex, index, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static GType substitute(GType t, Map<String, GType> binding) {
        if (t.kind == 'T') {
            GType bound = binding.get(t.name);
            return bound != null ? bound : t;
        }
        if (t.args.isEmpty()) {
            return t;
        }
        List<GType> args = new ArrayList<>(t.args.size());
        for (GType a : t.args) {
            args.add(substitute(a, binding));
        }
        return new GType(t.kind, t.name, args);
    }

    /** The runtime type, as long as nothing in it is a type variable or wildcard. */
    private static Type erasure(GType t) {
        switch (t.kind) {
            case 'L':
                return Type.getObjectType(t.name);
            case 'P':
                return Type.getType(t.name);
            case '[':
                Type element = erasure(t.args.get(0));
                return element == null ? null : Type.getType("[" + element.getDescriptor());
            default:
                return null;
        }
    }

    private char peek() {
        return s.charAt(pos);
    }

    private void expect(char c) {
        if (s.charAt(pos++) != c) {
            throw new IllegalArgumentException(s);
        }
    }

    private List<String> formals() {
        List<String> names = new ArrayList<>();
        if (pos >= s.length() || peek() != '<') {
            return names;
        }
        pos++;
        while (peek() != '>') {
            int colon = s.indexOf(':', pos);
            names.add(s.substring(pos, colon));
            pos = colon;
            while (pos < s.length() && peek() == ':') {
                pos++;
                char c = peek();
                if (c == 'L' || c == 'T' || c == '[') {
                    type();
                }
            }
        }
        pos++;
        return names;
    }

    private GType type() {
        char c = peek();
        switch (c) {
            case 'L':
                return classType();
            case 'T': {
                int end = s.indexOf(';', pos);
                GType var = new GType('T', s.substring(pos + 1, end), Collections.<GType>emptyList());
                pos = end + 1;
                return var;
            }
            case '[':
                pos++;
                return new GType('[', null, Collections.singletonList(type()));
            default:
                if ("ZBCSIJFDV".indexOf(c) < 0) {
                    throw new IllegalArgumentException(s);
                }
                pos++;
                return new GType('P', String.valueOf(c), Collections.<GType>emptyList());
        }
    }

    private GType classType() {
        pos++;
        StringBuilder name = new StringBuilder();
        List<GType> args = Collections.emptyList();
        while (true) {
            char c = s.charAt(pos++);
            if (c == ';') {
                break;
            }
            if (c == '<') {
                args = new ArrayList<>();
                while (peek() != '>') {
                    args.add(argument());
                }
                pos++;
            } else if (c == '.') {
                // An inner class: the outer class's arguments no longer apply to what follows
                name.append('$');
                args = Collections.emptyList();
            } else {
                name.append(c);
            }
        }
        return new GType('L', name.toString(), args);
    }

    private GType argument() {
        char c = peek();
        if (c == '*') {
            pos++;
            return new GType('*', null, Collections.<GType>emptyList());
        }
        if (c == '+' || c == '-') {
            pos++;
            return new GType(c, null, Collections.singletonList(type()));
        }
        return type();
    }
}
