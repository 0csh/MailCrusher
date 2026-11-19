package com.mailcrusher;

import android.accounts.Account;
import android.accounts.AccountManager;
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
    private static final String GMAIL_METADATA = "https://www.googleapis.com/auth/gmail.metadata";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private HistoryDb db;
    private String activeEmail;
    private String pendingAccountEmail;
    private String accessToken; // memory only; re-authorize after app restart
    private GeneratedMessage preview;
    private String latestSentMessageId;
    private TextInputEditText messageCountInput;
    private TextView accountView, previewView, statusView;
    private TextInputEditText recipientInput, subjectLengthInput, bodyLengthInput;

    private final ActivityResultLauncher<Intent> accountPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    status("Account selection cancelled.");
                    return;
                }
                String email = result.getData().getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (email == null || email.trim().isEmpty()) {
                    status("Google account was not selected.");
                    return;
                }
                authorize(email.trim());
            });

    private final ActivityResultLauncher<IntentSenderRequest> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                // Google Identity may return a useful authorization result even when
                // ActivityResult reports RESULT_CANCELED. Always inspect the returned Intent.
                Intent data = result.getData();
                if (data == null) {
                    status("Google returned no authorization data (result code "
                            + result.getResultCode() + "). Try again and check the Google consent screen.");
                    return;
                }
                try {
                    AuthorizationResult auth = Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(data);
                    handleAuthorization(auth, pendingAccountEmail);
                } catch (com.google.android.gms.common.api.ApiException e) {
                    status("Google authorization error (status " + e.getStatusCode()
                            + "): " + e.getStatus().getStatusMessage());
                } catch (Exception e) {
                    status("Authorization result could not be read: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        android.widget.LinearLayout rootContent = findViewById(R.id.rootContent);
        rootContent.setBackground(new AnimatedAmbientDrawable());
        animateEntrance();
        db = new HistoryDb(this);
        accountView = findViewById(R.id.txtAccount);
        previewView = findViewById(R.id.txtPreview);
        statusView = findViewById(R.id.txtStatus);
        recipientInput = findViewById(R.id.edtRecipient);
        subjectLengthInput = findViewById(R.id.edtSubjectLength);
        bodyLengthInput = findViewById(R.id.edtBodyLength);
        messageCountInput = findViewById(R.id.edtMessageCount);

        MaterialButton add = findViewById(R.id.btnAddAccount);
        MaterialButton generate = findViewById(R.id.btnGenerate);
        MaterialButton send = findViewById(R.id.btnSend);
        MaterialButton history = findViewById(R.id.btnHistory);
        MaterialButton checkStatus = findViewById(R.id.btnCheckStatus);

        add.setOnClickListener(v -> chooseGoogleAccount());
        generate.setOnClickListener(v -> generatePreview());
        send.setOnClickListener(v -> confirmSend());
        history.setOnClickListener(v -> showHistory());
        checkStatus.setOnClickListener(v -> checkLatestSendStatus());
        refreshAccounts();
    }


    private void animateEntrance() {
        android.view.ViewGroup root = findViewById(R.id.rootContent);
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(12f);
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 35L).setDuration(280L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void chooseGoogleAccount() {
        Intent picker = AccountManager.newChooseAccountIntent(
                null, null, new String[]{"com.google"}, true,
                "Choose a Google account to add to MailCrusher",
                null, null, null);
        accountPickerLauncher.launch(picker);
    }

    private void authorize(String email) {
        pendingAccountEmail = email;
        status("Authorizing " + email + " with Google…");
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(new Scope(GMAIL_SEND), new Scope(USER_EMAIL), new Scope(GMAIL_METADATA)))
                .setAccount(new Account(email, "com.google"));
        Identity.getAuthorizationClient(this).authorize(builder.build())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        PendingIntent pending = result.getPendingIntent();
                        try {
                            authLauncher.launch(new IntentSenderRequest.Builder(pending.getIntentSender()).build());
                        } catch (Exception e) { status("Could not open Google consent: " + e.getMessage()); }
                    } else {
                        handleAuthorization(result, email);
                    }
                })
                .addOnFailureListener(e -> status("Google authorization error: " + e.getMessage()));
    }

    private void handleAuthorization(AuthorizationResult result, String selectedEmail) {
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            status("Google did not grant Gmail access. Choose the account and accept the requested permissions.");
            return;
        }
        // Save the selected Google account directly; registration must not depend on
        // a second userinfo HTTP request succeeding.
        String email = selectedEmail == null ? pendingAccountEmail : selectedEmail;
        if (email == null || email.trim().isEmpty()) {
            status("Google access was granted, but the selected account email is missing. Please add the account again.");
            return;
        }
        email = email.trim();
        activeEmail = email;
        accessToken = token;
        pendingAccountEmail = null;
        try {
            db.addAccount(email);
            refreshAccounts();
            status("Account added and authorized: " + email);
        } catch (Exception e) {
            status("Google access succeeded, but the account could not be saved: " + e.getMessage());
        }
    }

    private void generatePreview() {
        try {
            int subjectLength = Integer.parseInt(subjectLengthInput.getText().toString());
            int bodyLength = Integer.parseInt(bodyLengthInput.getText().toString());
            int count = Integer.parseInt(messageCountInput.getText().toString());
            if (count < 1 || count > 5) {
                throw new IllegalArgumentException("Preview count must be between 1 and 5.");
            }
            StringBuilder previews = new StringBuilder();
            preview = null;
            for (int i = 1; i <= count; i++) {
                GeneratedMessage generated = MessageGenerator.create(subjectLength, bodyLength);
                if (preview == null) preview = generated;
                if (i > 1) previews.append("\n\n--------------------\n\n");
                previews.append("PREVIEW ").append(i)
                        .append("\nSUBJECT\n").append(generated.subject)
                        .append("\n\nBODY\n").append(generated.body);
            }
            previewView.animate().cancel();
            previewView.setAlpha(0f);
            previewView.setTranslationY(6f);
            previewView.setText(previews.toString());
            previewView.animate().alpha(1f).translationY(0f).setDuration(220L).start();
            status(count + " draft(s) generated. Review them below; send one at a time after confirmation.");
        } catch (Exception e) { status("Invalid settings: " + e.getMessage()); }
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
                String messageId = GmailApiClient.sendOne(token, sender, recipient, message.subject, message.body);
                runOnUiThread(() -> {
                    latestSentMessageId = messageId;
                    db.addHistory(recipient, message.subject, "Accepted by Gmail; ID " + messageId);
                    status("Gmail accepted the message. Use Check send status to verify it in Sent.");
                });
            } catch (Exception e) {
                db.addHistory(recipient, message.subject, "Failed: " + e.getMessage());
                runOnUiThread(() -> status("Send failed: " + e.getMessage()));
            }
        });
    }


    private void checkLatestSendStatus() {
        if (accessToken == null) {
            status("Authorize the Gmail account used to send the message first.");
            return;
        }
        if (latestSentMessageId == null || latestSentMessageId.isEmpty()) {
            status("No message ID in this app session. Send a test message first.");
            return;
        }
        final String token = accessToken;
        final String messageId = latestSentMessageId;
        status("Checking message status with Gmail…");
        io.execute(() -> {
            try {
                String result = GmailApiClient.checkSentStatus(token, messageId);
                runOnUiThread(() -> status(result));
            } catch (Exception e) {
                runOnUiThread(() -> status("Status check failed: " + e.getMessage()));
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
        if (activeEmail != null) {
            accountView.setText("CONNECTED  •  " + activeEmail + "\n" + accounts.size() + " saved account(s)  •  Tap to manage");
        } else if (!accounts.isEmpty()) {
            accountView.setText(accounts.size() + " saved Gmail account(s)\nTap to select or add an account");
        } else {
            accountView.setText("No Gmail account selected. Tap Add account.");
        }
        accountView.setOnClickListener(v -> showAccountPicker());
    }

    private void showAccountPicker() {
        java.util.List<String> accounts = db.accounts();
        if (accounts.isEmpty()) {
            chooseGoogleAccount();
            return;
        }
        String[] choices = new String[accounts.size() + 1];
        for (int i = 0; i < accounts.size(); i++) {
            choices[i] = (accounts.get(i).equalsIgnoreCase(activeEmail == null ? "" : activeEmail)
                    ? "✓  " : "") + accounts.get(i);
        }
        choices[accounts.size()] = "+  Add another account";
        new AlertDialog.Builder(this)
                .setTitle("Gmail accounts")
                .setItems(choices, (dialog, which) -> {
                    if (which == accounts.size()) chooseGoogleAccount();
                    else authorize(accounts.get(which));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void status(String message) { statusView.setText("Status: " + message); }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
