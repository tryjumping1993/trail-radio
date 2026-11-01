package com.trailradio.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONObject;

/** Keeps an active radio session and location updates alive while its screen is hidden. */
public final class RadioService extends Service {
    private static final String CHANNEL = "radio_session";
    private static final int NOTIFICATION = 1101;
    private static RadioService current;
    private PowerManager.WakeLock wakeLock;
    private LocationManager locations;
    private Location lastLocation;
    private String group = "";
    private boolean networkReady = true;
    private ConnectivityManager connectivity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            RadioRuntime runtime = RadioRuntime.existing();
            if (runtime != null) runtime.backgroundTick();
            handler.postDelayed(this, 10000);
        }
    };
    // The home-screen widgets follow the page: several times a second while there is audio to
    // draw, every second otherwise (only redrawn when something changed), and not at all
    // without widgets. One request runs at a time.
    private boolean widgetPending;
    private String widgetShown = "";
    private final Runnable widgetLoop = new Runnable() {
        @Override public void run() {
            if (widgetPending) return;
            RadioRuntime runtime = RadioRuntime.existing();
            if (runtime == null || RadioWidget.ids(RadioService.this).length == 0) { handler.postDelayed(this, 5000); return; }
            widgetPending = true;
            runtime.widgetState(json -> {
                widgetPending = false;
                if (current != RadioService.this) return;
                JSONObject state = null;
                try { if (json != null && !"null".equals(json)) state = new JSONObject(json); } catch (Exception ignored) { }
                boolean live = state != null && state.optJSONArray("wave") != null && state.optJSONArray("wave").length() > 0;
                String key = String.valueOf(json);
                if (live || !key.equals(widgetShown)) RadioWidget.show(RadioService.this, state);
                widgetShown = key;
                handler.postDelayed(widgetLoop, live ? 150 : 1000);
            });
        }
    };
    /** Redraws the widgets right away (after a tap on one). */
    void refreshWidgetSoon() { widgetShown = ""; handler.removeCallbacks(widgetLoop); handler.postDelayed(widgetLoop, 120); }
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            RadioRuntime runtime = RadioRuntime.existing();
            if (runtime != null) runtime.networkAvailable();
        }
    };
    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { deliver(location); }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };
    static RadioService current() { return current; }
    @Override public void onCreate() { super.onCreate(); current = this; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Active radio group", NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else if (Build.VERSION.SDK_INT == 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION, notification());
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (wakeLock == null) wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BigWalkRadio:active-group");
            if (!wakeLock.isHeld()) wakeLock.acquire();
            startLocations();
            if (connectivity == null) {
                try {
                    connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                    connectivity.registerDefaultNetworkCallback(networkCallback);
                } catch (RuntimeException error) { connectivity = null; }
            }
            handler.removeCallbacks(tick); handler.postDelayed(tick, 10000);
            handler.removeCallbacks(widgetLoop); handler.post(widgetLoop);
            RadioRuntime runtime = RadioRuntime.existing();
            if (runtime != null) runtime.serviceStarted(true); else stopSelf();
        } catch (RuntimeException error) {
            RadioRuntime runtime = RadioRuntime.existing();
            if (runtime != null) runtime.serviceStarted(false);
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = group.isEmpty() ? "Connecting to your group" : (networkReady ? "Connected to " : "Reconnecting to ") + group + " · Tap to return";
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Big Walk Radio is on").setContentText(text).setContentIntent(tap)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    void updateGroup(String value) {
        group = value == null ? "" : value.trim().substring(0, Math.min(value.trim().length(), 32));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
    }
    void updateNetwork(boolean ready) {
        networkReady = ready;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
    }
    private void startLocations() {
        if (locations != null) return;
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (LocationManager.GPS_PROVIDER.equals(provider) && !fine) continue;
            try {
                if (!locations.isProviderEnabled(provider)) continue;
                locations.requestLocationUpdates(provider, 5000, 0, listener);
                deliver(locations.getLastKnownLocation(provider));
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
    }
    private void deliver(Location location) {
        if (location == null) return;
        long now = System.currentTimeMillis(), age = now - location.getTime();
        if (age < -5000 || age > 30000) return;
        if (lastLocation != null && location.getTime() < lastLocation.getTime()) return;
        if (lastLocation != null && now - lastLocation.getTime() < 20000 && location.hasAccuracy() && lastLocation.hasAccuracy() && location.getAccuracy() > lastLocation.getAccuracy() * 2) return;
        lastLocation = location;
        RadioRuntime runtime = RadioRuntime.existing();
        if (runtime != null) runtime.location(location.getLatitude(), location.getLongitude(), location.getTime());
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(widgetLoop);
        RadioWidget.show(this, null);
        if (connectivity != null) { try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { } connectivity = null; }
        if (locations != null) { try { locations.removeUpdates(listener); } catch (SecurityException ignored) { } locations = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (current == this) current = null;
        RadioRuntime runtime = RadioRuntime.existing();
        if (runtime != null) runtime.serviceStoppedUnexpectedly();
        super.onDestroy();
    }
}
