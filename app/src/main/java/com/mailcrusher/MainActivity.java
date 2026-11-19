package com.mailcrusher;

import android.accounts.Account;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send";
    private static final String USER_EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    private static final int MODE_ADD = 1;
    private static final int MODE_SEND = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private HistoryDb db;
    private String activeEmail;
    private String accessToken; // memory only; re-authorize after app restart
    private GeneratedMessage preview;
    private int authMode = MODE_ADD;
    private TextView accountView, previewView, statusView;
    private TextInputEditText recipientInput, subjectLengthInput, bodyLengthInput;

    private final ActivityResultLauncher<IntentSenderRequest> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    status("Authorization cancelled.");
                    return;
                }
                try {
                    AuthorizationResult auth = Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(result.getData());
                    handleAuthorization(auth);
                } catch (Exception e) { status("Authorization failed: " + e.getMessage()); }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new HistoryDb(this);
        accountView = findViewById(R.id.txtAccount);
        previewView = findViewById(R.id.txtPreview);
        statusView = findViewById(R.id.txtStatus);
        recipientInput = findViewById(R.id.edtRecipient);
        subjectLengthInput = findViewById(R.id.edtSubjectLength);
        bodyLengthInput = findViewById(R.id.edtBodyLength);

        MaterialButton add = findViewById(R.id.btnAddAccount);
        MaterialButton generate = findViewById(R.id.btnGenerate);
        MaterialButton send = findViewById(R.id.btnSend);
        MaterialButton history = findViewById(R.id.btnHistory);

        add.setOnClickListener(v -> {
            authMode = MODE_ADD;
            authorize(null);
        });
        generate.setOnClickListener(v -> generatePreview());
        send.setOnClickListener(v -> confirmSend());
        history.setOnClickListener(v -> showHistory());
        refreshAccounts();
    }

    private void authorize(String email) {
        authMode = email == null ? MODE_ADD : MODE_SEND;
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(new Scope(GMAIL_SEND), new Scope(USER_EMAIL)));
        if (email == null) {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT);
        } else {
            builder.setAccount(new Account(email, "com.google"));
        }
        Identity.getAuthorizationClient(this).authorize(builder.build())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        PendingIntent pending = result.getPendingIntent();
                        try {
                            authLauncher.launch(new IntentSenderRequest.Builder(pending.getIntentSender()).build());
                        } catch (Exception e) { status("Could not open Google consent: " + e.getMessage()); }
                    } else {
                        handleAuthorization(result);
                    }
                })
                .addOnFailureListener(e -> status("Google authorization error: " + e.getMessage()));
    }

    private void handleAuthorization(AuthorizationResult result) {
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            status("Google did not return an access token.");
            return;
        }
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://www.googleapis.com/oauth2/v2/userinfo").openConnection();
                c.setRequestProperty("Authorization", "Bearer " + token);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("User info HTTP " + code);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder json = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) json.append(line);
                r.close(); c.disconnect();
                String email = new org.json.JSONObject(json.toString()).getString("email");
                runOnUiThread(() -> {
                    activeEmail = email;
                    accessToken = token;
                    db.addAccount(email);
                    accountView.setText("Active Gmail account: " + email);
                    status("Authorized. Access token remains in memory only.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Could not read authorized account: " + e.getMessage()));
            }
        });
    }

    private void generatePreview() {
        try {
            int subjectLength = Integer.parseInt(subjectLengthInput.getText().toString());
            int bodyLength = Integer.parseInt(bodyLengthInput.getText().toString());
            preview = MessageGenerator.create(subjectLength, bodyLength);
            previewView.setText("SUBJECT\n" + preview.subject + "\n\nBODY\n" + preview.body);
            status("Preview generated. Nothing has been sent.");
        } catch (Exception e) { status("Invalid lengths: " + e.getMessage()); }
    }

    private void confirmSend() {
        if (activeEmail == null || accessToken == null) {
            status("Authorize a Gmail account first.");
            return;
        }
        if (preview == null) {
            status("Generate and review a message first.");
            return;
        }
        String recipient = recipientInput.getText() == null ? "" :
                recipientInput.getText().toString().trim();
        if (!activeEmail.equalsIgnoreCase(recipient)) {
            status("V1 test mode only sends to the currently authorized sender address.");
            return;
        }
        if (!RateLimiter.canSend(this)) {
            long seconds = (RateLimiter.remainingMs(this) + 999) / 1000;
            status("Local safety interval active. Try again in " + seconds + " seconds.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Confirm test email")
                .setMessage("Send this single test message to " + activeEmail + "?\n\nSubject: " + preview.subject)
                .setNegativeButton("Cancel", (d, w) -> {})
                .setPositiveButton("Send one email", (d, w) -> sendConfirmed(recipient))
                .show();
    }

    private void sendConfirmed(String recipient) {
        final String token = accessToken;
        final String sender = activeEmail;
        final GeneratedMessage message = preview;
        RateLimiter.recordSendAttempt(this);
        status("Sending one confirmed test message…");
        io.execute(() -> {
            try {
                GmailApiClient.sendOne(token, sender, recipient, message.subject, message.body);
                db.addHistory(recipient, message.subject, "API accepted");
                runOnUiThread(() -> status("Gmail API accepted the message. Check your Sent folder."));
            } catch (Exception e) {
                db.addHistory(recipient, message.subject, "Failed: " + e.getMessage());
                runOnUiThread(() -> status("Send failed: " + e.getMessage()));
            }
        });
    }

    private void showHistory() {
        java.util.List<String> rows = db.history();
        String text = rows.isEmpty() ? "No sent attempts yet." : android.text.TextUtils.join("\n\n", rows);
        new AlertDialog.Builder(this).setTitle("Local history").setMessage(text)
                .setPositiveButton("Close", null).show();
    }

    private void refreshAccounts() {
        java.util.List<String> accounts = db.accounts();
        if (!accounts.isEmpty()) {
            accountView.setText("Saved account profiles:\n" + android.text.TextUtils.join("\n", accounts)
                    + "\n\nRe-authorize an account before sending.");
        }
    }

    private void status(String message) { statusView.setText("Status: " + message); }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
