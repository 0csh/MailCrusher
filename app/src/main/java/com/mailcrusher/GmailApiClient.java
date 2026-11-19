package com.mailcrusher;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class GmailApiClient {
    private GmailApiClient() {}

    // Sends one user-confirmed message. Access tokens are kept in memory only.
    public static String sendOne(String accessToken, String sender, String recipient,
                                 String subject, String body) throws Exception {
        String mime = "From: " + sender + "\r\n" +
                "To: " + recipient + "\r\n" +
                "Subject: " + subject + "\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + body;
        String raw = Base64.encodeToString(mime.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        org.json.JSONObject payload = new org.json.JSONObject();
        payload.put("raw", raw);

        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("Gmail API HTTP " + code + ": " + response);
        }
        return new org.json.JSONObject(response.toString()).optString("id", "accepted");
    }

    public static String checkSentStatus(String accessToken, String messageId) throws Exception {
        String encodedId = java.net.URLEncoder.encode(messageId, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/" + encodedId
                        + "?format=metadata").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("Gmail API HTTP " + code + ": " + response);
        }
        org.json.JSONObject message = new org.json.JSONObject(response.toString());
        org.json.JSONArray labels = message.optJSONArray("labelIds");
        boolean sent = false;
        if (labels != null) {
            for (int i = 0; i < labels.length(); i++) {
                if ("SENT".equals(labels.optString(i))) sent = true;
            }
        }
        return sent
                ? "Verified: Gmail lists this message in Sent."
                : "Gmail found the message, but it is not currently labeled Sent.";
    }
}
