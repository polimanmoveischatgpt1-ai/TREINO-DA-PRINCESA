package com.gym.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private static final int NOTIFICATION_REQUEST = 1202;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(11, 13, 18));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(11, 13, 18));

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception ex) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(this), "GymNative");
        loadGymInterface();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void loadGymInterface() {
        try (InputStream in = getAssets().open("index.html");
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder html = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                html.append(buffer, 0, count);
            }
            String corrected = html.toString().replace(
                    "if(!c)return,ctx=",
                    "if(!c)return;let ctx="
            );
            webView.loadDataWithBaseURL(
                    "file:///android_asset/",
                    corrected,
                    "text/html",
                    "UTF-8",
                    null
            );
        } catch (Exception ex) {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class AndroidBridge {
        private final Context context;
        AndroidBridge(Context context) { this.context = context; }

        private void sendTimerAction(String action, int seconds, String label, boolean vibration, boolean sound) {
            Intent intent = new Intent(context, TimerService.class);
            intent.setAction(action);
            intent.putExtra("seconds", seconds);
            intent.putExtra("label", label == null ? "Descanso" : label);
            intent.putExtra("vibration", vibration);
            intent.putExtra("sound", sound);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        }

        @JavascriptInterface
        public void startTimer(int seconds, String label, boolean vibration, boolean sound) {
            sendTimerAction(TimerService.ACTION_START, Math.max(1, seconds), label, vibration, sound);
        }

        @JavascriptInterface
        public void addTimer(int seconds) {
            sendTimerAction(TimerService.ACTION_ADD, Math.max(1, seconds), "", true, true);
        }

        @JavascriptInterface
        public void pauseTimer() {
            sendTimerAction(TimerService.ACTION_PAUSE, 0, "", true, true);
        }

        @JavascriptInterface
        public void resumeTimer() {
            sendTimerAction(TimerService.ACTION_RESUME, 0, "", true, true);
        }

        @JavascriptInterface
        public void cancelTimer() {
            Intent intent = new Intent(context, TimerService.class);
            intent.setAction(TimerService.ACTION_STOP);
            context.startService(intent);
        }

        @JavascriptInterface
        public String getTimerState() {
            try {
                SharedPreferences p = context.getSharedPreferences(TimerService.PREFS, MODE_PRIVATE);
                boolean active = p.getBoolean("active", false);
                boolean running = p.getBoolean("running", false);
                long endElapsed = p.getLong("endElapsed", 0L);
                long pausedMs = p.getLong("pausedMs", 0L);
                long remainingMs = running ? Math.max(0L, endElapsed - android.os.SystemClock.elapsedRealtime()) : Math.max(0L, pausedMs);
                JSONObject o = new JSONObject();
                o.put("active", active && remainingMs > 0);
                o.put("running", running && remainingMs > 0);
                o.put("remainingMs", remainingMs);
                o.put("label", p.getString("label", "Descanso"));
                return o.toString();
            } catch (Exception e) {
                return "{\"active\":false,\"running\":false,\"remainingMs\":0,\"label\":\"Descanso\"}";
            }
        }

        @JavascriptInterface
        public void vibrate(int milliseconds) {
            if (milliseconds <= 0) return;
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(milliseconds);
        }

        @JavascriptInterface
        public void keepScreenOn(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                    }
                });
            }
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        }

        @JavascriptInterface
        public void shareBackup(String json) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "Backup Gym");
            share.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(share, "Compartilhar backup do Gym"));
        }

        @JavascriptInterface
        public String version() {
            return "1.0.0";
        }
    }
}
