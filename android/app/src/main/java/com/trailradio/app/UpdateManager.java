package com.trailradio.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks only public releases, and verifies package identity and signer before installation. */
final class UpdateManager {
    private static final String API = "https://api.github.com/repos/tryjumping1993/trail-radio/releases/latest";
    private static final long MAX_APK = 100L * 1024 * 1024;
    private static final long CHECK_INTERVAL = 6L * 60 * 60 * 1000;
    static final int INSTALL_SETTINGS = 103;
    private final Activity activity;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodicCheck = new Runnable() {
        @Override public void run() { check(false); handler.postDelayed(this, CHECK_INTERVAL); }
    };
    private boolean waitingForInstallSettings;

    UpdateManager(Activity activity) { this.activity = activity; }
    void start() { handler.post(periodicCheck); }
    void stop() { handler.removeCallbacks(periodicCheck); }

    void check(boolean manual) {
        long last = activity.getPreferences(Activity.MODE_PRIVATE).getLong("last_update_check", 0);
        if (!manual && System.currentTimeMillis() - last < CHECK_INTERVAL) return;
        if (!checking.compareAndSet(false, true)) {
            if (manual) show("Checking for updates", "An update check is already in progress.");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(readText(API));
                String tag = release.getString("tag_name");
                if (tag.length() > 100) throw new IOException("Invalid release name");
                PackageInfo installed = installedPackage();
                String current = installed.versionName;
                if (tag.equals(current) || tag.equals("v" + current)) {
                    rememberCheck();
                    if (manual) show("You're up to date", "Trail Radio is already on the latest release.");
                    return;
                }
                JSONArray assets = release.getJSONArray("assets");
                String download = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if ("Trail-Radio.apk".equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))) {
                        download = asset.getString("browser_download_url");
                        break;
                    }
                }
                if (download == null) throw new IOException("The latest release has no APK");
                URL assetUrl = new URL(download);
                if (!UpdatePolicy.isInitialAsset(assetUrl)) throw new IOException("Unexpected release URL");
                File apk = new File(activity.getCacheDir(), "trail-radio-update.apk");
                File temp = new File(activity.getCacheDir(), "trail-radio-pending.apk");
                try {
                    downloadApk(download, temp);
                    PackageInfo candidate = archivePackage(temp);
                    if (!activity.getPackageName().equals(candidate.packageName) || !sameSigners(installed, candidate)) throw new IOException("APK identity or signature does not match this installation");
                    if (versionCode(candidate) <= versionCode(installed)) {
                        rememberCheck();
                        if (manual) show("You're up to date", "No newer installable release is available.");
                        return;
                    }
                    if (apk.exists() && !apk.delete()) throw new IOException("Cannot replace old download");
                    if (!temp.renameTo(apk)) throw new IOException("Cannot save update");
                    rememberCheck();
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        new AlertDialog.Builder(activity).setTitle("Update available")
                            .setMessage("Trail Radio " + tag + " is ready to install. Android will ask you to confirm the update.")
                            .setNegativeButton("Later", null).setPositiveButton("Install", (dialog, which) -> beginInstall()).show();
                    });
                } finally { if (temp.exists()) temp.delete(); }
            } catch (Exception error) {
                if (manual) show("Couldn't check for updates", "Could not download or verify the latest update. Check your connection and try again; GitHub may temporarily limit public update requests.");
            } finally { checking.set(false); }
        }, "trail-radio-update").start();
    }
    private void rememberCheck() { activity.getPreferences(Activity.MODE_PRIVATE).edit().putLong("last_update_check", System.currentTimeMillis()).apply(); }
    private void show(String title, String message) { activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }); }
    private PackageInfo installedPackage() throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), flags);
    }
    private PackageInfo archivePackage(File apk) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (info == null) throw new IOException("Invalid APK");
        return info;
    }
    private Signature[] signers(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
    }
    private boolean sameSigners(PackageInfo a, PackageInfo b) {
        Signature[] first = signers(a), second = signers(b);
        return first != null && first.length > 0 && second != null && Arrays.equals(first, second);
    }
    private long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private HttpURLConnection connect(String address, boolean asset) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!UpdatePolicy.isAllowedHost(url, asset)) throw new IOException("Unexpected download host");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Trail-Radio-Android");
            if (!asset) connection.setRequestProperty("Accept", "application/vnd.github+json");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("Missing redirect");
                url = new URL(url, location); continue;
            }
            if (status != 200) { connection.disconnect(); throw new IOException("Update request failed"); }
            return connection;
        }
        throw new IOException("Too many redirects");
    }
    private String readText(String url) throws Exception {
        HttpURLConnection connection = connect(url, false);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, 1024 * 1024); return output.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); }
    }
    private void downloadApk(String url, File file) throws Exception {
        HttpURLConnection connection = connect(url, true);
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) { copyLimited(input, output, MAX_APK); }
        finally { connection.disconnect(); }
    }
    private void copyLimited(InputStream input, java.io.OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[16384]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) { total += count; if (total > limit) throw new IOException("Download too large"); output.write(buffer, 0, count); }
    }
    private void beginInstall() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("Allow updates from Trail Radio")
                .setMessage("Android needs your permission to install updates downloaded by Trail Radio. You can turn this off later in system settings.")
                .setNegativeButton("Not now", null).setPositiveButton("Open settings", (dialog, which) -> {
                    waitingForInstallSettings = true;
                    try { activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())), INSTALL_SETTINGS); }
                    catch (Exception error) { waitingForInstallSettings = false; show("Couldn't open settings", "Allow Trail Radio to install updates in Android settings, then check for updates again."); }
                }).show();
            return;
        }
        install();
    }
    void onSettingsResult() { if (waitingForInstallSettings) { waitingForInstallSettings = false; if (activity.getPackageManager().canRequestPackageInstalls()) install(); } }
    private void install() {
        try {
            File apk = new File(activity.getCacheDir(), "trail-radio-update.apk");
            PackageInfo installed = installedPackage(), candidate = archivePackage(apk);
            if (!activity.getPackageName().equals(candidate.packageName) || !sameSigners(installed, candidate) || versionCode(candidate) <= versionCode(installed)) throw new IOException("Update is no longer valid");
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/update.apk");
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Trail Radio update", uri));
            activity.startActivity(intent);
        } catch (Exception error) { show("Couldn't install update", "Please check for updates again or download the APK from the Trail Radio releases page."); }
    }
}
