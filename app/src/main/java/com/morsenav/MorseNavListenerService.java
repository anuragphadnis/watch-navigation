package com.morsenav;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Receives Google Maps notifications and hands their text to {@link NavEngine}. */
public class MorseNavListenerService extends NotificationListenerService {

    static final String MAPS_PACKAGE = "com.google.android.apps.maps";

    private static final String[] TEXT_EXTRAS = {
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
    };

    /** Log the navigation notification's layout once per process. */
    private static boolean loggedExtras;

    @Override
    public void onListenerConnected() {
        DebugLog.add("Notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        DebugLog.add("Notification listener disconnected, requesting rebind");
        requestRebind(new ComponentName(this, MorseNavListenerService.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!MAPS_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        List<String> fields = extractText(notification);
        if (fields.isEmpty()) {
            DebugLog.add("MAPS notification with no text; extras=" + describeExtras(notification.extras));
            return;
        }
        if (!loggedExtras && "navigation".equals(notification.category)) {
            loggedExtras = true;
            DebugLog.add("MAPS navigation extras=" + describeExtras(notification.extras));
        }
        NavEngine.get(this).onMapsNotification(sbn.getKey(), fields, notification.getLargeIcon(),
                RideService.isRunning());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!MAPS_PACKAGE.equals(sbn.getPackageName())) return;
        NavEngine.get(this).onMapsNotificationRemoved(sbn.getKey());
    }

    /**
     * Collects every text field of the notification, in display order. Maps' layout varies between
     * versions, so rather than assume where the instruction sits we read them all.
     */
    private static List<String> extractText(Notification notification) {
        List<String> fields = new ArrayList<>();
        Bundle extras = notification.extras;
        if (extras != null) {
            for (String name : TEXT_EXTRAS) {
                addField(fields, extras.getCharSequence(name));
            }
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) addField(fields, line);
            }
        }
        addField(fields, notification.tickerText);
        return fields;
    }

    /** Extra names with value types, to see where Maps puts the maneuver when it isn't in the text. */
    static String describeExtras(Bundle extras) {
        if (extras == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            sb.append(key).append('=').append(value == null ? "null" : value.getClass().getSimpleName()).append(", ");
        }
        return sb.append('}').toString();
    }

    private static void addField(List<String> fields, CharSequence value) {
        if (value == null) return;
        String s = value.toString().trim();
        if (!s.isEmpty() && !fields.contains(s)) fields.add(s);
    }
}
