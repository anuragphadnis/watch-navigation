package com.morsenav;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import java.util.HashMap;
import java.util.Map;

/**
 * Identifies the maneuver in a Maps navigation notification by comparing its large icon with Maps'
 * own maneuver drawables, loaded by name. Using Maps' drawables rather than a bundled set keeps
 * this working across Maps updates and screen densities. References are cached in memory per
 * Maps version.
 */
final class IconManeuverMatcher {

    private final Context context;
    private Map<String, int[]> references;
    private long referencesVersion = -1;

    IconManeuverMatcher(Context context) {
        this.context = context;
    }

    /** Returns null if the icon or Maps' drawables are unavailable. */
    IconMatch match(Icon icon) {
        if (icon == null) return null;
        Drawable drawable = icon.loadDrawable(context);
        if (drawable == null) return null;
        Map<String, int[]> refs = references();
        if (refs.isEmpty()) return null;
        return IconMatch.classify(inkMask(drawable), refs);
    }

    /**
     * Like {@link #alphaMask} but also handles an icon drawn on an opaque background, by treating
     * how far each pixel is from the corner colour as ink.
     */
    private static int[] inkMask(Drawable drawable) {
        int size = IconMatch.SIZE;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(bitmap));
        int[] pixels = new int[size * size];
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        bitmap.recycle();

        int opaque = 0;
        for (int p : pixels) if ((p >>> 24) > 250) opaque++;
        if (opaque < pixels.length * 9 / 10) {
            for (int i = 0; i < pixels.length; i++) pixels[i] = pixels[i] >>> 24;
            return pixels;
        }
        int bg = pixels[0];
        int[] mask = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int d = Math.abs(((p >> 16) & 0xff) - ((bg >> 16) & 0xff))
                    + Math.abs(((p >> 8) & 0xff) - ((bg >> 8) & 0xff))
                    + Math.abs((p & 0xff) - (bg & 0xff));
            mask[i] = Math.min(255, d);
        }
        return mask;
    }

    private Map<String, int[]> references() {
        long version;
        try {
            version = context.getPackageManager()
                    .getPackageInfo(MorseNavListenerService.MAPS_PACKAGE, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return new HashMap<>();
        }
        if (references != null && version == referencesVersion) return references;

        Map<String, int[]> loaded = new HashMap<>();
        try {
            Context maps = context.createPackageContext(MorseNavListenerService.MAPS_PACKAGE, 0);
            for (String name : ManeuverIcons.NAMES) {
                int id = maps.getResources().getIdentifier(name, "drawable", MorseNavListenerService.MAPS_PACKAGE);
                if (id == 0) continue;
                try {
                    Drawable d = maps.getDrawable(id);
                    if (d != null) loaded.put(name, alphaMask(d));
                } catch (RuntimeException e) {
                    DebugLog.add("Could not load " + name + ": " + e);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            DebugLog.add("Maps package context unavailable: " + e);
        }
        DebugLog.add("Loaded " + loaded.size() + "/" + ManeuverIcons.NAMES.length
                + " Maps maneuver icons (Maps version " + version + ")");
        references = loaded;
        referencesVersion = version;
        return loaded;
    }

    private static int[] alphaMask(Drawable drawable) {
        int size = IconMatch.SIZE;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(bitmap));
        int[] pixels = new int[size * size];
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        bitmap.recycle();
        for (int i = 0; i < pixels.length; i++) pixels[i] = pixels[i] >>> 24;
        return pixels;
    }
}
