package me.louietyj.credbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher. Checks the pairing with the relay on every open and lists the requests waiting for
 * an answer, so a request whose notification never came can still be answered from here.
 */
public class HomeActivity extends Activity {

    private static final String PAIR_HINT = "Ask Claude to run `request-credential pair` and enter its code.";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Relay relay;
    private int pad;
    private TextView state;
    private LinearLayout waiting;
    private LinearLayout pairing;
    private TextView pairHint;
    private EditText code;
    private Button pair;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relay = new Relay(this);
        Notifications.ensureChannel(this);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }

        pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("Credential Request", 26, true));
        state = text("", 16, false);
        state.setPadding(0, pad / 2, 0, pad);
        root.addView(state);

        root.addView(text("Waiting for you", 20, true));
        waiting = new LinearLayout(this);
        waiting.setOrientation(LinearLayout.VERTICAL);
        waiting.setPadding(0, pad / 3, 0, 0);
        root.addView(waiting);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        // Shown only while the relay doesn't know this phone.
        pairing = new LinearLayout(this);
        pairing.setOrientation(LinearLayout.VERTICAL);
        pairing.setVisibility(View.GONE);
        TextView pairTitle = text("Pair with the relay", 20, true);
        pairTitle.setPadding(0, pad, 0, 0);
        pairing.addView(pairTitle);
        pairHint = text(PAIR_HINT, 14, false);
        pairing.addView(pairHint);
        code = new EditText(this);
        code.setHint("6-digit code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairing.addView(code);
        pair = new Button(this);
        pair.setText("Pair");
        pair.setOnClickListener(v -> pair());
        pairing.addView(pair);
        root.addView(pairing);

        Switch auth = new Switch(this);
        auth.setText("Ask for fingerprint, face or PIN before sending");
        auth.setChecked(relay.requireAuth());
        auth.setOnCheckedChangeListener((b, on) -> relay.setRequireAuth(on));
        auth.setPadding(0, pad, 0, 0);
        root.addView(auth);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        padForSystemBars(scroll);
    }

    /** Apps are drawn edge to edge, under the status bar, navigation bar and keyboard. */
    static void padForSystemBars(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        waiting.removeAllViews();
        if (!relay.isPaired()) {
            showUnpaired("Not paired with the relay yet.");
            return;
        }
        state.setText("Checking with the relay…");
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                state.setText("Couldn't get a push token: " + task.getException());
                return;
            }
            String token = task.getResult();
            io.execute(() -> {
                try {
                    long paired = relay.checkIn(token);
                    List<Relay.Request> open = relay.open();
                    runOnUiThread(() -> showPaired(paired, open));
                } catch (Http.HttpError e) {
                    // 401: the relay's device token changed; 409: it has no phone at all.
                    runOnUiThread(() -> {
                        if (e.status == 401 || e.status == 409) showUnpaired("The relay doesn't know this phone.");
                        else state.setText("The relay refused the check: " + Relay.describe(e));
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> state.setText("Couldn't reach the relay to check the pairing: "
                            + Relay.describe(e)));
                }
            });
        });
    }

    private void showPaired(long paired, List<Relay.Request> open) {
        state.setText("Paired with the relay since "
                + DateUtils.formatDateTime(this, paired, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)
                + ". Checked just now. Claude's requests arrive as notifications, and wait here too.");
        pairing.setVisibility(View.GONE);
        waiting.removeAllViews();
        if (open.isEmpty()) waiting.addView(text("Nothing right now.", 16, false));
        for (Relay.Request r : open) {
            Button b = new Button(this);
            b.setAllCaps(false);
            long min = Math.max(0, (r.expiresAt() - System.currentTimeMillis()) / 60_000);
            b.setText(r.site() + "\n" + r.reason() + "\nExpires in " + min + " min");
            b.setOnClickListener(v -> startActivity(new Intent(this, RequestActivity.class)
                    .putExtra(RequestActivity.EXTRA_RID, r.rid())));
            waiting.addView(b);
        }
    }

    private void showUnpaired(String why) {
        state.setText(why);
        pairing.setVisibility(View.VISIBLE);
        waiting.removeAllViews();
        waiting.addView(text("Requests show here once the phone is paired.", 16, false));
    }

    private void pair() {
        String c = code.getText().toString().trim();
        if (c.length() != 6) {
            pairHint.setText("The code is 6 digits.");
            return;
        }
        pair.setEnabled(false);
        pairHint.setText("Pairing…");
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                pair.setEnabled(true);
                pairHint.setText("Couldn't get a push token: " + task.getException());
                return;
            }
            String token = task.getResult();
            io.execute(() -> {
                try {
                    relay.pair(c, token);
                    runOnUiThread(() -> {
                        code.setText("");
                        pairHint.setText(PAIR_HINT);
                        pair.setEnabled(true);
                        refresh();
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        pair.setEnabled(true);
                        pairHint.setText("Pairing failed: " + Relay.describe(e));
                    });
                }
            });
        });
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
