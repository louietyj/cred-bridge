package me.louietyj.credbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;

/** The notification's Decline action: tell the relay, so Claude stops waiting. */
public class DeclineReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String rid = intent.getStringExtra(RequestActivity.EXTRA_RID);
        if (rid == null) return;
        Notifications.cancel(context, rid);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                new Relay(context).finish(rid, "declined");
            } catch (IOException e) {
                Log.w("CredBridge", "could not decline " + rid + ": " + Relay.describe(e));
            } finally {
                pending.finish();
            }
        }).start();
    }
}
