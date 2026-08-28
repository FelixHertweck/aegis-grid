package com.beanit.iec61850bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge into {@code iec61850bean}'s package-private model internals, used by {@code
 * Iec61850ControlModelNormalizer} to rewrite the control model the proxy exposes downstream.
 *
 * <p>This class lives in {@code com.beanit.iec61850bean} on purpose: {@link ModelNode#children} and
 * {@link ModelNode#setParent(ModelNode)} are package-private, and reflection from {@code
 * de.felixhertweck.otproxy} would be more fragile than these three tightly-scoped helpers.
 */
public final class ProxyControlModelSupport {

    private ProxyControlModelSupport() {}

    /** Returns true if {@code node} has a direct child with the given name. */
    public static boolean hasChild(ModelNode node, String childName) {
        return node.children != null && node.children.containsKey(childName);
    }

    /** Removes the direct child with the given name; no-op if it is absent. */
    public static void removeChild(ModelNode node, String childName) {
        if (node.children != null) {
            node.children.remove(childName);
        }
    }

    /**
     * Ensures {@code controlCo} — the FC=CO view of a control data object — carries an {@code SBO}
     * attribute as its first child, so {@code iec61850bean}'s server accepts a normal-security
     * Select on it. No-op if an {@code SBO} child already exists.
     */
    public static void ensureSboAttribute(FcModelNode controlCo) {
        if (hasChild(controlCo, "SBO")) {
            return;
        }
        ObjectReference sboRef = new ObjectReference(controlCo.getReference().toString() + ".SBO");
        // 129 = maximum ObjectReference length in IEC 61850-8-1; the server only ever puts
        // "" or "success" here.
        BdaVisibleString sbo = new BdaVisibleString(sboRef, Fc.CO, null, 129, false, false);
        sbo.setValue("");
        sbo.setParent(controlCo);

        // SBO must precede Oper in the control DO (IEC 61850-7-2); rebuild the map SBO-first.
        Map<String, ModelNode> reordered = new LinkedHashMap<>();
        reordered.put("SBO", sbo);
        reordered.putAll(controlCo.children);
        controlCo.children.clear();
        controlCo.children.putAll(reordered);
    }
}
