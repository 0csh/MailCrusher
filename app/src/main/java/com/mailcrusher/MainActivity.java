package com.mailcrusher;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import androidx.drawerlayout.widget.DrawerLayout;
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
import java.util.ArrayList;
import java.util.List;
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
    private static final String STATE_PENDING_EMAIL = "pending_account_email";
    private String accessToken; // memory only; re-authorize after app restart
    private final List<GeneratedMessage> generatedMessages = new ArrayList<>();
    private String latestSentMessageId;
    private TextInputEditText messageCountInput;
    private TextView accountView, previewView, statusView;
    private TextInputEditText recipientInput, subjectInput, bodyInput;
    private DrawerLayout drawerLayout;
    private LinearLayout authorizedList;

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
                    handleAuthorization(auth);
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
        if (savedInstanceState != null) {
            pendingAccountEmail = savedInstanceState.getString(STATE_PENDING_EMAIL);
        }
        setContentView(R.layout.activity_main);
        android.widget.LinearLayout rootContent = findViewById(R.id.rootContent);
        rootContent.setBackground(new AnimatedAmbientDrawable());
        animateEntrance();
        db = new HistoryDb(this);
        accountView = findViewById(R.id.txtAccount);
        previewView = findViewById(R.id.txtPreview);
        statusView = findViewById(R.id.txtStatus);
        recipientInput = findViewById(R.id.edtRecipient);
        subjectInput = findViewById(R.id.edtSubject);
        bodyInput = findViewById(R.id.edtBody);
        messageCountInput = findViewById(R.id.edtMessageCount);
        drawerLayout = findViewById(R.id.drawerLayout);
        authorizedList = findViewById(R.id.authorizedList);
        findViewById(R.id.btnOpenDrawer).setOnClickListener(v -> drawerLayout.openDrawer(android.view.Gravity.START));
        findViewById(R.id.btnCloseDrawer).setOnClickListener(v -> drawerLayout.closeDrawer(android.view.Gravity.START));
        findViewById(R.id.btnAddRecipient).setOnClickListener(v -> showAddAuthorizedRecipient());

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
        refreshAuthorizedRecipients();
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
        // Let Google Play services show its own account chooser. This avoids the
        // deprecated AccountManager chooser and lets us verify the actual token owner.
        authorize(null);
    }

    private void authorize(String email) {
        pendingAccountEmail = email;
        status(email == null
                ? "Opening Google account selection…"
                : "Authorizing saved account " + email + "…");

        java.util.List<Scope> scopes = Arrays.asList(
                new Scope("openid"),
                new Scope("email"),
                new Scope(GMAIL_SEND),
                new Scope(GMAIL_METADATA));

        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(scopes);

        if (email != null && !email.trim().isEmpty()) {
            builder.setAccount(new android.accounts.Account(email, "com.google"));
        }
        // When no account is specified, Google Identity selects from the accounts
        // available on the device and can show its consent/resolution UI as needed.

        Identity.getAuthorizationClient(this).authorize(builder.build())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        PendingIntent pending = result.getPendingIntent();
                        if (pending == null) {
                            pendingAccountEmail = null;
                            status("Google requested additional consent but returned no authorization screen.");
                            return;
                        }
                        try {
                            authLauncher.launch(new IntentSenderRequest.Builder(
                                    pending.getIntentSender()).build());
                        } catch (Exception e) {
                            pendingAccountEmail = null;
                            status("Could not open Google consent: " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage());
                        }
                    } else {
                        handleAuthorization(result);
                    }
                })
                .addOnFailureListener(e -> {
                    pendingAccountEmail = null;
                    String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    status("Google authorization failed: " + detail
                            + ". Check Play Services and the Android OAuth client configuration.");
                });
    }

    private void handleAuthorization(AuthorizationResult result) {
        final String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            pendingAccountEmail = null;
            status("Google returned no access token. Authorization was not completed.");
            return;
        }

        java.util.List<String> granted = result.getGrantedScopes();
        if (granted == null || !granted.contains(GMAIL_SEND)) {
            pendingAccountEmail = null;
            status("Gmail send permission was not granted. Accept the Gmail permission and try again.");
            return;
        }

        final String expectedEmail = pendingAccountEmail;
        status("Verifying the Google account before saving it…");
        io.execute(() -> {
            try {
                String verifiedEmail = fetchVerifiedGoogleEmail(token);
                if (expectedEmail != null && !expectedEmail.equalsIgnoreCase(verifiedEmail)) {
                    runOnUiThread(() -> {
                        pendingAccountEmail = null;
                        status("Account mismatch: selected " + expectedEmail
                                + " but Google authorized " + verifiedEmail + ". Nothing was saved.");
                    });
                    return;
                }

                long rowId = db.addAccount(verifiedEmail);
                if (rowId < 0) {
                    throw new IllegalStateException("SQLite did not save the account (insert returned " + rowId + ").");
                }
                runOnUiThread(() -> {
                    activeEmail = verifiedEmail;
                    accessToken = token;
                    pendingAccountEmail = null;
                    refreshAccounts();
                    status("Account verified and added: " + verifiedEmail);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pendingAccountEmail = null;
                    String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    status("Account was NOT added. Google account verification failed: " + detail);
                });
            }
        });
    }

    private String fetchVerifiedGoogleEmail(String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://openidconnect.googleapis.com/v1/userinfo").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        int code = conn.getResponseCode();
        java.io.InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("Google UserInfo HTTP " + code + ": " + response);
        }
        org.json.JSONObject profile = new org.json.JSONObject(response.toString());
        String email = profile.optString("email", "");
        boolean verified = profile.optBoolean("email_verified", false);
        if (email.isEmpty() || !verified) {
            throw new Exception("Google did not return a verified email address.");
        }
        return email.trim();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_PENDING_EMAIL, pendingAccountEmail);
        super.onSaveInstanceState(outState);
    }

    private void generatePreview() {
        try {
            String baseSubject = subjectInput.getText() == null ? "" : subjectInput.getText().toString().trim();
            String body = bodyInput.getText() == null ? "" : bodyInput.getText().toString().trim();
            int count = Integer.parseInt(messageCountInput.getText().toString().trim());
            if (baseSubject.isEmpty()) throw new IllegalArgumentException("Enter a subject.");
            if (body.isEmpty()) throw new IllegalArgumentException("Enter the message body.");
            if (baseSubject.length() > 120) throw new IllegalArgumentException("Subject must be 120 characters or fewer.");
            if (body.length() > 10000) throw new IllegalArgumentException("Message body must be 10,000 characters or fewer.");
            if (count < 1 || count > 5) {
                throw new IllegalArgumentException("Message count must be between 1 and 5.");
            }

            generatedMessages.clear();
            StringBuilder output = new StringBuilder();
            for (int i = 1; i <= count; i++) {
                String subject = count > 1 ? baseSubject + " (" + i + ")" : baseSubject;
                GeneratedMessage generated = new GeneratedMessage(subject, body);
                generatedMessages.add(generated);
                if (i > 1) output.append("\\n\\n━━━━━━━━━━━━━━━━━━━━\\n\\n");
                output.append("MESSAGE ").append(i)
                        .append("\\nSUBJECT\\n").append(generated.subject)
                        .append("\\n\\nBODY\\n").append(generated.body);
            }
            previewView.animate().cancel();
            previewView.setAlpha(0f);
            previewView.setTranslationY(6f);
            previewView.setText(output.toString());
            previewView.animate().alpha(1f).translationY(0f).setDuration(220L).start();
            status(count + " customized test message(s) generated. Review before sending.");
        } catch (Exception e) { status("Invalid settings: " + e.getMessage()); }
    }

    private void confirmSend() {
        if (activeEmail == null || accessToken == null) {
            status("Authorize a Gmail account first.");
            return;
        }
        if (generatedMessages.isEmpty()) {
            status("Generate messages first.");
            return;
        }
        String recipient = recipientInput.getText() == null ? "" :
                recipientInput.getText().toString().trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(recipient).matches()) {
            status("Enter a valid recipient email address.");
            return;
        }
        if (!db.isRecipientAuthorized(recipient)) {
            status("This address is not on your authorized recipients list. Open the menu to add it.");
            return;
        }
        if (!RateLimiter.canSend(this)) {
            long seconds = (RateLimiter.remainingMs(this) + 999) / 1000;
            status("Local safety interval active. Try again in " + seconds + " seconds.");
            return;
        }
        final int count = generatedMessages.size();
        new AlertDialog.Builder(this)
                .setTitle("Confirm test batch")
                .setMessage("Send all " + count + " generated test message(s) from " + activeEmail
                        + " to the authorized address " + recipient + "?\\n\\n"
                        + "Messages will be sent one by one with the safety interval between sends.")
                .setNegativeButton("Cancel", (d, w) -> {})
                .setPositiveButton("Send all " + count, (d, w) -> sendConfirmed(recipient))
                .show();
    }

    private void sendConfirmed(String recipient) {
        final String token = accessToken;
        final String sender = activeEmail;
        final List<GeneratedMessage> batch = new ArrayList<>(generatedMessages);
        status("Starting confirmed batch of " + batch.size() + " message(s)…");
        io.execute(() -> {
            int sent = 0;
            String lastMessageId = null;
            for (int i = 0; i < batch.size(); i++) {
                GeneratedMessage message = batch.get(i);
                try {
                    long waitMs = RateLimiter.remainingMs(this);
                    if (waitMs > 0) Thread.sleep(waitMs);
                    RateLimiter.recordSendAttempt(this);
                    final int current = i + 1;
                    runOnUiThread(() -> status("Sending message " + current + " of " + batch.size() + "…"));
                    String messageId = GmailApiClient.sendOne(
                            token, sender, recipient, message.subject, message.body);
                    lastMessageId = messageId;
                    sent++;
                    db.addHistory(recipient, message.subject,
                            "Accepted by Gmail; ID " + messageId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    db.addHistory(recipient, message.subject, "Stopped before send: interrupted");
                    break;
                } catch (Exception e) {
                    db.addHistory(recipient, message.subject, "Failed: " + e.getMessage());
                    final String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    final int completed = sent;
                    runOnUiThread(() -> status("Batch stopped after " + completed
                            + " successful send(s). Error: " + error));
                    break;
                }
            }
            final int completed = sent;
            final String finalId = lastMessageId;
            runOnUiThread(() -> {
                if (finalId != null) latestSentMessageId = finalId;
                if (completed == batch.size()) {
                    status("Gmail accepted all " + completed
                            + " message(s). Check send status to verify the latest one in Sent.");
                } else if (completed > 0) {
                    status("Gmail accepted " + completed + " of " + batch.size()
                            + " message(s). The batch stopped; see sent history.");
                }
            });
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


    private void showAddAuthorizedRecipient() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint("name@example.com");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Add authorized address")
                .setMessage("Only add someone who has agreed to receive your test emails.")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    String email = input.getText().toString().trim();
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        status("Enter a valid email address.");
                        return;
                    }
                    long result = db.addAuthorizedRecipient(email);
                    if (result == -1) status("That address is already on the authorized list.");
                    else {
                        recipientInput.setText(email);
                        status("Authorized recipient added: " + email);
                    }
                    refreshAuthorizedRecipients();
                }).show();
    }

    private void refreshAuthorizedRecipients() {
        if (authorizedList == null || db == null) return;
        authorizedList.removeAllViews();
        java.util.List<String> recipients = db.authorizedRecipients();
        if (recipients.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No authorized addresses yet.");
            empty.setTextColor(0xFF9AA4B2);
            empty.setPadding(4, 18, 4, 18);
            authorizedList.addView(empty);
            return;
        }
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        for (String email : recipients) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad / 2, 0, pad / 2);
            TextView address = new TextView(this);
            address.setText(email);
            address.setTextColor(0xFFE8ECF3);
            address.setTextSize(13);
            address.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            MaterialButton remove = new MaterialButton(this);
            remove.setText("Remove");
            remove.setTextSize(10);
            remove.setMinWidth(0);
            remove.setOnClickListener(v -> {
                db.removeAuthorizedRecipient(email);
                refreshAuthorizedRecipients();
                status("Removed authorized recipient: " + email);
            });
            row.addView(address);
            row.addView(remove);
            authorizedList.addView(row);
            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(0x334B5563);
            authorizedList.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void status(String message) { statusView.setText("Status: " + message); }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
