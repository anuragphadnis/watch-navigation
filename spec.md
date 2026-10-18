Here is the complete Phase 1 specification and Java source code blueprint for **MorseNavigation**, fully refactored for **Java** and compatible with Android 16.

---

# Project Specification: MorseNavigation (Phase 1: Phone-Based Tactile Haptics in Java)

## 1. Executive Summary

**MorseNavigation** is a local Android utility app written in Java for two-wheeler commuters. It intercepts live turn-by-turn notification payloads from Google Maps (`com.google.android.apps.maps`) and translates directional navigation cues into precise haptic vibration patterns executed directly via the phone's built-in vibrator (carried in the rider's pocket).

---

## 2. Technical Stack & Target Environment

* **Minimum SDK:** Android 8.0 (API Level 26)
* **Target/Compiled SDK:** Android 16 (API Level 36)
* **Language:** Java
* **Core APIs:**
* `NotificationListenerService` (Background status bar monitoring)
* `ForegroundService` (Exempts background tasks from Android background throttling)
* `VibrationEffect.createWaveform()` (Sub-millisecond custom haptic cadence generation)



---

## 3. Core Haptic Vocabulary & Rules

* **Uniform Pause:** Standardized `1000ms` (1 second) pause for all slow advance rhythms.
* **Pulse Count Mapping:**
* **2 Pulses:** Left Turn (`longArrayOf(0, 250, 1000, 250)`)
* **3 Pulses:** Right Turn (`longArrayOf(0, 250, 1000, 250, 1000, 250)`)
* **4 Pulses:** U-Turn (`longArrayOf(0, 250, 1000, 250, 1000, 250, 1000, 250)`)


* **State Modifiers:**
* **Advance (Distant):** Slow cadence, soft intensity (`120` amplitude).
* **Imminent (<20m / "Now"):** Fast staccato cadence, max intensity (`255` amplitude).



---

## 4. Complete Source Code Blueprint (Java)

### File 1: `AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="MorseNavigation"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".MorseNavListenerService"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            android:foregroundServiceType="specialUse"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

    </application>
</manifest>

```

### File 2: `MorseNavListenerService.java`

```java
package com.example.morsenavigation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;

public class MorseNavListenerService extends NotificationListenerService {

    private Vibrator vibrator;

    @Override
    public void onCreate() {
        super.onCreate();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                vibrator = vibratorManager.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        startForegroundServiceWithNotification();
    }

    private void startForegroundServiceWithNotification() {
        String channelId = "MORSE_FG_CHANNEL";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "MorseNav Foreground State",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MorseNavigation Active")
                .setContentText("Listening for Google Maps turns...")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if ("com.google.android.apps.maps".equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            if (notification == null || notification.extras == null) return;

            CharSequence titleSeq = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textSeq = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            
            String title = titleSeq != null ? titleSeq.toString().toLowerCase() : "";
            String text = textSeq != null ? textSeq.toString().toLowerCase() : "";
            String combined = title + " " + text;

            boolean isImminent = combined.contains("now") || combined.contains("in 20 m") || combined.contains("in 50 ft");

            long[] timings;
            if (combined.contains("u-turn") || combined.contains("uturn")) {
                timings = isImminent 
                    ? new long[]{0, 150, 150, 150, 150, 150, 150, 150, 150, 150} 
                    : new long[]{0, 250, 1000, 250, 1000, 250, 1000, 250};
                executeVibration(timings, isImminent);
            } else if (combined.contains("left")) {
                timings = isImminent 
                    ? new long[]{0, 200, 200, 200} 
                    : new long[]{0, 250, 1000, 250};
                executeVibration(timings, isImminent);
            } else if (combined.contains("right")) {
                timings = isImminent 
                    ? new long[]{0, 200, 200, 200, 200, 200} 
                    : new long[]{0, 250, 1000, 250, 1000, 250};
                executeVibration(timings, isImminent);
            }
        }
    }

    private void executeVibration(long[] timings, boolean isImminent) {
        if (vibrator == null) return;

        int amplitudeValue = isImminent ? 255 : 120;
        int[] amplitudes = new int[timings.length];
        for (int i = 0; i < timings.length; i++) {
            amplitudes[i] = (i % 2 != 0) ? amplitudeValue : 0;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
        } else {
            vibrator.vibrate(timings, -1);
        }
    }
}

```

### File 3: `MainActivity.java`

```java
package com.example.morsenavigation;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnPermission = findViewById(R.id.btnPermission);
        btnPermission.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });
    }
}

```

### File 4: `activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout android:gravity="center" android:layout_height="match_parent" android:layout_width="match_parent" android:orientation="vertical" android:padding="24dp" xmlns:android="http://schemas.android.com/apk/res/android">

    <TextView android:layout_height="wrap_content" android:layout_marginBottom="32dp" android:layout_width="wrap_content" android:text="MorseNavigation (Java)" android:textSize="20sp" android:textStyle="bold"/>

    <Button android:id="@+id/btnPermission" android:layout_height="wrap_content" android:layout_width="match_parent" android:text="Grant Notification Access"/>

</LinearLayout>

```
