package com.rpa.engine.api;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Screenshot wrapper for JS. Pixels are copied into a Java int array at creation
 * so the system bitmap is recycled immediately (no native memory held).
 */
public class ImageApi {
    private final int[] pixels;
    private final int width;
    private final int height;
    private final File baseDir;

    public ImageApi(Bitmap bitmap, File baseDir) {
        this.width = bitmap.getWidth();
        this.height = bitmap.getHeight();
        this.pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        this.baseDir = baseDir;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String pixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return null;
        int c = pixels[y * width + x];
        return String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c));
    }

    /** Saves this screenshot as PNG under the script package dir. */
    public boolean save(String relPath) {
        try {
            File out = resolveInBase(relPath);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            Bitmap bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                bmp.recycle();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Finds first pixel close to colorHex ("#RRGGBB") within threshold. */
    public Map<String, Integer> findColor(String colorHex, int threshold) {
        Integer target = parseColor(colorHex);
        if (target == null) return null;
        int tr = Color.red(target), tg = Color.green(target), tb = Color.blue(target);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = pixels[y * width + x];
                if (Math.abs(Color.red(c) - tr) <= threshold
                        && Math.abs(Color.green(c) - tg) <= threshold
                        && Math.abs(Color.blue(c) - tb) <= threshold) {
                    return point(x, y);
                }
            }
        }
        return null;
    }

    /** Template match against an image inside the script package (e.g. "res/btn.png"). */
    public Map<String, Integer> findImage(String relPath, int threshold) {
        Bitmap tpl;
        try {
            tpl = BitmapFactory.decodeFile(resolveInBase(relPath).getAbsolutePath());
        } catch (java.io.IOException e) {
            return null;
        }
        if (tpl == null) return null;
        int tw = tpl.getWidth(), th = tpl.getHeight();
        if (tw == 0 || th == 0 || tw > width || th > height) {
            tpl.recycle();
            return null;
        }
        int[] tp = new int[tw * th];
        tpl.getPixels(tp, 0, tw, 0, 0, tw, th);
        tpl.recycle();

        int anchor = tp[0];
        for (int y = 0; y <= height - th; y++) {
            for (int x = 0; x <= width - tw; x++) {
                if (!colorClose(pixels[y * width + x], anchor, threshold)) continue;
                if (matchAt(x, y, tp, tw, th, threshold)) {
                    return point(x, y);
                }
            }
        }
        return null;
    }

    private boolean matchAt(int sx, int sy, int[] tp, int tw, int th, int threshold) {
        for (int j = 0; j < th; j++) {
            int sRow = (sy + j) * width + sx;
            int tRow = j * tw;
            for (int i = 0; i < tw; i++) {
                if (!colorClose(pixels[sRow + i], tp[tRow + i], threshold)) return false;
            }
        }
        return true;
    }

    private static boolean colorClose(int a, int b, int threshold) {
        if (threshold <= 0) return a == b;
        return Math.abs(Color.red(a) - Color.red(b)) <= threshold
                && Math.abs(Color.green(a) - Color.green(b)) <= threshold
                && Math.abs(Color.blue(a) - Color.blue(b)) <= threshold;
    }

    /** @return null 表示非法颜色；纯黑(#000000)是合法目标色，不能用 0 当失败哨兵 */
    private static Integer parseColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) return Color.parseColor("#" + h);
            if (h.length() == 8) return Color.parseColor("#" + h);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 相对路径必须落在脚本包目录内，防止 ../ 越界读写。 */
    private File resolveInBase(String relPath) throws java.io.IOException {
        File f = new File(baseDir, relPath);
        String canonical = f.getCanonicalPath();
        String base = baseDir.getCanonicalPath();
        if (!canonical.startsWith(base + File.separator) && !canonical.equals(base)) {
            throw new java.io.IOException("路径越界: " + relPath);
        }
        return f;
    }

    private static Map<String, Integer> point(int x, int y) {
        Map<String, Integer> m = new HashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }
}
