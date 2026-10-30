package me.louietyj.credbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifications {

    private static final String CHANNEL = "requests";
    // The relay's longest request lifetime (TTL_MAX_S).
    private static final long MAX_TTL_MS = 30 * 60_000;

    private Notifications() {}

    static void ensureChannel(Context context) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Credential requests", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Claude asking for a site's login");
        context.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    static void show(Context context, Relay.Request r) {
        notify(context, r.rid(), "Claude wants your " + r.site() + " login", r.reason(),
                r.expiresAt() - System.currentTimeMillis());
    }

    /** A request the push could not load: the site and reason are unknown until it is opened. */
    static void showUnfetched(Context context, String rid) {
        notify(context, rid, "Claude is asking for a login",
                "Tap to see which site. It couldn't be loaded in the background.", MAX_TTL_MS);
    }

    private static void notify(Context context, String rid, String title, String text, long ttlMs) {
        ensureChannel(context);
        int id = idFor(rid);
        Intent open = new Intent(context, RequestActivity.class)
                .putExtra(RequestActivity.EXTRA_RID, rid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent decline = new Intent(context, DeclineReceiver.class)
                .putExtra(RequestActivity.EXTRA_RID, rid);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(1, ttlMs))
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
