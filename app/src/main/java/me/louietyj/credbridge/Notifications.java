package me.louietyj.credbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifications {

    private static final String CHANNEL = "requests";

    private Notifications() {}

    static void ensureChannel(Context context) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Credential requests", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Claude asking for a site's login");
        context.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    static void show(Context context, Relay.Request r) {
        ensureChannel(context);
        int id = idFor(r.rid());
        Intent open = new Intent(context, RequestActivity.class)
                .putExtra(RequestActivity.EXTRA_RID, r.rid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent decline = new Intent(context, DeclineReceiver.class)
                .putExtra(RequestActivity.EXTRA_RID, r.rid());
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Claude wants your " + r.site() + " login")
                .setContentText(r.reason())
                .setStyle(new Notification.BigTextStyle().bigText(r.reason()))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(1, r.expiresAt() - System.currentTimeMillis()))
                .setContentIntent(PendingIntent.getActivity(context, id, open, flags))
                .addAction(new Notification.Action.Builder(null, "Decline",
                        PendingIntent.getBroadcast(context, id, decline, flags)).build())
                .build();
        context.getSystemService(NotificationManager.class).notify(id, n);
    }

    static void cancel(Context context, String rid) {
        context.getSystemService(NotificationManager.class).cancel(idFor(rid));
    }

    private static int idFor(String rid) {
        return rid.hashCode();
    }
}
