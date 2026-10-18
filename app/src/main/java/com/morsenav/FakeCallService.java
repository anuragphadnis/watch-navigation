package com.morsenav;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/**
 * Phase 2 experiment: an incoming call that exists only inside Android's Telecom system (no
 * cellular call), to see whether the phone hands it to the watch over Bluetooth calling (HFP) and
 * how the watch vibrates while it rings. Each call ends itself after {@link FakeCall#EXTRA_HOLD_MS}.
 */
public class FakeCallService extends ConnectionService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        Bundle extras = request.getExtras();
        Bundle callExtras = extras != null ? extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) : null;
        if (callExtras == null) callExtras = extras;
        long holdMs = callExtras != null ? callExtras.getLong(FakeCall.EXTRA_HOLD_MS, 1000) : 1000;

        FakeConnection c = new FakeConnection(account.getId());
        c.setAddress(android.net.Uri.fromParts("tel", "MorseNav", null), TelecomManager.PRESENTATION_ALLOWED);
        c.setCallerDisplayName("MorseNav", TelecomManager.PRESENTATION_ALLOWED);
        if (FakeCall.ACCOUNT_SELF_MANAGED.equals(account.getId())) c.setAudioModeIsVoip(true);
        c.setInitializing();
        c.setRinging();
        DebugLog.add("CALL [" + account.getId() + "] ringing, ends in " + holdMs + " ms");
        handler.postDelayed(() -> c.end("hold elapsed"), holdMs);
        return c;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle account, ConnectionRequest request) {
        DebugLog.add("CALL [" + account.getId() + "] Telecom refused the incoming call");
    }

    private static final class FakeConnection extends Connection {
        private final String account;
        private final long startMs = android.os.SystemClock.uptimeMillis();
        private boolean ended;

        FakeConnection(String account) {
            this.account = account;
        }

        void end(String why) {
            if (ended) return;
            ended = true;
            setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
            destroy();
            DebugLog.add("CALL [" + account + "] ended (" + why + ") after "
                    + (android.os.SystemClock.uptimeMillis() - startMs) + " ms");
        }

        @Override
        public void onShowIncomingCallUi() {
            DebugLog.add("CALL [" + account + "] Telecom asked us to show call UI (not shown)");
        }

        @Override
        public void onCallAudioStateChanged(CallAudioState state) {
            String route;
            switch (state.getRoute()) {
                case CallAudioState.ROUTE_BLUETOOTH: route = "BLUETOOTH"; break;
                case CallAudioState.ROUTE_EARPIECE: route = "EARPIECE"; break;
                case CallAudioState.ROUTE_SPEAKER: route = "SPEAKER"; break;
                case CallAudioState.ROUTE_WIRED_HEADSET: route = "WIRED"; break;
                default: route = "route " + state.getRoute();
            }
            DebugLog.add("CALL [" + account + "] audio route " + route
                    + (state.getActiveBluetoothDevice() != null ? " (" + state.getActiveBluetoothDevice().getAddress() + ")" : ""));
        }

        @Override
        public void onAnswer() {
            DebugLog.add("CALL [" + account + "] answered on " + "phone or watch");
            end("answered");
        }

        @Override
        public void onReject() {
            DebugLog.add("CALL [" + account + "] rejected on phone or watch");
            end("rejected");
        }

        @Override
        public void onDisconnect() {
            end("disconnect requested");
        }
    }
}
