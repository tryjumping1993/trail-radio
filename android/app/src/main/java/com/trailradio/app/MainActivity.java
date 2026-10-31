package com.trailradio.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.util.Arrays;

public class MainActivity extends Activity {
    private WebView web;
    private UpdateManager updates;
    private PermissionRequest microphoneRequest;
    private GeolocationPermissions.Callback locationCallback;
    private String locationOrigin;
    private static final int MIC_REQUEST = 101, LOCATION_REQUEST = 102;
    private static final String ORIGIN = "https://trailradio.local";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new UpdateManager(this);
        web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setGeolocationEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new ShareBridge(), "TrailRadioNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"trailradio.local".equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (!Arrays.asList("/index.html", "/style.css", "/app.js").contains(path)) return new WebResourceResponse("text/plain", "UTF-8", null);
                String type = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css" : "text/javascript";
                try { return new WebResourceResponse(type, "UTF-8", getAssets().open(path.substring(1))); }
                catch (IOException e) { return new WebResourceResponse("text/plain", "UTF-8", null); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("trailradio.local".equals(request.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!ORIGIN.equals(request.getOrigin().toString()) || !Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) { request.deny(); return; }
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    else { microphoneRequest = request; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST); }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (microphoneRequest == request) microphoneRequest = null; }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!ORIGIN.equals(origin)) { callback.invoke(origin, false, false); return; }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) callback.invoke(origin, true, false);
                else { locationOrigin = origin; locationCallback = callback; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST); }
            }
        });
        openIntent(getIntent());
        updates.start();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openIntent(intent); }
    private void openIntent(Intent intent) {
        Uri data = intent.getData();
        String room = data == null ? null : data.getQueryParameter("room");
        web.loadUrl(ORIGIN + "/index.html" + (room != null && room.matches("tr-[a-f0-9]{16}") ? "?room=" + room : ""));
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == MIC_REQUEST && microphoneRequest != null) {
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
    private class ShareBridge {
        @JavascriptInterface public void checkForUpdates() { updates.check(true); }
        @JavascriptInterface public void shareInvite(String text) {
            if (text == null || text.length() > 1000) return;
            runOnUiThread(() -> { Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(send, "Invite your crew")); });
        }
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { updates.stop(); if (microphoneRequest != null) microphoneRequest.deny(); if (locationCallback != null) locationCallback.invoke(locationOrigin, false, false); web.destroy(); super.onDestroy(); }
}
