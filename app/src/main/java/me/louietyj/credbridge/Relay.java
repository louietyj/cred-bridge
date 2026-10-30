package me.louietyj.credbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The phone's side of the request-credential relay (claude-skills/request-credential/worker).
 * Blocking; call off the main thread.
 */
final class Relay {

    /** A credential request as the relay describes it. Not a record: see {@link Seal.Sealed}. */
    static final class Request {
        private final String rid, site, reason, url, status;
        private final long expiresAt;

        Request(String rid, String site, String reason, String url, long expiresAt, String status) {
            this.rid = rid;
            this.site = site;
            this.reason = reason;
            this.url = url;
            this.expiresAt = expiresAt;
            this.status = status;
        }

        String rid() { return rid; }
        String site() { return site; }
        String reason() { return reason; }
        String url() { return url; }
        long expiresAt() { return expiresAt; }
        String status() { return status; }

        boolean isOpen() {
            return "pending".equals(status) || "delivered".equals(status) || "opened".equals(status);
        }

        /** Checked here, not trusted from the relay: it is what autofill acts on. ASCII only. */
        boolean hasPlainSite() {
            return site != null && SITE.matcher(site).matches();
        }
    }

    private static final Pattern SITE = Pattern.compile(
            "^(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private static final String PREFS = "relay";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final String KEY_REQUIRE_AUTH = "require_auth";

    private final SharedPreferences prefs;

    Relay(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Only that this phone was paired once; {@link #checkIn} is what proves the relay agrees. */
    boolean isPaired() {
        return prefs.getString(KEY_DEVICE_TOKEN, null) != null;
    }

    boolean requireAuth() {
        return prefs.getBoolean(KEY_REQUIRE_AUTH, true);
    }

    void setRequireAuth(boolean on) {
        prefs.edit().putBoolean(KEY_REQUIRE_AUTH, on).apply();
    }

    void pair(String code, String fcmToken) throws IOException {
        JSONObject out = call("POST", "/pair/claim", null, json("code", code, "fcm_token", fcmToken));
        prefs.edit().putString(KEY_DEVICE_TOKEN, out.optString("k")).apply();
    }

    /** Proves the pairing and hands the relay the current FCM token; returns when it was paired (epoch ms). */
    long checkIn(String fcmToken) throws IOException {
        return call("PUT", "/device", deviceToken(), json("fcm_token", fcmToken)).optLong("paired");
    }

    /** The requests still waiting for an answer, newest first. They carry no link: open one to get it. */
    List<Request> open() throws IOException {
        JSONArray a = call("GET", "/device/requests", deviceToken(), null).optJSONArray("requests");
        List<Request> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            out.add(new Request(o.optString("rid"), o.optString("site"), o.optString("reason"),
                    null, o.optLong("expires_at"), o.optString("status")));
        }
        return out;
    }

    /** {@code opening}: the request screen is showing it, rather than a push being handled. */
    Request fetch(String rid, boolean opening) throws IOException {
        String path = "/device/request/" + rid + (opening ? "?seen=opened" : "");
        JSONObject o = call("GET", path, deviceToken(), null);
        return new Request(o.optString("rid"), o.optString("site"), o.optString("reason"),
                o.optString("url"), o.optLong("expires_at"), o.optString("status"));
    }

    /** {@code outcome} is "sent" or "declined". */
    void finish(String rid, String outcome) throws IOException {
        call("POST", "/device/request/" + rid + "/" + outcome, deviceToken(), "{}");
    }

    /** The relay's own error message when there is one, else the HTTP status. */
    static String describe(IOException e) {
        if (e instanceof Http.HttpError h) {
            try {
                String msg = new JSONObject(h.body).optString("error");
                if (!msg.isEmpty()) return msg;
            } catch (JSONException ignored) {
                // not JSON: fall through to the status line
            }
        }
        return e.getMessage();
    }

    private String deviceToken() throws IOException {
        String k = prefs.getString(KEY_DEVICE_TOKEN, null);
        if (k == null) throw new IOException("not paired");
        return k;
    }

    private static JSONObject call(String method, String path, String bearer, String body)
            throws IOException {
        String reply = Http.send(method, BuildConfig.WORKER_URL + path, bearer, body);
        try {
            return new JSONObject(reply);
        } catch (JSONException e) {
            throw new IOException("the relay sent something other than JSON", e);
        }
    }

    private static String json(String... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
