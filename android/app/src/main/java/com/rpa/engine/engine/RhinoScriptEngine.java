package com.rpa.engine.engine;

import com.rpa.engine.api.AutoApi;
import com.rpa.engine.api.DeviceApi;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.File;
import java.lang.reflect.Method;

/** Executes cloud-delivered JS scripts inside a Rhino sandbox. */
public class RhinoScriptEngine {

    public interface Host {
        boolean isPaused();
        boolean isStopRequested();
        void requestStop();
        void onLog(String level, String content);
        void onToast(String message);
    }

    private static final ThreadLocal<Host> HOST = new ThreadLocal<>();

    public void execute(String source, String paramsJson, Host host, AutoApi auto, File baseDir)
            throws Exception {
        HOST.set(host);
        auto.setBaseDir(baseDir);
        Context cx = Context.enter();
        cx.setOptimizationLevel(-1);
        cx.setClassShutter(new SandboxShutter());
        try {
            Scriptable scope = cx.initStandardObjects();
            scope.put("auto", scope, cx.getWrapFactory().wrap(cx, scope, auto, null));
            scope.put("device", scope, cx.getWrapFactory().wrap(cx, scope, new DeviceApi(), null));
            defineFunction(cx, scope, "log");
            defineFunction(cx, scope, "toast");
            defineFunction(cx, scope, "sleep");

            String params = (paramsJson != null && paramsJson.trim().startsWith("{"))
                    ? paramsJson : "{}";
            cx.evaluateString(scope, "var params = " + params + ";", "params", 1, null);
            cx.evaluateString(scope, source, "main.js", 1, null);
        } finally {
            HOST.remove();
            Context.exit();
        }
    }

    private void defineFunction(Context cx, Scriptable scope, String name) throws Exception {
        Method method = RhinoScriptEngine.class.getMethod("js_" + name,
                Context.class, Scriptable.class, Object[].class, Function.class);
        FunctionObject fn = new FunctionObject(name, method, scope);
        ScriptableObject.putProperty(scope, name, fn);
    }

    // ---------- JS built-in functions ----------

    public static Object js_log(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        Host host = HOST.get();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(Context.toString(args[i]));
        }
        if (host != null) host.onLog("INFO", sb.toString());
        return Undefined.instance;
    }

    public static Object js_toast(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        Host host = HOST.get();
        if (host != null && args.length > 0) {
            host.onToast(Context.toString(args[0]));
        }
        return Undefined.instance;
    }

    public static Object js_sleep(Context cx, Scriptable thisObj, Object[] args, Function funObj)
            throws InterruptedException {
        long ms = args.length > 0 ? (long) Context.toNumber(args[0]) : 0;
        if (ms > 0) {
            Thread.sleep(ms);
        }
        return Undefined.instance;
    }
}
