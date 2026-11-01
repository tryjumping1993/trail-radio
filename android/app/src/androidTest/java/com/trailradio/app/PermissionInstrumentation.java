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
    private int idleSeconds() { try { return Math.max(0, Integer.parseInt(arguments.getString("idle", "0"))); } catch (RuntimeException e) { return 0; } }
    private long touchDownTime;
    private void touchTalk(Activity activity, WebView web, boolean down) throws Exception {
        if (down) {
            getTargetContext().startActivity(new Intent().setClassName(getTargetContext(), MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            Thread.sleep(1000);
            eval(activity, web, "document.getElementById('talk').scrollIntoView({block:'center'})");
        }
        org.json.JSONArray point = new org.json.JSONArray(eval(activity, web,
            "(()=>{const r=document.getElementById('talk').getBoundingClientRect();return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height/2)*devicePixelRatio];})()"));
        activity.runOnUiThread(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (down) touchDownTime = now;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(touchDownTime, now,
                down ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_UP,
                (float) point.optDouble(0), (float) point.optDouble(1), 0);
            web.dispatchTouchEvent(event); event.recycle();
        });
        Thread.sleep(250);
    }
    /** Deterministic tone through real WebRTC; a playing event alone can also mean silence. */
    private void audioProbe(Activity activity, WebView web, String role, Bundle result) throws Exception {
        boolean host = "audio-host".equals(role);
        String room = arguments.getString("room", "");
        int delay = Integer.parseInt(arguments.getString("stateDelay", "0"));
        eval(activity, web, "window.__probeAudio=[];window.__probeCalls=[];window.__probeContext=new AudioContext();"
            + "const create=document.createElement.bind(document);document.createElement=(tag,...args)=>{const e=create(tag,...args);if(tag==='audio')window.__probeAudio.push(e);return e;};"
            + "navigator.mediaDevices.getUserMedia=async()=>{const c=window.__probeContext;c.resume();const dest=c.createMediaStreamDestination(),o=c.createOscillator(),g=c.createGain();o.frequency.value=440;g.gain.value=0.2;o.connect(g).connect(dest);o.start();return dest.stream;};"
            + "const P=window.Peer;window.Peer=class extends P{constructor(...a){super(...a);window.__probePeer=this;}emit(event,...args){if(event==='call')window.__probeCalls.push(args[0]);if(event==='connection')this.track(args[0]);return super.emit(event,...args);}connect(...a){const c=super.connect(...a);this.track(c);return c;}track(c){const emit=c.emit.bind(c);c.emit=(event,data,...a)=>{if(event==='data'&&" + delay + ">0){setTimeout(()=>emit(event,data,...a)," + delay + ");return true;}return emit(event,data,...a);};}};"
            + "window.__probeHeard=()=>window.__probeAudio.some(a=>{if(a.muted||a.paused||!a.srcObject)return false;if(!a.__meter){a.__meter=window.__probeContext.createAnalyser();window.__probeContext.createMediaStreamSource(a.srcObject).connect(a.__meter);}const d=new Float32Array(a.__meter.fftSize);a.__meter.getFloatTimeDomainData(d);return d.some(v=>Math.abs(v)>0.01);});");
        if (!host) eval(activity, web, "document.getElementById('join-tab').click();document.getElementById('code').value='" + room + "';");
        eval(activity, web, "document.getElementById('range').value='26400';document.getElementById('name').value='" + role + "';document.getElementById('room-name').value='Audio Probe';document.getElementById('setup-form').requestSubmit();");
        waitFor(activity, web, "document.getElementById('connected').hidden", "false", 45);
        if (host) {
            room = new org.json.JSONArray("[" + eval(activity, web, "document.getElementById('room-code').textContent") + "]").getString(0);
            Bundle ready = new Bundle(); ready.putString("phase", "audio-host-ready"); ready.putString("room", room); sendStatus(1, ready);
            waitFor(activity, web, "document.getElementById('count').textContent", "\"02\"", 45);
            Thread.sleep(5000);
            touchTalk(activity, web, true);
            waitFor(activity, web, "document.getElementById('screen-main').textContent", "\"YOU’RE ON!\"", 10);
            Thread.sleep(12000);
            touchTalk(activity, web, false);
            waitFor(activity, web, "window.__probeHeard()", "true", 30);
            Thread.sleep(4000);
        } else {
            Bundle ready = new Bundle(); ready.putString("phase", "audio-client-ready"); sendStatus(1, ready);
            waitFor(activity, web, "window.__probeHeard()", "true", 25);
            waitFor(activity, web, "document.getElementById('screen-label').textContent", "\"CHANNEL OPEN\"", 25);
            touchTalk(activity, web, true);
            waitFor(activity, web, "document.getElementById('screen-main').textContent", "\"YOU’RE ON!\"", 10);
            Thread.sleep(10000);
            touchTalk(activity, web, false);
        }
        result.putString("result", "PASS: bidirectional non-silent WebRTC audio at five-mile range setting; delayed state=" + delay + "ms");
        eval(activity, web, "document.getElementById('leave').click()");
    }
    private void backgroundPeerTest(Activity activity, WebView web, String role, Bundle result) throws Exception {
        String room = arguments.getString("room", "");
        boolean hostRole = role.startsWith("host"), clientRole = role.startsWith("client"), injectDisconnect = role.endsWith("reconnect");
        if (clientRole && !room.matches("tr-[a-f0-9]{16}")) throw new AssertionError("Missing host room");
        if (injectDisconnect) eval(activity, web, "window.__heartbeatCount=0;window.__socketCount=0;const NativeWS=window.WebSocket;window.WebSocket=function(...a){let w=new NativeWS(...a);if(String(a[0]).includes('peerjs?')){window.__signalSocket=w;window.__socketCount++;let send=w.send.bind(w);w.send=d=>{if(String(d).includes('HEARTBEAT'))window.__heartbeatCount++;return send(d);};}return w;};window.WebSocket.prototype=NativeWS.prototype;Object.setPrototypeOf(window.WebSocket,NativeWS);");
        if (clientRole) {
            eval(activity, web, "document.getElementById('join-tab').click();document.getElementById('code').value='" + room + "';");
        }
        eval(activity, web, "document.getElementById('name').value='" + role + "';document.getElementById('room-name').value='Background Test';document.getElementById('setup-form').requestSubmit();");
        waitFor(activity, web, "document.getElementById('connected').hidden ? 'waiting' : 'connected'", "\"connected\"", 45);
        if (RadioService.current() == null) throw new AssertionError("Foreground radio service not running");
        if (hostRole) {
            room = new org.json.JSONArray("[" + eval(activity, web, "document.getElementById('room-code').textContent") + "]").getString(0);
            eval(activity, web, "window.__audioPlays=0; const oldCreate=document.createElement.bind(document);document.createElement=(tag,...args)=>{const el=oldCreate(tag,...args);if(String(tag).toLowerCase()==='audio')el.addEventListener('playing',()=>window.__audioPlays++);return el;};");
            CountDownLatch moved = new CountDownLatch(1);
            activity.runOnUiThread(() -> { activity.moveTaskToBack(true); moved.countDown(); });
            if (!moved.await(10, TimeUnit.SECONDS)) throw new AssertionError("Could not background activity");
            if ("host-detached".equals(role) || "host-reconnect".equals(role)) {
                activity.runOnUiThread(activity::finish);
                for (int i=0; i<20 && !activity.isDestroyed(); i++) Thread.sleep(250);
                if (!activity.isDestroyed() || RadioService.current() == null) throw new AssertionError("Could not detach the host activity while retaining service");
            }
            Bundle ready = new Bundle(); ready.putString("phase", "host-ready"); ready.putString("room", room); sendStatus(1, ready);
            if (injectDisconnect) {
                Thread.sleep(22000);
                if (!"true".equals(eval(activity, web, "!!window.__signalSocket"))) throw new AssertionError("Host signaling socket was not captured");
                eval(activity, web, "window.__signalSocket.close()");
                Thread.sleep(13000);
                waitFor(activity, web, "window.__socketCount >= 2", "true", 20);
                if (!"true".equals(eval(activity, web, "window.__heartbeatCount >= 2"))) throw new AssertionError("Background signaling heartbeats were not sent");
            } else Thread.sleep(35000);
            android.os.PowerManager power = (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (power.isInteractive()) throw new AssertionError("Screen was not turned off for background test");
            if (RadioService.current() == null) throw new AssertionError("Foreground service stopped while screen was off");
            Bundle listening = new Bundle(); listening.putString("phase", "host-listening"); listening.putString("room", room); sendStatus(1, listening);
            waitFor(activity, web, "window.__audioPlays > 0", "true", 45 + idleSeconds());
            if (!"false".equals(eval(activity, web, "document.getElementById('connected').hidden"))) throw new AssertionError("Host left the group while backgrounded");
            result.putString("result", "PASS: screen-off host stayed in the group and played incoming audio");
        } else {
            Bundle ready = new Bundle(); ready.putString("phase", "client-ready"); sendStatus(1, ready);
            if (injectDisconnect) {
                Thread.sleep(20000);
                if (!"true".equals(eval(activity, web, "!!window.__signalSocket"))) throw new AssertionError("Client signaling socket was not captured");
                eval(activity, web, "window.__signalSocket.close()");
                Thread.sleep(20000);
                waitFor(activity, web, "window.__socketCount >= 2", "true", 20);
            } else Thread.sleep(40000);
            // A long quiet spell before talking (the "idle" argument, in seconds).
            Thread.sleep(idleSeconds() * 1000L);
            android.os.PowerManager power = (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (power.isInteractive()) throw new AssertionError("Screen was not turned off for client test");
            waitFor(activity, web, "document.getElementById('talk').disabled", "false", 25);
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
            if (role.startsWith("audio-")) { audioProbe(activity, web, role, result); finish(Activity.RESULT_OK,result); return; }
            if (role.startsWith("host") || role.startsWith("client")) { backgroundPeerTest(activity, web, role, result); finish(Activity.RESULT_OK,result); return; }
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
