package me.louietyj.credbridge;

import android.os.SystemClock;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.IOException;

/** FCM delivers only a request id; the details come from the relay. Runs off the main thread. */
public class PushService extends FirebaseMessagingService {

    private static final String TAG = "CredBridge";

    // Android cuts a cached app's network, and the push that wakes it lifts the block a moment
    // later, so the first fetch can fail with "Unable to resolve host".
    private static final long[] RETRY_MS = {250, 500, 1000, 2000, 3000};

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String rid = message.getData().get("rid");
        if (rid == null) return;
        Relay relay = new Relay(this);
        IOException failure = null;
        for (int i = 0; ; i++) {
            try {
                Relay.Request r = relay.fetch(rid, false);
                if (i > 0) Log.i(TAG, "fetched request " + rid + " after " + i + " retries: " + Relay.describe(failure));
                if (r.isOpen() && r.hasPlainSite()) Notifications.show(this, r);
                return;
            } catch (Http.HttpError e) {
                failure = e;
                break;
            } catch (IOException e) {
                failure = e;
                if (i == RETRY_MS.length) break;
                SystemClock.sleep(RETRY_MS[i]);
            }
        }
        Log.w(TAG, "could not fetch request " + rid + ": " + Relay.describe(failure));
        // Something is waiting either way; opening it fetches again, in the foreground.
        Notifications.showUnfetched(this, rid);
    }

    @Override
    public void onNewToken(String token) {
        Relay relay = new Relay(this);
        if (!relay.isPaired()) return;
        try {
            relay.checkIn(token);
        } catch (IOException e) {
            Log.w(TAG, "could not send the new FCM token: " + Relay.describe(e));
        }
    }
}
