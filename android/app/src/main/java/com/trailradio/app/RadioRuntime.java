package com.trailradio.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/** Owns the live WebView independently of an Activity while a group is active. */
final class RadioRuntime {
    static final String ORIGIN = "https://trailradio.local";
    private static RadioRuntime instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableContextWrapper webContext;
    private final WebView web;
    private volatile WeakReference<MainActivity> activity = new WeakReference<>(null);
    private boolean sessionRequested;
    private boolean connected;

    static RadioRuntime get(Context context) {
        if (instance == null) instance = new RadioRuntime(context.getApplicationContext());
        return instance;
    }
    static RadioRuntime existing() { return instance; }
    private RadioRuntime(Context app) {
        this.app = app;
        webContext = new MutableContextWrapper(app);
        web = new WebView(webContext);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setGeolocationEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "TrailRadioNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"trailradio.local".equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (!Arrays.asList("/index.html", "/style.css", "/app.js").contains(path)) return new WebResourceResponse("text/plain", "UTF-8", null);
                String type = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css" : "text/javascript";
                try { return new WebResourceResponse(type, "UTF-8", app.getAssets().open(path.substring(1))); }
                catch (IOException e) { return new WebResourceResponse("text/plain", "UTF-8", null); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("trailradio.local".equals(request.getUrl().getHost())) return false;
                MainActivity host = activity.get();
                if (host != null) { try { host.startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); } catch (Exception ignored) { } }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!TrustedOrigin.matches(request.getOrigin() == null ? null : request.getOrigin().toString()) || !Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) { request.deny(); return; }
                if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else { MainActivity host = activity.get(); if (host == null) request.deny(); else host.requestWebMicrophone(request); }
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { MainActivity host = activity.get(); if (host != null) host.cancelWebMicrophone(request); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!TrustedOrigin.matches(origin)) { callback.invoke(origin, false, false); return; }
                if (hasLocation()) callback.invoke(origin, true, false);
                else { MainActivity host = activity.get(); if (host == null) callback.invoke(origin, false, false); else host.requestWebLocation(origin, callback); }
            }
        });
    }
    private boolean hasLocation() { return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    void attach(MainActivity host, ViewGroup root) {
        activity = new WeakReference<>(host);
        webContext.setBaseContext(host);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        root.addView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    void detach(MainActivity host) {
        if (activity.get() != host) return;
        activity.clear();
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        webContext.setBaseContext(app);
        if (!sessionRequested) dispose();
    }
    WebView web() { return web; }
    boolean hasSession() { return sessionRequested; }
    boolean isConnected() { return connected; }
    void load(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String room = data == null ? null : data.getQueryParameter("room");
        web.loadUrl(ORIGIN + "/index.html" + (room != null && room.matches("tr-[a-f0-9]{16}") ? "?room=" + room : ""));
    }
    void markSessionRequested() { sessionRequested = true; }
    void serviceStarted(boolean ok) {
        if (!ok) { sessionRequested = false; connected = false; }
        execute("window.radioServiceStarted && window.radioServiceStarted(" + ok + ")");
        if (!ok && activity.get() == null) main.post(this::dispose);
    }
    void serviceStoppedUnexpectedly() {
        if (!sessionRequested) return;
        sessionRequested = false; connected = false;
        execute("window.radioServiceStopped && window.radioServiceStopped()");
        if (activity.get() == null) main.post(this::dispose);
    }
    void stopSession() {
        sessionRequested = false; connected = false;
        app.stopService(new Intent(app, RadioService.class));
        if (activity.get() == null) main.post(this::dispose);
    }
    void location(double latitude, double longitude, long timestamp) {
        if (!sessionRequested || !Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
        execute("window.radioNativeLocation && window.radioNativeLocation(" + latitude + "," + longitude + "," + timestamp + ")");
    }
    void phonePermissionResult(boolean ok) { execute("window.trailRadioPermissionResult && window.trailRadioPermissionResult(" + ok + ")"); }
    private void execute(String script) { main.post(() -> { if (instance == this) web.evaluateJavascript(script, null); }); }
    private void dispose() {
        if (instance != this || sessionRequested || activity.get() != null) return;
        web.removeJavascriptInterface("TrailRadioNative");
        web.destroy();
        instance = null;
    }
    private final class Bridge {
        @JavascriptInterface public void requestPhonePermissions() { MainActivity host = activity.get(); if (host != null) host.runOnUiThread(host::requestPhonePermissions); else phonePermissionResult(false); }
        @JavascriptInterface public void startRadioSession() { MainActivity host = activity.get(); if (host != null) host.runOnUiThread(host::requestRadioService); else serviceStarted(false); }
        @JavascriptInterface public void stopRadioSession() { main.post(RadioRuntime.this::stopSession); }
        @JavascriptInterface public void radioConnected(String group) { main.post(() -> { connected = true; RadioService service = RadioService.current(); if (service != null) service.updateGroup(group); MainActivity host = activity.get(); if (host != null) host.maybeRequestBatteryExemption(); }); }
        @JavascriptInterface public void openAppSettings() { MainActivity host = activity.get(); if (host != null) host.runOnUiThread(host::openAppSettings); }
        @JavascriptInterface public void checkForUpdates() { MainActivity host = activity.get(); if (host != null) host.runOnUiThread(host::checkForUpdates); }
        @JavascriptInterface public void shareInvite(String text) { MainActivity host = activity.get(); if (host != null && text != null && text.length() <= 1000) host.runOnUiThread(() -> host.shareInvite(text)); }
    }
}
