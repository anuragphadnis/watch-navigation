package com.morsenav;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.widget.Toast;

/** Starts the fake incoming calls handled by {@link FakeCallService}. */
final class FakeCall {

    static final String ACCOUNT_SELF_MANAGED = "morsenav-self-managed";
    static final String ACCOUNT_MANAGED = "morsenav-managed";
    static final String EXTRA_HOLD_MS = "com.morsenav.HOLD_MS";

    /** Ring lengths and start times of the test, ms: 1 s, 0.3 s, 2.5 s, ~4 s apart. */
    private static final long[][] CALLS = {{0, 1000}, {5000, 300}, {9300, 2500}};

    private FakeCall() {}

    private static PhoneAccountHandle handle(Context context, String id) {
        return new PhoneAccountHandle(new ComponentName(context, FakeCallService.class), id);
    }

    private static void register(Context context, TelecomManager telecom, String id) {
        boolean selfManaged = ACCOUNT_SELF_MANAGED.equals(id);
        PhoneAccount account = PhoneAccount.builder(handle(context, id), "MorseNav test calls")
                .setCapabilities(selfManaged ? PhoneAccount.CAPABILITY_SELF_MANAGED : PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build();
        telecom.registerPhoneAccount(account);
    }

    /** One self-managed call ringing for {@code holdMs}, to compare lengths directly. */
    static void single(Context context, long holdMs) {
        run(context, true, new long[][]{{0, holdMs}});
    }

    /** Ring lengths of the sweep, ms: cuts the watch's ring pattern off at increasing points. */
    static final long[] SWEEP_MS = {500, 1000, 1500, 2000, 2500, 6000};
    private static final long SWEEP_GAP_MS = 5000;

    /** Rings for each {@link #SWEEP_MS} in turn, {@link #SWEEP_GAP_MS} apart, to map the ring pattern. */
    static void sweep(Context context) {
        long[][] calls = new long[SWEEP_MS.length][];
        long at = 0;
        for (int i = 0; i < SWEEP_MS.length; i++) {
            calls[i] = new long[]{at, SWEEP_MS[i]};
            at += SWEEP_MS[i] + SWEEP_GAP_MS;
        }
        run(context, true, calls);
    }

    /** Runs the three test calls, or explains what is needed first. */
    static void test(Context context, boolean selfManaged) {
        run(context, selfManaged, CALLS);
    }

    @SuppressWarnings("MissingPermission")
    private static void run(Context context, boolean selfManaged, long[][] calls) {
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        String id = selfManaged ? ACCOUNT_SELF_MANAGED : ACCOUNT_MANAGED;
        register(context, telecom, id);
        PhoneAccountHandle h = handle(context, id);

        // A managed account must be enabled by the user; reading its state needs READ_PHONE_NUMBERS,
        // so instead try the first call and open Calling accounts if Telecom refuses it.
        if (selfManaged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !telecom.isIncomingCallPermitted(h)) {
            DebugLog.add("CALL [" + id + "] Telecom says an incoming call isn't permitted right now");
            return;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        for (long[] call : calls) {
            handler.postDelayed(() -> {
                Bundle callExtras = new Bundle();
                callExtras.putLong(EXTRA_HOLD_MS, call[1]);
                Bundle extras = new Bundle();
                extras.putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, callExtras);
                try {
                    telecom.addNewIncomingCall(h, extras);
                    DebugLog.add("CALL [" + id + "] requested, hold " + call[1] + " ms");
                } catch (SecurityException | IllegalArgumentException e) {
                    DebugLog.add("CALL [" + id + "] request failed: " + e);
                    if (!selfManaged && call == calls[0]) {
                        handler.removeCallbacksAndMessages(null);
                        Toast.makeText(context, R.string.enable_calling_account, Toast.LENGTH_LONG).show();
                        context.startActivity(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }
            }, call[0]);
        }
    }
}
