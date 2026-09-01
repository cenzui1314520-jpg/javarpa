package com.rpa.engine.engine;

/**
 * Rhino sandbox: denies every Java class except the script API surface and a few
 * immutable JDK types returned by API methods. Blocks Runtime/ProcessBuilder/reflection.
 */
public class SandboxShutter implements org.mozilla.javascript.ClassShutter {

    @Override
    public boolean visibleToScripts(String fullClassName) {
        if (fullClassName == null) return false;
        if (fullClassName.startsWith("com.rpa.engine.api.")) return true;
        switch (fullClassName) {
            case "java.lang.String":
            case "java.lang.StringBuilder":
            case "java.lang.Integer":
            case "java.lang.Long":
            case "java.lang.Double":
            case "java.lang.Float":
            case "java.lang.Boolean":
            case "java.lang.Character":
            case "java.lang.Number":
            case "java.lang.CharSequence":
            case "java.lang.Object":
            case "java.lang.Iterable":
            case "java.lang.Comparable":
            case "java.util.List":
            case "java.util.ArrayList":
            case "java.util.Map":
            case "java.util.HashMap":
            case "java.util.Set":
            case "java.util.Collection":
                return true;
            default:
                return false;
        }
    }
}
