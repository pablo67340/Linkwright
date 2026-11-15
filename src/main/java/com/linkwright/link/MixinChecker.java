package com.linkwright.link;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a mixin's targets in the dependency: the target classes, the methods its injectors
 * select, the members named in {@code @At} targets, and its shadows, overwrites, accessors and
 * invokers. Injectors marked {@code require = 0} are skipped, since they are allowed to miss.
 */
final class MixinChecker {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    private final Hierarchy hierarchy;

    MixinChecker(Hierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    static boolean isMixin(ClassNode node) {
        return annotation(node.invisibleAnnotations, MIXIN) != null || annotation(node.visibleAnnotations, MIXIN) != null;
    }

    void check(ClassNode node, LinkChecker.Pass pass) {
        if (annotation(node.invisibleAnnotations, PSEUDO) != null) {
            return;
        }
        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);
        if (mixin == null) {
            mixin = annotation(node.visibleAnnotations, MIXIN);
        }
        List<String> targets = new ArrayList<>();
        for (Object value : listValue(mixin, "value")) {
            if (value instanceof Type t) {
                targets.add(t.getInternalName());
            }
        }
        for (Object value : listValue(mixin, "targets")) {
            if (value instanceof String s) {
                targets.add(s.replace('.', '/'));
            }
        }
        String where = "mixin " + node.name.replace('/', '.');
        List<String> live = new ArrayList<>();
        for (String target : targets) {
            if (!pass.inDependency(target)) {
                continue;
            }
            pass.checked++;
            if (hierarchy.index().contains(target)) {
                live.add(target);
            } else {
                pass.problem(Problem.Kind.MIXIN_TARGET, target.replace('/', '.'), where, null);
            }
        }
        if (live.isEmpty()) {
            return;
        }

        for (MethodNode method : node.methods) {
            String memberWhere = where + "." + method.name;
            for (AnnotationNode ann : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (SHADOW.equals(ann.desc)) {
                    String name = stripPrefix(method.name, stringValue(ann, "prefix", "shadow$"));
                    forEachTarget(live, t -> hierarchy.resolveMethod(t, name, method.desc), name + method.desc, memberWhere, pass);
                } else if (OVERWRITE.equals(ann.desc)) {
                    forEachTarget(live, t -> hierarchy.resolveMethod(t, method.name, method.desc), method.name + method.desc, memberWhere, pass);
                } else if (ACCESSOR.equals(ann.desc)) {
                    List<String> names = accessorNames(ann, method.name);
                    if (!names.isEmpty()) {
                        forEachTarget(live, t -> anyField(t, names), names.get(0), memberWhere, pass);
                    }
                } else if (INVOKER.equals(ann.desc)) {
                    String name = invokerName(ann, method.name);
                    if (name != null) {
                        forEachTarget(live, t -> hierarchy.resolveMethodName(t, name), name, memberWhere, pass);
                    }
                } else if (INJECTORS.contains(ann.desc) && !Integer.valueOf(0).equals(value(ann, "require"))) {
                    checkInjector(ann, live, memberWhere, pass);
                }
            }
        }
        for (FieldNode field : node.fields) {
            for (AnnotationNode ann : annotations(field.visibleAnnotations, field.invisibleAnnotations)) {
                if (SHADOW.equals(ann.desc)) {
                    String name = stripPrefix(field.name, stringValue(ann, "prefix", "shadow$"));
                    forEachTarget(live, t -> hierarchy.resolveField(t, name, field.desc), name + ":" + field.desc,
                            where + "." + field.name, pass);
                }
            }
        }
    }

    private void checkInjector(AnnotationNode ann, List<String> targets, String where, LinkChecker.Pass pass) {
        for (Object selector : listValue(ann, "method")) {
            if (!(selector instanceof String s) || s.contains("*") || s.startsWith("/") || s.isEmpty()) {
                continue;
            }
            if (s.startsWith("L") && s.indexOf(';') > 0 && s.indexOf(';') < s.indexOf('(') ) {
                s = s.substring(s.indexOf(';') + 1);
            }
            int paren = s.indexOf('(');
            String name = paren < 0 ? s : s.substring(0, paren);
            String desc = paren < 0 ? null : s.substring(paren);
            forEachTarget(targets, t -> desc == null || !desc.contains(")") ? hierarchy.resolveMethodName(t, name)
                    : hierarchy.resolveMethod(t, name, desc), s, where, pass);
        }
        Object at = value(ann, "at");
        List<Object> ats = at instanceof List<?> list ? new ArrayList<>(list) : at == null ? List.of() : List.of(at);
        for (Object a : ats) {
            if (a instanceof AnnotationNode atNode) {
                checkAtTarget(stringValue(atNode, "target", null), where, pass);
            }
        }
    }

    /** {@code @At} targets look like {@code Lowner;name(desc)ret} or {@code Lowner;name:desc}. */
    private void checkAtTarget(String target, String where, LinkChecker.Pass pass) {
        if (target == null || !target.startsWith("L") || target.indexOf(';') < 0 || target.contains("*")) {
            return;
        }
        String owner = target.substring(1, target.indexOf(';'));
        String rest = target.substring(target.indexOf(';') + 1);
        if (!pass.inDependency(owner)) {
            return;
        }
        if (!hierarchy.index().contains(owner)) {
            pass.checked++;
            pass.problem(Problem.Kind.MIXIN_TARGET, owner.replace('/', '.'), where, "named in an @At target");
            return;
        }
        int paren = rest.indexOf('(');
        int colon = rest.indexOf(':');
        Hierarchy.Resolution resolution;
        if (paren > 0) {
            resolution = hierarchy.resolveMethod(owner, rest.substring(0, paren), rest.substring(paren));
        } else if (colon > 0) {
            resolution = hierarchy.resolveField(owner, rest.substring(0, colon), rest.substring(colon + 1));
        } else if (!rest.isEmpty()) {
            resolution = hierarchy.resolveFieldName(owner, rest);
        } else {
            return;
        }
        pass.resolution(resolution);
        if (resolution == Hierarchy.Resolution.MISSING) {
            pass.problem(Problem.Kind.MIXIN_MEMBER, owner.replace('/', '.') + "." + rest, where, "named in an @At target");
        }
    }

    private void forEachTarget(List<String> targets, java.util.function.Function<String, Hierarchy.Resolution> lookup,
                               String member, String where, LinkChecker.Pass pass) {
        for (String target : targets) {
            Hierarchy.Resolution resolution = lookup.apply(target);
            pass.resolution(resolution);
            if (resolution == Hierarchy.Resolution.MISSING) {
                pass.problem(Problem.Kind.MIXIN_MEMBER, target.replace('/', '.') + "." + member, where, null);
            }
        }
    }

    private Hierarchy.Resolution anyField(String target, List<String> names) {
        Hierarchy.Resolution best = Hierarchy.Resolution.MISSING;
        for (String name : names) {
            Hierarchy.Resolution r = hierarchy.resolveFieldName(target, name);
            if (r == Hierarchy.Resolution.FOUND) {
                return r;
            }
            if (r == Hierarchy.Resolution.UNKNOWN) {
                best = r;
            }
        }
        return best;
    }

    private static List<String> accessorNames(AnnotationNode ann, String methodName) {
        String explicit = stringValue(ann, "value", "");
        if (!explicit.isEmpty()) {
            return List.of(explicit);
        }
        for (String prefix : List.of("get", "set", "is")) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String bare = methodName.substring(prefix.length());
                String lower = Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
                return List.of(lower, bare, bare.toUpperCase(Locale.ROOT));
            }
        }
        return List.of();
    }

    private static String invokerName(AnnotationNode ann, String methodName) {
        String explicit = stringValue(ann, "value", "");
        if (!explicit.isEmpty()) {
            return explicit;
        }
        for (String prefix : List.of("call", "invoke")) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String bare = methodName.substring(prefix.length());
                return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
            }
        }
        return null;
    }

    private static String stripPrefix(String name, String prefix) {
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) {
            all.addAll(visible);
        }
        if (invisible != null) {
            all.addAll(invisible);
        }
        return all;
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        if (list != null) {
            for (AnnotationNode ann : list) {
                if (desc.equals(ann.desc)) {
                    return ann;
                }
            }
        }
        return null;
    }

    private static Object value(AnnotationNode ann, String key) {
        if (ann == null || ann.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (key.equals(ann.values.get(i))) {
                return ann.values.get(i + 1);
            }
        }
        return null;
    }

    private static List<?> listValue(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        return v instanceof List<?> list ? list : v == null ? List.of() : List.of(v);
    }

    private static String stringValue(AnnotationNode ann, String key, String fallback) {
        Object v = value(ann, key);
        if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return s;
        }
        return v instanceof String s ? s : fallback;
    }
}
