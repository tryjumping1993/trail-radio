package com.trailradio.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private WebView web;
    private RadioRuntime runtime;
    private UpdateManager updates;
    private FrameLayout root;
    private PermissionRequest microphoneRequest;
    private GeolocationPermissions.Callback locationCallback;
    private String locationOrigin;
    private static final int MIC_REQUEST = 101, LOCATION_REQUEST = 102, PHONE_PERMISSIONS_REQUEST = 104, NOTIFICATION_REQUEST = 105;

    void setBackdrop(int color) { if (root != null) root.setBackgroundColor(color); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new UpdateManager(this);
        runtime = RadioRuntime.get(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(runtime.backdrop());
        runtime.attach(this, root);
        web = runtime.web();
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboardBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboardBottom));
                return insets;
            });
        }
        setContentView(root);
        if (!runtime.hasSession()) runtime.load(getIntent());
        updates.start();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!runtime.hasSession()) runtime.load(intent);
        else if (intent.getData() != null) Toast.makeText(this, "Leave your current group before joining another.", Toast.LENGTH_LONG).show();
    }
    void requestWebMicrophone(PermissionRequest request) { microphoneRequest = request; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST); }
    void cancelWebMicrophone(PermissionRequest request) { if (microphoneRequest == request) microphoneRequest = null; }
    void requestWebLocation(String origin, GeolocationPermissions.Callback callback) { locationOrigin = origin; locationCallback = callback; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PHONE_PERMISSIONS_REQUEST) runtime.phonePermissionResult(hasPhonePermissions());
        else if (code == NOTIFICATION_REQUEST) startRadioService();
        else if (code == MIC_REQUEST && microphoneRequest != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) microphoneRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); else microphoneRequest.deny();
            microphoneRequest = null;
        } else if (code == LOCATION_REQUEST && locationCallback != null) {
            boolean granted = false; for (int result : results) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            locationCallback.invoke(locationOrigin, granted, false); locationCallback = null; locationOrigin = null;
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UpdateManager.INSTALL_SETTINGS) updates.onSettingsResult();
    }
    private boolean hasPhonePermissions() {
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return microphone && location;
    }
    void requestPhonePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (missing.isEmpty()) runtime.phonePermissionResult(true);
        else requestPermissions(missing.toArray(new String[0]), PHONE_PERMISSIONS_REQUEST);
    }
    void requestRadioService() {
        if (!hasPhonePermissions()) { runtime.serviceStarted(false); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("asked_radio_notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_radio_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else startRadioService();
    }
    private void startRadioService() {
        runtime.markSessionRequested();
        try { startForegroundService(new Intent(this, RadioService.class)); }
        catch (RuntimeException error) { runtime.serviceStarted(false); }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && runtime != null && runtime.isConnected()) maybeRequestBatteryExemption();
    }
    void maybeRequestBatteryExemption() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (!hasWindowFocus() || power.isIgnoringBatteryOptimizations(getPackageName()) || getPreferences(MODE_PRIVATE).getBoolean("asked_radio_battery", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked_radio_battery", true).apply();
        new AlertDialog.Builder(this).setTitle("Keep listening with the screen off")
            .setMessage("Android can pause the radio connection to save battery. Allow unrestricted battery use to keep your group connected while the screen is off.")
            .setNegativeButton("Not now", null).setPositiveButton("Continue", (dialog, which) -> {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (RuntimeException error) { Toast.makeText(this, "You can allow unrestricted battery use in Android settings.", Toast.LENGTH_LONG).show(); }
            }).show();
    }
    void openAppSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    void checkForUpdates() { updates.check(true); }
    void shareInvite(String text) { Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(send, "Invite your crew")); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        updates.stop();
        if (microphoneRequest != null) microphoneRequest.deny();
        if (locationCallback != null) locationCallback.invoke(locationOrigin, false, false);
        runtime.detach(this);
        super.onDestroy();
    }
}
