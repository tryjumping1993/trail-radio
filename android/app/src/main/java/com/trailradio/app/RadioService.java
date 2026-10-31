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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps an active radio session and location updates alive while its screen is hidden. */
public final class RadioService extends Service {
    private static final String CHANNEL = "radio_session";
    private static final int NOTIFICATION = 1101;
    private static RadioService current;
    private PowerManager.WakeLock wakeLock;
    private LocationManager locations;
    private Location lastLocation;
    private String group = "";
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
        String text = group.isEmpty() ? "Connecting to your group" : "Connected to " + group + " · Tap to return";
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Big Walk Radio is on").setContentText(text).setContentIntent(tap)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    void updateGroup(String value) {
        group = value == null ? "" : value.trim().substring(0, Math.min(value.trim().length(), 32));
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
        if (locations != null) { try { locations.removeUpdates(listener); } catch (SecurityException ignored) { } locations = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (current == this) current = null;
        RadioRuntime runtime = RadioRuntime.existing();
        if (runtime != null) runtime.serviceStoppedUnexpectedly();
        super.onDestroy();
    }
}
