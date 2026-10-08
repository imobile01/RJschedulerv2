package com.rj.scheduler;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * RJ-Scheduler for Android: shows the app's index.html (in app/src/main/assets)
 * inside a WebView. Records, names and the PIN are kept on the phone (localStorage).
 *
 * The page talks to Android through window.RJAndroid (see Bridge below):
 *  - setPending / getSent : automatic email at each pay-period cut-off, even when the app is closed
 *  - saveFile             : "Download to phone" -> Downloads/RJ-Scheduler
 *  - shareFile            : "Submit as Excel" -> Android share menu (Gmail etc.)
 *  - batteryOk/askBattery : lets the 11:59 PM send run while the phone is asleep
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int PICK_FILE = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#EAF0FB"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Keep the app clear of the status bar and navigation bar (Android 15 draws edge-to-edge).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage for records, names, PIN
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;
                openOutside(uri);                  // mailto:, Gmail and other links
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Lets "Choose from Photos" (wallpaper) open the phone's photo picker.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "No photo app found", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "RJAndroid");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);

        // Android 13+: allow the "Records emailed" notifications.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        Sched.reschedule(this);
    }

    private void openOutside(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            if ("mailto".equals(uri.getScheme())) i = new Intent(Intent.ACTION_SENDTO, uri);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show();
        }
    }

    private static String safeName(String n) {
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Called from the page as window.RJAndroid.* */
    private class Bridge {

        /** The next pay period, already built as Excel, to email at the cut-off (empty = nothing to send). */
        @JavascriptInterface
        public void setPending(String json, String cutoff) {
            long c = 0;
            try { c = Long.parseLong(cutoff); } catch (Exception ignored) { }
            Sched.setPending(getApplicationContext(), json, c);
        }

        /** Pay periods the phone already emailed in the background: {"<cutoff>":{"to":..,"at":..}} */
        @JavascriptInterface
        public String getSent() {
            return Sched.prefs(MainActivity.this).getString("sent", "{}");
        }

        @JavascriptInterface
        public boolean batteryOk() {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void askBattery() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (Exception ignored) { }
                }
            });
        }

        /** "Download to phone": saves the Excel file in Downloads/RJ-Scheduler. */
        @JavascriptInterface
        public boolean saveFile(String name, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                name = safeName(name);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put("_display_name", name);
                    v.put("mime_type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                    v.put("relative_path", Environment.DIRECTORY_DOWNLOADS + "/RJ-Scheduler");
                    Uri u = getContentResolver().insert(Uri.parse("content://media/external/downloads"), v);
                    if (u == null) return false;
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
                    return true;
                }
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 8);
                    return false;
                }
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RJ-Scheduler");
                d.mkdirs();
                try (FileOutputStream o = new FileOutputStream(new File(d, name))) { o.write(data); }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** "Submit as Excel": Android share menu with the file attached and every email filled in. */
        @JavascriptInterface
        public void shareFile(final String fileName, final String base64, final String subject,
                              final String body, final String emails) {
            runOnUiThread(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    File dir = new File(getCacheDir(), "exports");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, safeName(fileName));
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                    Uri uri = FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".files", f);

                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    if (emails != null && !emails.trim().isEmpty()) {
                        send.putExtra(Intent.EXTRA_EMAIL, emails.trim().split("\\s*,\\s*"));
                    }
                    send.putExtra(Intent.EXTRA_SUBJECT, subject);
                    send.putExtra(Intent.EXTRA_TEXT, body);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Send records with"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Couldn't share the Excel file", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }
}
