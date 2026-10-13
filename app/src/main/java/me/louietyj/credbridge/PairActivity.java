package me.louietyj.credbridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher: pair with the relay using the code from `request-credential pair`, and settings. */
public class PairActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Relay relay;
    private TextView state;
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

        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Credential Request");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        root.addView(title);

        state = new TextView(this);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        state.setPadding(0, pad / 2, 0, pad);
        root.addView(state);

        code = new EditText(this);
        code.setHint("6-digit code from Claude");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(code);

        pair = new Button(this);
        pair.setOnClickListener(v -> pair());
        root.addView(pair);

        Switch auth = new Switch(this);
        auth.setText("Ask for fingerprint, face or PIN before sending");
        auth.setChecked(relay.requireAuth());
        auth.setOnCheckedChangeListener((b, on) -> relay.setRequireAuth(on));
        auth.setPadding(0, pad, 0, 0);
        root.addView(auth);

        setContentView(root);
        showState(null);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void showState(String error) {
        String base = relay.isPaired()
                ? "Paired. Claude's requests will arrive as notifications."
                : "Not paired. Ask Claude to run `request-credential pair` and enter the code.";
        state.setText(error == null ? base : error + "\n\n" + base);
        pair.setText(relay.isPaired() ? "Pair again" : "Pair");
        pair.setEnabled(true);
    }

    private void pair() {
        String c = code.getText().toString().trim();
        if (c.length() != 6) {
            state.setText("The code is 6 digits.");
            return;
        }
        pair.setEnabled(false);
        state.setText("Pairing…");
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                showState("Couldn't get a push token: " + task.getException());
                return;
            }
            String token = task.getResult();
            io.execute(() -> {
                try {
                    relay.pair(c, token);
                    runOnUiThread(() -> {
                        code.setText("");
                        showState(null);
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> showState("Pairing failed: " + Relay.describe(e)));
                }
            });
        });
    }
}
