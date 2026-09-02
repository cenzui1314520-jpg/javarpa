package com.rpa.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.rpa.engine.App;

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
        for (AccessibilityNodeInfo r : roots) {
            hit = bfs(r, c);
            if (hit != null) break;
        }
        if (hit != null) return hit;
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
            if (matches(node, c)) result.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
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

    private static AccessibilityNodeInfo bfs(AccessibilityNodeInfo root, Criteria c) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (matches(node, c)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
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
                                try {
                                    queue.put(soft);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            try {
                                queue.put(null);
                            } catch (InterruptedException ignored) {
                            }
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
}
