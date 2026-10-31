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
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent);
            WebView web = (WebView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            String state = "";
            for (int i=0; i<45; i++) { state = eval(activity, web, "document.readyState"); if (state.contains("complete")) break; Thread.sleep(1000); }
            if (!state.contains("complete")) throw new AssertionError("WebView page did not finish loading: " + state);
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
            result.putString("result", "PASS: microphone, location, and group startup");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
