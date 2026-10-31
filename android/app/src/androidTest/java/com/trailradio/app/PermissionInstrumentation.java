package com.trailradio.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Integration probe: run on an emulator with microphone and location already granted. */
public final class PermissionInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    private String eval(Activity activity, WebView web, String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        activity.runOnUiThread(() -> web.evaluateJavascript(script, value -> { result.set(value); latch.countDown(); }));
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("WebView evaluation timed out");
        return result.get();
    }
    private WebView findWebView(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);
            if (child instanceof WebView) return (WebView) child;
            if (child instanceof ViewGroup) { WebView nested = findWebView((ViewGroup) child); if (nested != null) return nested; }
        }
        return null;
    }
    private void assertWebViewInsideSystemBars(Activity activity, WebView web) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 35) return;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            try {
                android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                int bottomInset = Math.max(safe.bottom, insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);
                android.graphics.Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                int[] origin = new int[2]; web.getLocationOnScreen(origin);
                if (origin[0] < bounds.left + safe.left || origin[1] < bounds.top + safe.top || origin[0] + web.getWidth() > bounds.right - safe.right || origin[1] + web.getHeight() > bounds.bottom - bottomInset)
                    failure.set("WebView overlaps system bars: web=" + origin[0] + "," + origin[1] + "," + web.getWidth() + "," + web.getHeight() + " window=" + bounds + " safe=" + safe);
            } catch (Exception error) { failure.set(error.toString()); }
            finally { latch.countDown(); }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Insets check timed out");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void assertVisible(Activity activity, WebView web, String ids) throws Exception {
        String expression = "[" + ids + "].every(id=>{let e=document.getElementById(id),r=e.getBoundingClientRect();return !e.hidden && r.left>=0 && r.top>=0 && r.right<=innerWidth && r.bottom<=innerHeight;})";
        String visible = eval(activity, web, expression);
        if (!"true".equals(visible)) {
            String debug = eval(activity, web, "JSON.stringify({height:innerHeight,width:innerWidth,scrollY:scrollY,visual:visualViewport?{height:visualViewport.height,offsetTop:visualViewport.offsetTop}:null,rects:[" + ids + "].map(id=>{let r=document.getElementById(id).getBoundingClientRect();return {id,top:r.top,bottom:r.bottom,left:r.left,right:r.right}})})");
            throw new AssertionError("Controls outside visible WebView: " + debug);
        }
    }
    private boolean keyboardVisible(Activity activity) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        activity.runOnUiThread(() -> {
            android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) visible.set(insets.isVisible(android.view.WindowInsets.Type.ime()));
            latch.countDown();
        });
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Keyboard check timed out");
        return visible.get();
    }
    private String waitFor(Activity activity, WebView web, String script, String expected, int seconds) throws Exception {
        String value = "";
        for (int i=0; i<seconds; i++) {
            value=eval(activity, web, script);
            if (expected.equals(value)) return value;
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out: " + script + " => " + value);
    }
    private void backgroundPeerTest(Activity activity, WebView web, String role, Bundle result) throws Exception {
        String room = arguments.getString("room", "");
        if ("client".equals(role) && !room.matches("tr-[a-f0-9]{16}")) throw new AssertionError("Missing host room");
        if ("client".equals(role)) {
            eval(activity, web, "document.getElementById('join-tab').click();document.getElementById('code').value='" + room + "';");
        }
        eval(activity, web, "document.getElementById('name').value='" + role + "';document.getElementById('room-name').value='Background Test';document.getElementById('setup-form').requestSubmit();");
        waitFor(activity, web, "document.getElementById('connected').hidden ? 'waiting' : 'connected'", "\"connected\"", 45);
        if (RadioService.current() == null) throw new AssertionError("Foreground radio service not running");
        if ("host".equals(role) || "host-detached".equals(role)) {
            room = new org.json.JSONArray("[" + eval(activity, web, "document.getElementById('room-code').textContent") + "]").getString(0);
            eval(activity, web, "window.__audioPlays=0; const oldCreate=document.createElement.bind(document);document.createElement=(tag,...args)=>{const el=oldCreate(tag,...args);if(String(tag).toLowerCase()==='audio')el.addEventListener('playing',()=>window.__audioPlays++);return el;};");
            CountDownLatch moved = new CountDownLatch(1);
            activity.runOnUiThread(() -> { activity.moveTaskToBack(true); moved.countDown(); });
            if (!moved.await(10, TimeUnit.SECONDS)) throw new AssertionError("Could not background activity");
            if ("host-detached".equals(role)) {
                activity.runOnUiThread(activity::finish);
                for (int i=0; i<20 && !activity.isDestroyed(); i++) Thread.sleep(250);
                if (!activity.isDestroyed() || RadioService.current() == null) throw new AssertionError("Could not detach the host activity while retaining service");
            }
            Bundle ready = new Bundle(); ready.putString("phase", "host-ready"); ready.putString("room", room); sendStatus(1, ready);
            Thread.sleep(35000);
            android.os.PowerManager power = (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (power.isInteractive()) throw new AssertionError("Screen was not turned off for background test");
            if (RadioService.current() == null) throw new AssertionError("Foreground service stopped while screen was off");
            Bundle listening = new Bundle(); listening.putString("phase", "host-listening"); listening.putString("room", room); sendStatus(1, listening);
            waitFor(activity, web, "window.__audioPlays > 0", "true", 45);
            if (!"false".equals(eval(activity, web, "document.getElementById('connected').hidden"))) throw new AssertionError("Host left the group while backgrounded");
            result.putString("result", "PASS: screen-off host stayed in the group and played incoming audio");
        } else {
            Bundle ready = new Bundle(); ready.putString("phase", "client-ready"); sendStatus(1, ready);
            Thread.sleep(40000);
            android.os.PowerManager power = (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (power.isInteractive()) throw new AssertionError("Screen was not turned off for client test");
            eval(activity, web, "document.getElementById('talk').dispatchEvent(new KeyboardEvent('keydown',{key:' ',bubbles:true}));");
            waitFor(activity, web, "document.getElementById('screen-label').textContent", "\"TRANSMITTING\"", 15);
            Thread.sleep(10000);
            eval(activity, web, "document.getElementById('talk').dispatchEvent(new KeyboardEvent('keyup',{key:' ',bubbles:true}));");
            result.putString("result", "PASS: client joined and transmitted");
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent);
            WebView web = findWebView((ViewGroup) activity.findViewById(android.R.id.content));
            if (web == null) throw new AssertionError("WebView not found");
            String state = "";
            for (int i=0; i<45; i++) { state = eval(activity, web, "document.readyState"); if (state.contains("complete")) break; Thread.sleep(1000); }
            if (!state.contains("complete")) throw new AssertionError("WebView page did not finish loading: " + state);
            String role = arguments == null ? "" : arguments.getString("role", "");
            if ("host".equals(role) || "host-detached".equals(role) || "client".equals(role)) { backgroundPeerTest(activity, web, role, result); finish(Activity.RESULT_OK,result); return; }
            if ("no-notification".equals(role) || "battery-prompt".equals(role)) {
                if ("no-notification".equals(role)) activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("asked_radio_notifications", true).apply();
                else activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("asked_radio_battery", false).apply();
                eval(activity, web, "document.getElementById('name').value='Settings Test';document.getElementById('setup-form').requestSubmit();");
                waitFor(activity, web, "document.getElementById('connected').hidden ? 'waiting' : 'connected'", "\"connected\"", 45);
                if (RadioService.current() == null) throw new AssertionError("Service missing after joining");
                if ("battery-prompt".equals(role)) {
                    for (int i=0; i<10 && !activity.getPreferences(Activity.MODE_PRIVATE).getBoolean("asked_radio_battery", false); i++) Thread.sleep(500);
                    if (!activity.getPreferences(Activity.MODE_PRIVATE).getBoolean("asked_radio_battery", false)) throw new AssertionError("Battery exemption prompt was not offered");
                    result.putString("result", "PASS: battery exemption offered for a connected group");
                } else {
                    eval(activity, web, "document.getElementById('leave').click()");
                    for (int i=0; i<10 && RadioService.current()!=null; i++) Thread.sleep(500);
                    if (RadioService.current()!=null) throw new AssertionError("Service did not stop on leave");
                    result.putString("result", "PASS: group works without notification permission");
                }
                finish(Activity.RESULT_OK,result); return;
            }
            assertWebViewInsideSystemBars(activity, web);
            assertVisible(activity, web, "\"check-update\",\"screen-label\"");
            eval(activity, web, "document.getElementById('name').focus()");
            activity.runOnUiThread(() -> {
                web.requestFocus();
                android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                keyboard.showSoftInput(web, 0);
            });
            for (int i=0; i<10 && !keyboardVisible(activity); i++) Thread.sleep(500);
            if (!keyboardVisible(activity)) throw new AssertionError("Could not open emulator keyboard for inset test");
            Thread.sleep(500);
            assertWebViewInsideSystemBars(activity, web);
            assertVisible(activity, web, "\"name\"");
            activity.runOnUiThread(() -> {
                android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(web.getWindowToken(), 0);
            });
            for (int i=0; i<10 && keyboardVisible(activity); i++) Thread.sleep(500);
            if (keyboardVisible(activity)) throw new AssertionError("Could not close emulator keyboard");
            eval(activity, web, "window.__permissionProbe='pending'; (async()=>{let m='';let g=''; try {let s=await navigator.mediaDevices.getUserMedia({audio:true});s.getTracks().forEach(t=>t.stop());m='ok';} catch(e){m=e.name+':'+e.message;} await new Promise(r=>navigator.geolocation.getCurrentPosition(p=>{g='ok';r();},e=>{g='error-'+e.code+':'+e.message;r();},{enableHighAccuracy:true,timeout:15000,maximumAge:0})); window.__permissionProbe='mic='+m+';geo='+g;})() ");
            String probe="";
            for(int i=0;i<30;i++) { Thread.sleep(1000); probe=eval(activity,web,"window.__permissionProbe"); if(!probe.contains("pending")) break; }
            result.putString("probe",probe);
            if (!probe.contains("mic=ok;geo=ok")) throw new AssertionError("Granted phone permissions did not reach WebView: " + probe);
            String peerReady = eval(activity, web, "typeof window.Peer");
            if (!peerReady.contains("function")) throw new AssertionError("Radio network library did not load: " + peerReady);
            eval(activity, web, "document.getElementById('name').value='Permission Test'; document.getElementById('room-name').value='Test Group'; document.getElementById('setup-form').requestSubmit();");
            String group="";
            for (int i=0; i<45; i++) {
                Thread.sleep(1000);
                group=eval(activity,web,"document.getElementById('connected').hidden ? 'waiting: '+document.getElementById('message').textContent : 'connected'");
                if ("\"connected\"".equals(group)) break;
            }
            result.putString("group",group);
            if (!"\"connected\"".equals(group)) throw new AssertionError("Could not start a group after permissions were granted: " + group);
            double height = Double.parseDouble(eval(activity, web, "innerHeight"));
            if (height >= 450) {
                assertVisible(activity, web, "\"check-update\",\"screen-label\",\"connected\",\"talk\"");
            } else {
                for (String id : new String[]{"check-update", "screen-label", "connected", "talk"}) {
                    eval(activity, web, "document.getElementById('" + id + "').scrollIntoView({block:'center'})");
                    Thread.sleep(150);
                    assertVisible(activity, web, "\"" + id + "\"");
                }
            }
            if (!"true".equals(eval(activity, web, "document.documentElement.scrollWidth <= innerWidth"))) throw new AssertionError("Horizontal overflow");
            if (RadioService.current() == null) throw new AssertionError("Foreground service missing after joining");
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            activity.runOnUiThread(activity::recreate);
            Activity recreated = waitForMonitorWithTimeout(monitor, 15000);
            removeMonitor(monitor);
            if (recreated == null) throw new AssertionError("Activity did not recreate");
            WebView retained = findWebView((ViewGroup) recreated.findViewById(android.R.id.content));
            if (retained != web) throw new AssertionError("Radio WebView was not retained across activity recreation");
            if (!"false".equals(eval(recreated, retained, "document.getElementById('connected').hidden"))) throw new AssertionError("Group did not survive activity recreation");
            String originalRoom = eval(recreated, retained, "document.getElementById('room-code').textContent");
            recreated.runOnUiThread(recreated::finish);
            for (int i=0; i<20 && !recreated.isDestroyed(); i++) Thread.sleep(250);
            if (!recreated.isDestroyed()) throw new AssertionError("Activity did not close");
            if (RadioService.current() == null) throw new AssertionError("Service stopped when activity closed");
            Activity restored = startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            WebView reopened = findWebView((ViewGroup) restored.findViewById(android.R.id.content));
            if (reopened != web || !originalRoom.equals(eval(restored, reopened, "document.getElementById('room-code').textContent")) || !"false".equals(eval(restored, reopened, "document.getElementById('connected').hidden"))) throw new AssertionError("Reopening the app did not restore the live group");
            eval(restored, reopened, "document.getElementById('leave').click()");
            for (int i=0; i<10 && RadioService.current()!=null; i++) Thread.sleep(500);
            if (RadioService.current()!=null) throw new AssertionError("Leaving did not stop the foreground service");
            result.putString("result", "PASS: microphone, location, activity recreation, reopening, and service cleanup");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
