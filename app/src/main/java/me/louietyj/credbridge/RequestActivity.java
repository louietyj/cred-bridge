package me.louietyj.credbridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One credential request: shows the site and Claude's reason, lets the autofill service fill
 * the login, and on Send seals it to the request's request-text link.
 */
public class RequestActivity extends Activity {

    static final String EXTRA_RID = "rid";
    // Debug builds only: skip the relay and submit straight to a request-text link.
    static final String EXTRA_DEBUG_URL = "debug_url";
    static final String EXTRA_DEBUG_SITE = "debug_site";

    private static final String TAG = "CredBridge";
    private static final List<String> FIELDS = List.of("username", "password");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Relay relay;
    private Relay.Request request;

    private LinearLayout root;
    private TextView status;
    private TextView countdown;
    private EditText username;
    private EditText password;
    private Button send;
    private Button decline;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        relay = new Relay(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        setContentView(root);
        status = text("Loading the request…", 16, false);
        root.addView(status);

        String debugUrl = getIntent().getStringExtra(EXTRA_DEBUG_URL);
        if (BuildConfig.DEBUG && debugUrl != null) {
            String site = getIntent().getStringExtra(EXTRA_DEBUG_SITE);
            show(new Relay.Request(null, site, "Debug submit to " + debugUrl, debugUrl,
                    System.currentTimeMillis() + 10 * 60_000, "opened"));
            return;
        }

        String rid = getIntent().getStringExtra(EXTRA_RID);
        if (rid == null) {
            finish();
            return;
        }
        Notifications.cancel(this, rid);
        io.execute(() -> {
            try {
                Relay.Request r = relay.fetch(rid, true);
                runOnUiThread(() -> show(r));
            } catch (IOException e) {
                runOnUiThread(() -> status.setText("Couldn't load the request: " + Relay.describe(e)));
            }
        });
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private void show(Relay.Request r) {
        request = r;
        root.removeAllViews();
        root.addView(text("Claude is asking for", 16, false));
        TextView site = text(r.site(), 34, true);
        site.setPadding(0, dp(4), 0, dp(8));
        root.addView(site);

        if (!r.hasPlainSite()) {
            root.addView(text("Refused: this is not a plain domain name.", 18, false));
            return;
        }
        String webDomain = "https://" + r.site();
        TextView matches = text("Your password manager will fill its login for " + webDomain, 14, false);
        matches.setPadding(0, 0, 0, dp(16));
        root.addView(matches);

        TextView reason = text("Claude says: “" + r.reason() + "”", 18, false);
        root.addView(reason);
        countdown = text("", 14, false);
        countdown.setPadding(0, dp(4), 0, dp(24));
        root.addView(countdown);

        if (!r.isOpen()) {
            root.addView(text(closedMessage(r.status()), 18, false));
            Button close = new Button(this);
            close.setText("Close");
            close.setOnClickListener(v -> finish());
            root.addView(close);
            return;
        }

        username = new DomainEditText(this, webDomain);
        username.setHint("Username");
        username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(username);

        password = new DomainEditText(this, webDomain);
        password.setHint("Password");
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(16), 0, 0);
        decline = new Button(this);
        decline.setText("Decline");
        decline.setOnClickListener(v -> decline());
        send = new Button(this);
        send.setText("Send to Claude");
        send.setOnClickListener(v -> confirmThenSend());
        buttons.addView(decline);
        buttons.addView(send);
        root.addView(buttons);

        status = text("", 16, false);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        password.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSend(); }
        });
        tick();

        username.requestFocus();
        username.post(() -> getSystemService(AutofillManager.class).requestAutofill(username));
    }

    private void tick() {
        long left = (request.expiresAt() - System.currentTimeMillis()) / 1000;
        if (left <= 0) {
            countdown.setText("Expired");
            status.setText(closedMessage("expired"));
            busy = true;
            updateSend();
            decline.setEnabled(false);
            return;
        }
        countdown.setText(String.format("Expires in %d:%02d", left / 60, left % 60));
        updateSend();
        main.postDelayed(this::tick, 1000);
    }

    private void updateSend() {
        send.setEnabled(!busy && password.length() > 0);
    }

    private void confirmThenSend() {
        if (!relay.requireAuth()) {
            send();
            return;
        }
        new BiometricPrompt.Builder(this)
                .setTitle("Send your " + request.site() + " login to Claude?")
                .setDescription(request.reason())
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(new CancellationSignal(), getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                send();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                status.setText("Not sent: " + msg);
                            }
                        });
    }

    private void send() {
        Map<String, String> fields = Map.of(
                FIELDS.get(0), username.getText().toString(),
                FIELDS.get(1), password.getText().toString());
        Relay.Request r = request;
        setBusy("Sending…");
        io.execute(() -> {
            try {
                if (!RequestTextClient.isAllowedUrl(r.url(), BuildConfig.SUBMIT_HOST_SUFFIX,
                        BuildConfig.ALLOW_LOCAL_SUBMIT)) {
                    throw new IOException("refusing to send to " + r.url());
                }
                RequestTextClient.Form form = RequestTextClient.fetchForm(r.url());
                if (!form.fieldNames().equals(FIELDS)) {
                    throw new IOException("the link asks for " + form.fieldNames() + ", not a login");
                }
                RequestTextClient.submit(r.url(), form, fields);
                markFinished(r, "sent");
                runOnUiThread(() -> {
                    password.setText("");
                    status.setText("Sent ✓");
                    main.postDelayed(this::finish, 1000);
                });
            } catch (Exception e) {
                String why = e instanceof Http.HttpError h && h.status == 410
                        ? "This request was already answered, or it expired."
                        : e instanceof IOException ioe ? Relay.describe(ioe) : e.toString();
                Log.w(TAG, "send failed: " + why);
                runOnUiThread(() -> {
                    busy = false;
                    updateSend();
                    decline.setEnabled(true);
                    status.setText("Not sent: " + why);
                });
            }
        });
    }

    private void decline() {
        Relay.Request r = request;
        setBusy("Declining…");
        io.execute(() -> {
            markFinished(r, "declined");
            runOnUiThread(this::finish);
        });
    }

    // Best effort: Claude still learns a "sent" from request-text itself.
    private void markFinished(Relay.Request r, String outcome) {
        if (r.rid() == null) return;
        try {
            relay.finish(r.rid(), outcome);
        } catch (IOException e) {
            Log.w(TAG, "could not mark " + r.rid() + " " + outcome + ": " + Relay.describe(e));
        }
    }

    private void setBusy(String message) {
        busy = true;
        updateSend();
        decline.setEnabled(false);
        status.setText(message);
    }

    private static String closedMessage(String status) {
        return switch (status) {
            case "sent" -> "Already sent.";
            case "declined" -> "You declined this request.";
            case "expired" -> "This request expired.";
            default -> "This request is " + status + ".";
        };
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
