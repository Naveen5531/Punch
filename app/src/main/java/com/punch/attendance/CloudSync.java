package com.punch.attendance;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Google sync: Punch's data is kept as one file in the user's own Google Drive, in the hidden folder only this app
 * can see (appDataFolder). Sign in once with Google; after a reinstall or on a new phone, signing in brings it back.
 * The page (index.html) decides what to save and when; this class signs in and moves the file.
 *
 * Needs, once, in Google Cloud Console: Google Drive API turned on and an Android OAuth client for package
 * com.punch.attendance with this app's SHA-1 (shown in Settings > Google sync).
 */
final class CloudSync {
    static final int SIGNIN_REQ = 91;
    private static final String DRIVE = "https://www.googleapis.com/auth/drive.appdata";
    private static final String EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    private static final String NAME = "punch_data.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor();

    private CloudSync() {}

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("punch_cloud", 0); }

    private static AuthorizationRequest request(String email) {
        AuthorizationRequest.Builder b = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(new Scope(DRIVE), new Scope(EMAIL)));
        if (email != null) b.setAccount(new Account(email, "com.google"));
        return b.build();
    }

    // ---------- sign in / out ----------

    /** Shows Google's account chooser and permission screen (first time), then reports the account to the page. */
    static void signIn(final Activity a) {
        Identity.getAuthorizationClient(a).authorize(request(null))
                .addOnSuccessListener(r -> {
                    if (r.hasResolution() && r.getPendingIntent() != null) {
                        try {
                            a.startIntentSenderForResult(r.getPendingIntent().getIntentSender(), SIGNIN_REQ, null, 0, 0, 0, null);
                        } catch (Exception e) { fail(a, e, "signin"); }
                    } else signedIn(a.getApplicationContext(), r.getAccessToken());
                })
                .addOnFailureListener(e -> fail(a, e, "signin"));
    }

    /** Back from Google's screen. Google also closes it without a reason when the one-time setup is missing. */
    static void result(Context c, int code, Intent data) {
        if (code != Activity.RESULT_OK || data == null) {
            if (data != null) {
                try { Identity.getAuthorizationClient(c).getAuthorizationResultFromIntent(data); }
                catch (ApiException e) { if (e.getStatusCode() != 16 && e.getStatusCode() != 12501) { fail(c, e, "signin"); return; } }
                catch (Exception ignored) {}
            }
            state(c, "error", "Google closed the sign-in without finishing. If the one-time Google setup below is not done yet, "
                    + "do it first (Google cannot sign in to Punch without it). If it is done, wait 5 minutes for Google to take "
                    + "it in and try again (if the app is still in Testing there, Publish it or add your Gmail as a test user).", "setup");
            return;
        }
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(c).getAuthorizationResultFromIntent(data);
            signedIn(c.getApplicationContext(), r.getAccessToken());
        } catch (Exception e) { fail(c, e, "signin"); }
    }

    private static void signedIn(final Context c, final String token) {
        io.execute(() -> {
            try {
                JSONObject me = new JSONObject(http("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token, null, null));
                String email = me.optString("email", "");
                if (email.isEmpty()) throw new IOException("Google did not give the account's email.");
                p(c).edit().putString("email", email).remove("fileId").apply();
                state(c, "in", email, null);
            } catch (Exception e) { fail(c, e, "signin"); }
        });
    }

    static void signOut(Context c) {
        p(c).edit().clear().apply();
        state(c, "out", null, null);
    }

    // ---------- save / load ----------

    /** Saves the page's data (JSON text) as the Drive file. */
    static void push(final Context c, final String json) {
        final String email = p(c).getString("email", null);
        if (email == null) return;
        io.execute(() -> {
            try {
                String token = token(c, email);
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                String id = fileId(c, token);
                if (id != null) {
                    try {
                        http("PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + id + "?uploadType=media&fields=id", token, "application/json", body);
                    } catch (HttpError e) {
                        if (e.code != 404) throw e;
                        id = null;   // deleted on Drive: make it again
                    }
                }
                if (id == null) {
                    String b = "punchpart" + System.currentTimeMillis();
                    ByteArrayOutputStream m = new ByteArrayOutputStream();
                    m.write(("--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                            + "{\"name\":\"" + NAME + "\",\"parents\":[\"appDataFolder\"]}\r\n--" + b
                            + "\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    m.write(body);
                    m.write(("\r\n--" + b + "--").getBytes(StandardCharsets.UTF_8));
                    JSONObject made = new JSONObject(http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                            token, "multipart/related; boundary=" + b, m.toByteArray()));
                    p(c).edit().putString("fileId", made.getString("id")).apply();
                }
                long now = System.currentTimeMillis();
                p(c).edit().putLong("lastPush", now).apply();
                state(c, "saved", String.valueOf(now), null);
            } catch (Exception e) { fail(c, e, "save"); }
        });
    }

    /** Reads the Drive file and gives it to the page (base64 of the JSON text; empty when there is none yet). */
    static void pull(final Context c) {
        final String email = p(c).getString("email", null);
        if (email == null) return;
        io.execute(() -> {
            try {
                String token = token(c, email);
                String id = fileId(c, token);
                String b64 = "";
                if (id != null) {
                    String text = http("GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media", token, null, null);
                    b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                }
                p(c).edit().putLong("lastPull", System.currentTimeMillis()).apply();
                WebHolder.js("window.onCloudData&&onCloudData('" + b64 + "')");
            } catch (Exception e) { fail(c, e, "load"); }
        });
    }

    /** Account, last save, and what the one-time Google setup needs (package and SHA-1), for the page. */
    static String state(Context c) {
        try {
            SharedPreferences s = p(c);
            return new JSONObject()
                    .put("email", s.getString("email", null) == null ? JSONObject.NULL : s.getString("email", null))
                    .put("lastPush", s.getLong("lastPush", 0))
                    .put("pkg", c.getPackageName())
                    .put("sha1", sha1(c)).toString();
        } catch (Exception e) { return "{}"; }
    }

    // ---------- helpers ----------

    /** A fresh access token without any screen (Google refreshes it as needed). */
    private static String token(Context c, String email) throws Exception {
        AuthorizationResult r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(email)), 40, TimeUnit.SECONDS);
        if (r.hasResolution() || r.getAccessToken() == null) throw new NeedSignIn();
        return r.getAccessToken();
    }

    private static String fileId(Context c, String token) throws Exception {
        String id = p(c).getString("fileId", null);
        if (id != null) return id;
        String q = URLEncoder.encode("name='" + NAME + "' and trashed=false", "UTF-8");
        JSONObject list = new JSONObject(http("GET", "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&orderBy=modifiedTime%20desc&fields=files(id)&q=" + q, token, null, null));
        JSONArray f = list.optJSONArray("files");
        if (f == null || f.length() == 0) return null;
        id = f.getJSONObject(0).getString("id");
        p(c).edit().putString("fileId", id).apply();
        return id;
    }

    static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String body) { super("HTTP " + code + ": " + body); this.code = code; }
    }

    static final class NeedSignIn extends Exception {}

    private static String http(String method, String url, String token, String type, byte[] body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            if ("PATCH".equals(method)) { h.setRequestMethod("POST"); h.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
            else h.setRequestMethod(method);
            h.setConnectTimeout(20000);
            h.setReadTimeout(40000);
            h.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", type);
                h.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = h.getOutputStream()) { o.write(body); }
            }
            int code = h.getResponseCode();
            String text = read(code < 400 ? h.getInputStream() : h.getErrorStream());
            if (code >= 400) throw new HttpError(code, text);
            return text;
        } finally { h.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try (InputStream i = in) { while ((n = i.read(buf)) > 0) b.write(buf, 0, n); }
        return b.toString("UTF-8");
    }

    /** The SHA-1 of the key this APK is signed with, as Google Cloud Console asks for it (AA:BB:...). */
    static String sha1(Context c) {
        try {
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder s = new StringBuilder();
            for (byte x : d) { if (s.length() > 0) s.append(':'); s.append(String.format("%02X", x)); }
            return s.toString();
        } catch (Exception e) { return ""; }
    }

    private static void fail(Context c, Exception e, String during) {
        Throwable t = e;
        if (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) t = t.getCause();
        String msg, code = "net";
        if (t instanceof NeedSignIn) { msg = "Google sign-in has expired. Tap Sign in with Google again."; code = "signin"; }
        else if (t instanceof ApiException) {
            int s = ((ApiException) t).getStatusCode();
            if (s == 10) {
                msg = "Google sign-in is not set up for Punch yet. Do the one-time setup below (Android client with package "
                        + c.getPackageName() + " and SHA-1 " + sha1(c) + "), then try again.";
                code = "setup";
            } else if (s == 16 || s == 12501) { msg = "Sign-in was cancelled."; code = "cancel"; }
            else if (s == 7) msg = "No internet. Punch will try again.";
            else msg = "Google sign-in failed (code " + s + ").";
            if (code.equals("net") && "signin".equals(during)) code = "signin";
        } else if (t instanceof HttpError) {
            HttpError h = (HttpError) t;
            String body = h.getMessage() == null ? "" : h.getMessage();
            if (h.code == 401) { msg = "Google sign-in has expired. Tap Sign in with Google again."; code = "signin"; }
            else if (body.contains("accessNotConfigured") || body.contains("SERVICE_DISABLED") || body.contains("has not been used")) {
                msg = "Google Drive API is off in your Google Cloud project. Turn it on (APIs & Services > Library > Google Drive API), then tap Sync now.";
                code = "setup";
            } else if (h.code == 403 && body.contains("storageQuota")) msg = "Your Google Drive is full.";
            else msg = "Google Drive error " + h.code + ". Punch will try again.";
        } else if (t instanceof IOException) msg = "No internet. Punch will try again.";
        else msg = "Google sync: " + t;
        state(c, "error", msg, code);
    }

    private static void state(Context c, String state, String value, String code) {
        try {
            JSONObject o = new JSONObject().put("state", state);
            if ("in".equals(state)) o.put("email", value);
            else if ("saved".equals(state)) o.put("at", Long.parseLong(value));
            else if ("error".equals(state)) o.put("msg", value).put("code", code);
            WebHolder.js("window.onCloud&&onCloud(" + WebHolder.quote(o.toString()) + ")");
        } catch (Exception ignored) {}
    }
}
