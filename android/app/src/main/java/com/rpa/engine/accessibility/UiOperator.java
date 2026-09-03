package com.rpa.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.rpa.engine.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Node lookup and gesture helpers on top of the accessibility service. */
public final class UiOperator {
    private UiOperator() {}

    public static class Criteria {
        public String text;
        public String textContains;
        public String idSuffix;
        public String desc;
        public String className;
        public Boolean clickable;
    }

    public static AccessibilityNodeInfo findFirst(Criteria c) {
        AutoAccessibilityService service = AutoAccessibilityService.get();
        if (service == null) return null;
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) roots.add(root);
        if (Build.VERSION.SDK_INT >= 21) {
            for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && !containsRef(roots, r)) roots.add(r);
            }
        }
        AccessibilityNodeInfo hit = null;
        int hitRootIndex = -1;
        for (int ri = 0; ri < roots.size() && hit == null; ri++) {
            hit = bfs(roots.get(ri), c);
            hitRootIndex = ri;
        }
        if (hit != null) {
            // 命中：回收其余根节点；命中链路上的节点仍归调用方使用
            for (int i = 0; i < roots.size(); i++) {
                if (i != hitRootIndex) recycleNode(roots.get(i));
            }
            return hit;
        }
        recycleAll(roots); // 未命中时释放根节点；命中节点交调用方使用后 recycle
        return null;
    }

    private static boolean containsRef(List<AccessibilityNodeInfo> list, AccessibilityNodeInfo node) {
        // AccessibilityNodeInfo 未重写 equals，按引用去重
        for (AccessibilityNodeInfo n : list) {
            if (n == node) return true;
        }
        return false;
    }

    public static List<AccessibilityNodeInfo> findAll(Criteria c) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        AutoAccessibilityService service = AutoAccessibilityService.get();
        if (service == null) return result;
        // 与 findFirst 保持一致：活动窗口 + 全部分屏/悬浮窗
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) roots.add(root);
        if (Build.VERSION.SDK_INT >= 21) {
            for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && !containsRef(roots, r)) roots.add(r);
            }
        }
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.addAll(roots);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            if (matches(node, c)) {
                result.add(node);
            } else {
                recycleNode(node); // 未命中的中间节点即时回收
            }
        }
        return result;
    }

    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (Build.VERSION.SDK_INT >= 33) return; // 33+ recycle 为 no-op
        for (AccessibilityNodeInfo n : nodes) {
            try {
                n.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    /** API<33 下释放单个节点；33+ recycle 已是 no-op。 */
    public static void recycleNode(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT >= 33) return;
        try {
            node.recycle();
        } catch (Exception ignored) {
        }
    }

    private static AccessibilityNodeInfo bfs(AccessibilityNodeInfo root, Criteria c) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            AccessibilityNodeInfo hit = null;
            if (matches(node, c)) {
                hit = node;
            } else {
                // 未命中的节点在取完子节点后立即回收，避免长任务耗尽节点池
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
                recycleNode(node);
            }
            if (hit != null) {
                recycleAll(new ArrayList<>(queue));
                return hit;
            }
        }
        return null;
    }

    private static boolean matches(AccessibilityNodeInfo node, Criteria c) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String viewId = node.getViewIdResourceName();
        if (c.text != null && (text == null || !c.text.contentEquals(text))) return false;
        if (c.textContains != null && (text == null || !text.toString().contains(c.textContains)))
            return false;
        if (c.desc != null && (desc == null || !c.desc.contentEquals(desc))) return false;
        if (c.idSuffix != null && (viewId == null || !viewId.endsWith(c.idSuffix))) return false;
        if (c.className != null
                && (node.getClassName() == null || !c.className.contentEquals(node.getClassName())))
            return false;
        if (c.clickable != null && node.isClickable() != c.clickable) return false;
        return true;
    }

    public static boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int depth = 0;
        while (target != null && !target.isClickable() && depth < 5) {
            target = target.getParent();
            depth++;
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return tap(rect.centerX(), rect.centerY());
    }

    public static boolean inputText(AccessibilityNodeInfo node, String text) {
        Bundle bundle = new Bundle();
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
    }

    public static boolean scrollForward(AccessibilityNodeInfo node) {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    public static boolean tap(int x, int y) {
        return gesture(x, y, x, y, 50);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return gesture(x1, y1, x2, y2, Math.max(durationMs, 50));
    }

    private static boolean gesture(int x1, int y1, int x2, int y2, int durationMs) {
        AutoAccessibilityService service = AutoAccessibilityService.get();
        if (service == null) return false;
        android.accessibilityservice.GestureDescription.Builder builder =
                new android.accessibilityservice.GestureDescription.Builder();
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, durationMs));
        return service.dispatchGesture(builder.build(), null, null);
    }

    public static boolean back() {
        AutoAccessibilityService service = AutoAccessibilityService.get();
        return service != null
                && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public static boolean home() {
        AutoAccessibilityService service = AutoAccessibilityService.get();
        return service != null
                && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    public static boolean launchApp(String pkg) {
        try {
            Intent intent = App.get().getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            App.get().startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Takes a screenshot via the accessibility API (Android 11+ only). */
    public static android.graphics.Bitmap takeScreenshot() {
        AutoAccessibilityService service = AutoAccessibilityService.get();
        if (service == null || Build.VERSION.SDK_INT < 30) return null;
        try {
            final java.util.concurrent.SynchronousQueue<android.graphics.Bitmap> queue =
                    new java.util.concurrent.SynchronousQueue<>();
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    r -> r.run(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            android.graphics.Bitmap soft = null;
                            try {
                                android.graphics.Bitmap hard = android.graphics.Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (hard != null) {
                                    soft = hard.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                    hard.recycle();
                                }
                            } finally {
                                result.getHardwareBuffer().close();
                                // 非阻塞投放：若等待方已超时返回，直接丢弃迟到结果，
                                // 绝不能在主线程（框架回调线程）上阻塞等待消费者
                                queue.offer(soft);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            queue.offer(null);
                        }
                    });
            // 回调异常时不让脚本线程永久挂起
            return queue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保留停止信号给上层判断
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> screen() {
        Map<String, Object> m = new HashMap<>();
        m.put("width", App.get().getResources().getDisplayMetrics().widthPixels);
        m.put("height", App.get().getResources().getDisplayMetrics().heightPixels);
        m.put("density", App.get().getResources().getDisplayMetrics().density);
        return m;
    }

    // UI 检查器防失控上限：超深/超多节点直接截断，序列化结果已足够定位控件
    private static final int MAX_DUMP_NODES = 4000;
    private static final int MAX_DUMP_DEPTH = 60;

    /** 全窗口控件树序列化（调试/UI 检查器用）：{roots:[节点...], nodeCount}。 */
    public static JSONObject dumpTree() throws org.json.JSONException {
        JSONObject out = new JSONObject();
        JSONArray rootsArr = new JSONArray();
        int[] counter = {0};
        AutoAccessibilityService service = AutoAccessibilityService.get();
        if (service != null) {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) roots.add(root);
            if (Build.VERSION.SDK_INT >= 21) {
                for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && !containsRef(roots, r)) roots.add(r);
                }
            }
            for (AccessibilityNodeInfo r : roots) {
                JSONObject j = dumpNode(r, 0, counter);
                recycleNode(r);
                if (j != null) rootsArr.put(j);
            }
        }
        out.put("roots", rootsArr);
        out.put("nodeCount", counter[0]);
        return out;
    }

    private static JSONObject dumpNode(AccessibilityNodeInfo node, int depth, int[] counter)
            throws org.json.JSONException {
        if (node == null || counter[0] >= MAX_DUMP_NODES) return null;
        counter[0]++;
        JSONObject j = new JSONObject();
        CharSequence text = node.getText();
        if (text != null) j.put("text", text.toString());
        CharSequence desc = node.getContentDescription();
        if (desc != null) j.put("desc", desc.toString());
        String viewId = node.getViewIdResourceName();
        if (viewId != null) j.put("id", viewId);
        CharSequence cls = node.getClassName();
        if (cls != null) j.put("className", cls.toString());
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        JSONObject r = new JSONObject();
        r.put("x", rect.left);
        r.put("y", rect.top);
        r.put("w", rect.width());
        r.put("h", rect.height());
        j.put("rect", r);
        j.put("clickable", node.isClickable());
        j.put("longClickable", node.isLongClickable());
        j.put("scrollable", node.isScrollable());
        j.put("enabled", node.isEnabled());
        j.put("visibleToUser", node.isVisibleToUser());
        j.put("childCount", node.getChildCount());
        JSONArray children = new JSONArray();
        if (depth < MAX_DUMP_DEPTH) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                JSONObject cj = dumpNode(child, depth + 1, counter);
                recycleNode(child); // 序列化完即回收，避免一次 dump 耗尽节点池
                if (cj != null) children.put(cj);
            }
        }
        j.put("children", children);
        return j;
    }

    /**
     * 截图并压缩为 JPEG base64（调试上行用）。
     *
     * @return {width, height(原始屏幕尺寸), image(base64 JPEG)}；失败返回 null
     */
    public static JSONObject captureJpeg(int maxWidth, int quality) throws org.json.JSONException {
        android.graphics.Bitmap bmp = takeScreenshot();
        if (bmp == null) return null;
        try {
            JSONObject out = new JSONObject();
            out.put("width", bmp.getWidth());
            out.put("height", bmp.getHeight());
            android.graphics.Bitmap scaled = bmp;
            if (bmp.getWidth() > maxWidth) {
                int h = Math.round(bmp.getHeight() * (maxWidth / (float) bmp.getWidth()));
                scaled = android.graphics.Bitmap.createScaledBitmap(bmp, maxWidth, h, true);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos);
            if (scaled != bmp) {
                scaled.recycle();
                bmp.recycle();
            }
            out.put("image", android.util.Base64.encodeToString(
                    bos.toByteArray(), android.util.Base64.NO_WRAP));
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
