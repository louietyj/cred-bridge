package me.louietyj.credbridge;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.IOException;

/** FCM delivers only a request id; the details come from the relay. Runs off the main thread. */
public class PushService extends FirebaseMessagingService {

    private static final String TAG = "CredBridge";

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String rid = message.getData().get("rid");
        if (rid == null) return;
        try {
            Relay.Request r = new Relay(this).fetch(rid, false);
            if (r.isOpen() && r.hasPlainSite()) Notifications.show(this, r);
        } catch (IOException e) {
            Log.w(TAG, "could not fetch request " + rid + ": " + Relay.describe(e));
        }
    }

    @Override
    public void onNewToken(String token) {
        Relay relay = new Relay(this);
        if (!relay.isPaired()) return;
        try {
            relay.updateFcmToken(token);
        } catch (IOException e) {
            Log.w(TAG, "could not send the new FCM token: " + Relay.describe(e));
        }
    }
}
