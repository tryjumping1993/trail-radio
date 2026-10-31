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
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
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
            result.putString("result", "PASS: microphone, location, group startup, and visible controls");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
