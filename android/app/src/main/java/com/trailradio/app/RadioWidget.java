package com.trailradio.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.RemoteViews;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home-screen widget: the group, what the channel is doing, a talk button and the waveform of
 * whoever is talking. A widget only knows taps, so the button starts talking and the next tap
 * stops (the page ends a widget transmission after a minute). RadioService refreshes the widget
 * while a group is active; otherwise it shows the radio as off and opens the app.
 */
public final class RadioWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "com.trailradio.app.WIDGET_TOGGLE";
    private static final int WAVE_W = 480, WAVE_H = 100;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RadioService service = RadioService.current();
        if (service != null) service.refreshWidgetSoon(); else show(context, null);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            RadioRuntime runtime = RadioRuntime.existing();
            RadioService service = RadioService.current();
            // A stale button after the group ended: show the radio as off (opening the app from
            // a broadcast is not allowed in the background).
            if (runtime == null || !runtime.hasSession() || service == null) { show(context, null); return; }
            runtime.widgetToggle();
            service.refreshWidgetSoon();
            return;
        }
        super.onReceive(context, intent);
    }

    static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, RadioWidget.class));
    }

    /** Shows `state` (the page's radioWidgetState) on every widget; null shows the radio as off. */
    static void show(Context context, JSONObject state) {
        int[] ids = ids(context);
        if (ids.length == 0) return;
        String status = state == null ? "offline" : state.optString("state", "offline");
        boolean offline = "offline".equals(status);
        boolean on = "talking".equals(status) || "waiting".equals(status);
        String group = state == null ? "" : state.optString("group", "").trim();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.radio_widget);
        views.setTextViewText(R.id.widget_group, group.isEmpty() ? "BIG WALK RADIO" : group.toUpperCase(Locale.ROOT));
        views.setTextViewText(R.id.widget_status, statusText(status, state == null ? "" : state.optString("speaker", "")));
        views.setTextViewText(R.id.widget_talk, offline ? "OPEN" : on ? "STOP" : "TALK");
        views.setInt(R.id.widget_talk, "setBackgroundResource", on ? R.drawable.widget_talk_on : R.drawable.widget_talk);
        views.setImageViewBitmap(R.id.widget_wave, waveform(state == null ? null : state.optJSONArray("wave")));
        Intent openApp = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getBroadcast(context, 1, new Intent(context, RadioWidget.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_talk, offline ? open : toggle);
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views);
    }

    private static String statusText(String status, String speaker) {
        switch (status) {
            case "talking": return "YOU’RE ON · TAP TO STOP";
            case "waiting": return "ASKING FOR THE CHANNEL…";
            case "listening": return speaker.trim().isEmpty() ? "SOMEONE IS TALKING" : speaker.trim().toUpperCase(Locale.ROOT) + " IS TALKING";
            case "reconnecting": return "RECONNECTING…";
            case "no-location": return "WAITING FOR GPS";
            case "open": return "CHANNEL OPEN · TAP TO TALK";
            default: return "OFF · TAP TO OPEN";
        }
    }

    /** The waveform (samples from -1 to 1), or a flat line while the channel is quiet. */
    private static Bitmap waveform(JSONArray wave) {
        Bitmap bitmap = Bitmap.createBitmap(WAVE_W, WAVE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff385446);
        float mid = WAVE_H / 2f;
        Path path = new Path();
        int n = wave == null ? 0 : wave.length();
        if (n < 2) { path.moveTo(2, mid); path.lineTo(WAVE_W - 2, mid); }
        else for (int i = 0; i < n; i++) {
            double v = Math.max(-1, Math.min(1, wave.optDouble(i, 0)));
            float x = 2 + (WAVE_W - 4) * i / (float) (n - 1), y = (float) (mid - v * (mid - 4) * 1.6);
            y = Math.max(2, Math.min(WAVE_H - 2, y));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
        return bitmap;
    }
}
