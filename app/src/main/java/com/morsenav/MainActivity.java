package com.morsenav;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private static final long REFRESH_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private TextView status;
    private TextView log;
    private Button btnRide;
    private Button btnOutput;
    private ActivityResultLauncher<String> notificationPermission;
    private ActivityResultLauncher<String> bluetoothPermission;
    private Runnable pendingWatchAction;
    private boolean pendingRideStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 16 always draws edge-to-edge; keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        btnRide = findViewById(R.id.btnRide);

        notificationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> refresh());

        bluetoothPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    Runnable action = pendingWatchAction;
                    pendingWatchAction = null;
                    if (granted && action != null) action.run();
                    // A ride starts either way; without the permission it just has no watch cues.
                    if (pendingRideStart) RideService.start(this);
                    pendingRideStart = false;
                    handler.postDelayed(this::refresh, 300);
                });
        bindWatch(R.id.btnWatchFind, () -> WatchLink.sendFindWatch(this));
        bindWatch(R.id.btnWatchReadVibration, () -> WatchLink.readVibration(this));
        bindWatch(R.id.btnWatchNavi, () -> WatchLink.naviTest(this, SmaNavi.KEY_NAVI_INFO));
        bindWatch(R.id.btnWatchGoogleNavi, () -> WatchLink.naviTest(this, SmaNavi.KEY_GOOGLE_NAVI_INFO));
        bindWatch(R.id.btnWatchNotif1, () -> WatchLink.notificationTest(this, false));
        bindWatch(R.id.btnWatchNotif2, () -> WatchLink.notificationTest(this, true));
        bindWatch(R.id.btnWatchBurst, () -> WatchLink.tickBurstTest(this));
        bindWatch(R.id.btnWatchSymbols, () -> WatchLink.symbolTest(this));
        bindWatch(R.id.btnWatchProbeVibration, () -> WatchLink.probeVibrationKeys(this));
        bindWatch(R.id.btnWatchLoveTap, () -> WatchLink.loveTapTest(this));
        bindWatch(R.id.btnWatchLoveTapContact, () -> WatchLink.loveTapWithContactTest(this));
        bindWatch(R.id.btnWatchLoveTapRemove, () -> WatchLink.removeLoveTapContacts(this));
        bindWatch(R.id.btnWatchFakeCall, () -> WatchLink.fakeCallTest(this));
        findViewById(R.id.btnPhoneCallSelf).setOnClickListener(v -> FakeCall.test(this, true));
        findViewById(R.id.btnPhoneCallManaged).setOnClickListener(v -> FakeCall.test(this, false));
        findViewById(R.id.btnRingShort).setOnClickListener(v -> FakeCall.single(this, 300));
        findViewById(R.id.btnRingLong).setOnClickListener(v -> FakeCall.single(this, 3000));
        findViewById(R.id.btnRingSweep).setOnClickListener(v -> FakeCall.sweep(this));
        bindWatch(R.id.btnVibrationModes, () -> WatchLink.vibrationModeProbe(this));
        bindWatch(R.id.btnVibrationHunt, () -> WatchLink.vibrationHunt(this));
        bindWatch(R.id.btnNotificationChain, () -> WatchLink.notificationChainTest(this));
        bindWatch(R.id.btnBuzzCut, () -> WatchLink.buzzCutTest(this));
        bindWatch(R.id.btnVibrationOffCut, () -> WatchLink.vibrationOffCutTest(this));

        findViewById(R.id.btnListenerAccess).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btnPostNotifications).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        });

        findViewById(R.id.btnBattery).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        btnRide.setOnClickListener(v -> {
            if (RideService.isRunning()) {
                RideService.stop(this);
            } else {
                if (!hasListenerAccess()) {
                    Toast.makeText(this, R.string.need_listener_access, Toast.LENGTH_LONG).show();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasBluetoothConnect()) {
                    // Ask for watch access, then start the ride either way.
                    pendingRideStart = true;
                    bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
                } else {
                    RideService.start(this);
                }
            }
            handler.postDelayed(this::refresh, 300);
        });

        btnOutput = findViewById(R.id.btnOutput);
        btnOutput.setOnClickListener(v -> {
            NavEngine engine = NavEngine.get(this);
            NavEngine.Output[] all = NavEngine.Output.values();
            engine.setOutput(all[(engine.output().ordinal() + 1) % all.length]);
            refresh();
        });

        bindTest(R.id.btnTestLeft, "Turn left onto MG Road", false);
        bindTest(R.id.btnTestLeftNow, "Turn left onto MG Road", true);
        bindTest(R.id.btnTestRight, "Turn right onto MG Road", false);
        bindTest(R.id.btnTestRightNow, "Turn right onto MG Road", true);
        bindTest(R.id.btnTestUturn, "Make a U-turn", false);
        bindTest(R.id.btnTestUturnNow, "Make a U-turn", true);
        findViewById(R.id.btnOpenQuiz).setOnClickListener(v ->
                startActivity(new Intent(this, QuizActivity.class)));
        bindRhythm(R.id.btnRhythmLeft, ManeuverParser.Direction.LEFT, false);
        bindRhythm(R.id.btnRhythmLeftNow, ManeuverParser.Direction.LEFT, true);
        bindRhythm(R.id.btnRhythmRight, ManeuverParser.Direction.RIGHT, false);
        bindRhythm(R.id.btnRhythmRightNow, ManeuverParser.Direction.RIGHT, true);
        bindRhythm(R.id.btnRhythmUturn, ManeuverParser.Direction.U_TURN, false);
        bindRhythm(R.id.btnRhythmUturnNow, ManeuverParser.Direction.U_TURN, true);

        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("MorseNav log", DebugLog.dump()));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            DebugLog.clear();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    /** Phase 2 watch experiments; WatchLink needs Android 13+ and the Nearby devices permission. */
    @SuppressLint("NewApi")
    private void bindWatch(int buttonId, Runnable action) {
        findViewById(buttonId).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.watch_needs_android13, Toast.LENGTH_SHORT).show();
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingWatchAction = action;
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                action.run();
            }
        });
    }

    private void bindRhythm(int buttonId, ManeuverParser.Direction direction, boolean imminent) {
        findViewById(buttonId).setOnClickListener(v -> {
            Runnable play = () -> NavEngine.get(this).testRhythm(direction, imminent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasBluetoothConnect()
                    && NavEngine.get(this).output() != NavEngine.Output.PHONE) {
                pendingWatchAction = play;
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                play.run();
            }
        });
    }

    private void bindTest(int buttonId, String instruction, boolean imminent) {
        findViewById(buttonId).setOnClickListener(v -> {
            // The watch half of a test cue needs Nearby devices access; the phone half doesn't.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasBluetoothConnect()
                    && NavEngine.get(this).output() != NavEngine.Output.PHONE) {
                pendingWatchAction = () -> NavEngine.get(this).test(Arrays.asList(instruction), imminent);
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                NavEngine.get(this).test(Arrays.asList(instruction), imminent);
            }
        });
    }

    private boolean hasListenerAccess() {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
    }

    private boolean hasBluetoothConnect() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBatteryUnrestricted() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refresh() {
        NavEngine engine = NavEngine.get(this);
        Haptics haptics = engine.haptics();
        String text = check(hasListenerAccess(), "Notification access")
                + check(canPostNotifications(), "Ride notification allowed")
                + check(isBatteryUnrestricted(), "Battery unrestricted")
                + check(haptics.hasVibrator(), "Vibrator present")
                + check(haptics.hasAmplitudeControl(), "Amplitude control (soft vs strong)")
                + check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasBluetoothConnect(),
                        "Watch access (Nearby devices)")
                + "\nRide: " + (RideService.isRunning() ? "ACTIVE" : "stopped")
                + (RideService.isRunning() ? "   Watch: " + watchState(engine.watchReady()) : "");
        status.setText(text);
        btnRide.setText(RideService.isRunning() ? R.string.stop_ride : R.string.start_ride);
        switch (engine.output()) {
            case PHONE: btnOutput.setText(R.string.output_phone); break;
            case WATCH: btnOutput.setText(R.string.output_watch); break;
            default: btnOutput.setText(R.string.output_both);
        }
        log.setText(DebugLog.dump());
    }

    private static String watchState(Boolean ready) {
        if (ready == null) return "needs Android 13";
        return ready ? "connected" : "connecting…";
    }

    private static String check(boolean ok, String label) {
        return (ok ? "✓  " : "✗  ") + label + "\n";
    }
}
