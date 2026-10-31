(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  // Talk range in feet: the host picks it (up to a mile) and shares it with the group.
  const DEFAULT_RANGE_FT = 1500, MIN_RANGE_FT = 100, MAX_RANGE_FT = 5280, FRESH = 30000;
  let rangeFeet = DEFAULT_RANGE_FT;
  const cleanRange = v => { const n = Math.round(Number(v)); return Number.isFinite(n) ? Math.min(MAX_RANGE_FT, Math.max(MIN_RANGE_FT, n)) : DEFAULT_RANGE_FT; };
  const rangeLabel = ft => ft === 5280 ? '1 MILE' : ft === 2640 ? '½ MILE' : ft === 3960 ? '¾ MILE' : `${ft.toLocaleString('en-US')} FT`;
  let mode = new URL(location.href).searchParams.has('room') ? 'join' : 'host';
  let peer, hostConn, localStream, watchId, roomId, selfId, selfName, groupName, isHost = false;
  let roster = {}, connections = {}, calls = {}, floor = null, held = false, active = false, nativeSession = false, lastPosition = null, locationTimer, holdTimer;
  let wantsGroup=false, recovering=false, hostReady=false, peerRetryTimer=null, hostRetryTimer=null, linkTimer=null, initialTimer=null;
  let peerRetryAt=0, hostRetryAt=0, linkDeadline=0, initialDeadline=0, peerAttempts=0, hostAttempts=0;
  const msg = text => $('message').textContent = text || '';
  // Android can resize the WebView after a field receives focus when its keyboard opens.
  let focusScrollTimer;
  function keepFocusedFieldVisible() {
    clearTimeout(focusScrollTimer);
    focusScrollTimer=setTimeout(()=>{
      const field=document.activeElement;
      if (field && /^(INPUT|TEXTAREA|SELECT)$/.test(field.tagName)) field.scrollIntoView({block:'center',inline:'nearest'});
    },80);
  }
  document.addEventListener('focusin',keepFocusedFieldVisible);
  window.addEventListener('resize',keepFocusedFieldVisible);
  window.visualViewport?.addEventListener('resize',keepFocusedFieldVisible);
  const safeSend = (conn, data) => { if (conn && conn.open) { try { conn.send(data); } catch (_) {} } };
  const clean = text => String(text || '').trim().slice(0, 32);
  function distance(a,b) {
    if (!a || !b) return null;
    const rad = n => n * Math.PI / 180, dLat = rad(b.lat-a.lat), dLon = rad(b.lon-a.lon);
    const h = Math.sin(dLat/2)**2 + Math.cos(rad(a.lat))*Math.cos(rad(b.lat))*Math.sin(dLon/2)**2;
    return 6371000 * 2 * Math.atan2(Math.sqrt(h),Math.sqrt(1-h));
  }
  function current(p) { return p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && Date.now()-p.time < FRESH; }
  function inRange(a,b) { return current(a) && current(b) && distance(a,b) <= rangeFeet / 3.28084; }
  function position() { return current(lastPosition) ? lastPosition : null; }
  // The radio screen's waveform: the speaker the group hears (or your own microphone while you
  // talk), drawn from Web Audio analysers. A flat line while the channel is quiet.
  let audioCtx=null, localAnalyser=null, waveFrame=null;
  function startAudio() {
    try {
      const AC=window.AudioContext||window.webkitAudioContext; if(!AC) return;
      if(!audioCtx) audioCtx=new AC(); if(audioCtx.state==='suspended') audioCtx.resume().catch(()=>{});
    } catch (_) {}
    if(!waveFrame) drawWave();
  }
  function analyserFor(stream) {
    if(!audioCtx || !stream) return null;
    try {const node=audioCtx.createAnalyser(); node.fftSize=1024; audioCtx.createMediaStreamSource(stream).connect(node); return node;} catch (_) {return null;}
  }
  function sizeCanvas(canvas) {
    const ratio=window.devicePixelRatio||1, w=Math.round((canvas.clientWidth||0)*ratio), h=Math.round((canvas.clientHeight||0)*ratio);
    if(!w || !h) return null;
    if(canvas.width!==w || canvas.height!==h) {canvas.width=w; canvas.height=h;}
    const ctx=canvas.getContext?.('2d'); if(ctx) ctx.setTransform(ratio,0,0,ratio,0,0);
    return ctx ? {ctx,w:w/ratio,h:h/ratio} : null;
  }
  function audibleAnalyser() {
    if(!active || !floor) return null;
    if(floor===selfId) return held ? localAnalyser : null;
    const entry=calls[floor]; return entry && !entry.outgoing && entry.audio && !entry.audio.muted ? entry.analyser : null;
  }
  let waveData=null;
  function drawWave() {
    waveFrame=null;
    const raf=window.requestAnimationFrame; if(!raf) return;
    const canvas=$('wave'), area=canvas && sizeCanvas(canvas);
    if(area) {
      const {ctx,w,h}=area, analyser=audibleAnalyser(), mid=h/2;
      ctx.clearRect(0,0,w,h); ctx.strokeStyle='#385446'; ctx.lineWidth=2; ctx.lineJoin='round'; ctx.beginPath();
      if(analyser) {
        if(!waveData || waveData.length!==analyser.fftSize) waveData=new Uint8Array(analyser.fftSize);
        analyser.getByteTimeDomainData(waveData);
        const step=waveData.length/w;
        for(let x=0; x<=w; x++) {const v=(waveData[Math.min(waveData.length-1,Math.floor(x*step))]-128)/128; const y=mid-v*(mid-2)*1.6; x?ctx.lineTo(x,y):ctx.moveTo(x,y);}
      } else {ctx.moveTo(0,mid); ctx.lineTo(w,mid);}
      ctx.stroke();
    }
    // Hidden pages get no frames, so the loop pauses in the background by itself.
    if(active || audioCtx) waveFrame=raf(drawWave);
  }
  // The crew map: an overhead view centred on you, north up, with the talk range as a ring.
  function drawMap() {
    const canvas=$('crew-map'); if(!canvas || !$('crew-details').open) return;
    const area=sizeCanvas(canvas); if(!area) return;
    const {ctx,w,h}=area, cx=w/2, cy=h/2, ringR=Math.min(w,h)/2-18, me=position();
    ctx.clearRect(0,0,w,h); ctx.font="700 9px 'Space Grotesk',sans-serif"; ctx.textAlign='center'; ctx.textBaseline='middle';
    ctx.fillStyle='#82937d'; ctx.fillText('N',cx,8);
    ctx.strokeStyle='#b8c4aa'; ctx.lineWidth=1; ctx.setLineDash([4,4]); ctx.beginPath(); ctx.arc(cx,cy,ringR,0,Math.PI*2); ctx.stroke(); ctx.setLineDash([]);
    ctx.textAlign='left'; ctx.fillText(rangeLabel(rangeFeet),cx+ringR*0.71+4,cy+ringR*0.71+4); ctx.textAlign='center';
    if(!me) {ctx.fillStyle='#93a18f'; ctx.fillText('WAITING FOR YOUR LOCATION',cx,cy); return;}
    const scale=ringR/rangeFeet, edge=Math.min(w,h)/2-8, cosLat=Math.cos(me.lat*Math.PI/180);
    Object.entries(roster).forEach(([id,member])=>{
      if(id===selfId || !current(member.pos)) return;
      // Feet east and north of you (flat-earth is plenty at these distances).
      const east=(member.pos.lon-me.lon)*cosLat*111320*3.28084, north=(member.pos.lat-me.lat)*110540*3.28084;
      let x=east*scale, y=-north*scale; const r=Math.hypot(x,y), near=inRange(me,member.pos), out=r>edge;
      if(out) {x*=edge/r; y*=edge/r;}
      ctx.beginPath(); ctx.arc(cx+x,cy+y,9,0,Math.PI*2);
      ctx.fillStyle=out?'#eef2e1':near?(floor===id?'#e97d48':'#e9c779'):'#eab39a'; ctx.fill();
      ctx.strokeStyle=near?'#385446':'#c47b5e'; ctx.lineWidth=out?1.5:1; ctx.stroke();
      ctx.fillStyle='#263e34'; ctx.fillText((member.name||'?').charAt(0).toUpperCase(),cx+x,cy+y+0.5);
    });
    ctx.beginPath(); ctx.arc(cx,cy,6,0,Math.PI*2); ctx.fillStyle='#385446'; ctx.fill();
    ctx.beginPath(); ctx.arc(cx,cy,9,0,Math.PI*2); ctx.strokeStyle='#385446'; ctx.lineWidth=1.5; ctx.stroke();
  }
  function setMode(next) {
    mode=next; $('host-tab').classList.toggle('active',mode==='host'); $('join-tab').classList.toggle('active',mode==='join');
    $('host-tab').setAttribute('aria-selected',mode==='host'); $('join-tab').setAttribute('aria-selected',mode==='join');
    $('room-name-wrap').hidden=mode!=='host'; $('code-wrap').hidden=mode!=='join'; $('start').firstChild.textContent=mode==='host'?'START A GROUP ':'JOIN THE GROUP '; msg('');
  }
  $('host-tab').onclick=()=>setMode('host'); $('join-tab').onclick=()=>setMode('join');
  if (window.TrailRadioNative) { $('check-update').hidden=false; $('check-update').onclick=()=>window.TrailRadioNative.checkForUpdates(); }
  $('permission-settings').onclick=()=>window.TrailRadioNative?.openAppSettings();
  const urlRoom = new URL(location.href).searchParams.get('room'); if(urlRoom) $('code').value=urlRoom; setMode(mode);
  try {const saved=localStorage.getItem('trailRadioRange'); if(saved && [...$('range').options].some(o=>o.value===saved)) $('range').value=saved;} catch (_) {}
  function updateScreen() {
    if(!active) { $('screen-label').textContent='OFFLINE'; $('screen-main').textContent='READY?'; $('screen-sub').textContent='START OR JOIN A GROUP'; $('talk').disabled=true; return; }
    $('talk').disabled=recovering || !position() || (!!floor && floor!==selfId);
    $('screen-label').textContent=recovering?'RECONNECTING':position() ? (floor ? 'TRANSMITTING' : 'CHANNEL OPEN') : 'LOCATION NEEDED';
    $('screen-main').textContent=recovering?'HOLD ON':floor ? (floor===selfId?'YOU’RE ON!':(roster[floor]?.name||'SOMEONE').toUpperCase()) : (position()?'HELLO!':'NO GPS');
    $('screen-sub').textContent=recovering?'YOUR GROUP IS STILL OPEN':!position()?'WAITING FOR A FRESH LOCATION':floor?(floor===selfId?'RELEASE WHEN YOU’RE DONE':'CHANNEL IS IN USE'):'HOLD THE BUTTON TO TALK';
    $('talk').classList.toggle('transmitting',floor===selfId); $('talk').classList.toggle('held',held);
  }
  function render() {
    if(!active) return; drawMap();
    $('group-title').textContent=groupName; $('range-tag').textContent=`◉ ${rangeLabel(rangeFeet)} RANGE`; $('count').textContent=String(Object.keys(roster).length).padStart(2,'0'); $('roster').replaceChildren();
    Object.entries(roster).forEach(([id, member])=>{
      const row=document.createElement('div'); row.className='member';
      const avatar=document.createElement('div'); avatar.className='avatar'; avatar.textContent=(member.name||'?').charAt(0).toUpperCase();
      const info=document.createElement('div'); info.className='member-info'; const name=document.createElement('div'); name.className='member-name'; name.textContent=member.name+(id===selfId?' (you)':'');
      const detail=document.createElement('div'); detail.className='member-detail'; detail.textContent=member.host?'GROUP HOST':'WANDERER'; info.append(name,detail);
      const state=document.createElement('div'); state.className='member-state';
      if(id===selfId) state.textContent=position()?'● YOU ARE HERE':'LOCATION NEEDED';
      else if(!current(member.pos)||!position()) {state.textContent='LOCATION NEEDED';state.classList.add('far');}
      else {const feet=Math.round(distance(position(),member.pos)*3.28084); state.textContent=feet<=rangeFeet?`● ${feet.toLocaleString('en-US')} FT AWAY`:`○ OUT OF RANGE`; if(feet>rangeFeet)state.classList.add('far');}
      row.append(avatar,info,state); $('roster').append(row);
    });
    updateScreen(); enforceAudio();
  }
  function broadcast() {
    const data={type:'state',roster,floor,groupName,range:rangeFeet}; Object.values(connections).forEach(c=>safeSend(c,data)); render();
  }
  function removeMember(id, conn) {
    if(connections[id]!==conn) return;
    delete connections[id]; delete roster[id]; if(floor===id) floor=null; broadcast();
  }
  function onHostData(id,data) {
    if(!data || !roster[id]) return;
    if(data.type==='position') {roster[id].pos=data.pos; broadcast();}
    if(data.type==='request') {
      if(!recovering && !floor && current(roster[id].pos)) {floor=id; broadcast(); safeSend(connections[id],{type:'granted'});}
      else safeSend(connections[id],{type:'denied',reason:recovering?'network':'busy'});
    }
    if(data.type==='release' && floor===id) {floor=null; broadcast();}
  }
  function hostConnection(conn) {
    conn.on('open',()=>{ if(!wantsGroup) {conn.close(); return;} connections[conn.peer]=conn; safeSend(conn,{type:'welcome-request'}); });
    conn.on('data',data=>{
      if(connections[conn.peer]!==conn) return;
      if(data?.type==='hello') { roster[conn.peer]={name:clean(data.name)||'Wanderer',pos:null,host:false}; broadcast(); }
      else onHostData(conn.peer,data);
    });
    conn.on('close',()=>removeMember(conn.peer,conn)); conn.on('error',()=>removeMember(conn.peer,conn));
  }
  function handleClientData(data) {
    if(!data) return;
    if(data.type==='welcome-request') {safeSend(hostConn,{type:'hello',name:selfName}); sendPosition();}
    if(data.type==='state') {
      roster=data.roster||{}; floor=data.floor; groupName=data.groupName||'Your group'; rangeFeet=data.range==null?DEFAULT_RANGE_FT:cleanRange(data.range);
      if(roster[selfId]) {hostReady=true; clearTimeout(linkTimer); linkTimer=null; linkDeadline=0; if(!active) enter(); markRecovered();}
      render();
    }
    if(data.type==='granted') { if(held && !recovering) beginTransmit(); else safeSend(hostConn,{type:'release'}); }
    if(data.type==='denied') {held=false; msg(data.reason==='network'?'The channel is reconnecting. Please wait.':'Someone else has the channel. Try again when they finish.'); updateScreen();}
  }
  function clearRetryTimers() {
    clearTimeout(peerRetryTimer); clearTimeout(hostRetryTimer); clearTimeout(linkTimer); clearTimeout(initialTimer);
    peerRetryTimer=hostRetryTimer=linkTimer=initialTimer=null; peerRetryAt=hostRetryAt=linkDeadline=initialDeadline=0;
  }
  function notifyNetwork(ready) {
    if(nativeSession && typeof window.TrailRadioNative.radioNetworkStatus==='function') window.TrailRadioNative.radioNetworkStatus(ready);
  }
  function markRecovering() {
    if(!wantsGroup) return;
    if(!recovering) {recovering=true; if(active) release(); notifyNetwork(false);}
    if(active) {msg('Connection interrupted. Reconnecting to your group…'); updateScreen();}
    else msg('Connecting to the radio network…');
  }
  function markRecovered() {
    if(!wantsGroup || !peer || peer.destroyed || peer.disconnected || !peer.open || (!isHost && !hostReady)) return;
    recovering=false; peerAttempts=0; hostAttempts=0;
    clearTimeout(peerRetryTimer); peerRetryTimer=null; peerRetryAt=0;
    clearTimeout(hostRetryTimer); hostRetryTimer=null; hostRetryAt=0;
    notifyNetwork(true);
    if(active) {msg(''); updateScreen();}
  }
  function initialConnectionExpired() {
    if(!wantsGroup || active || !initialDeadline || Date.now()<initialDeadline) return;
    cleanup(); msg(isHost?'Could not connect to the radio network. Check your connection and try again.':'Could not reach that group. Check the code and make sure the host is online.');
  }
  function schedulePeerRetry(immediate=false) {
    if(!wantsGroup || peerRetryTimer) return;
    const delay=immediate?0:Math.min(30000,1000 * 2 ** Math.min(peerAttempts++,5));
    peerRetryAt=Date.now()+delay;
    peerRetryTimer=setTimeout(retryPeer,delay);
  }
  function scheduleHostRetry(immediate=false) {
    if(!wantsGroup || isHost || hostReady || hostRetryTimer || hostConn) return;
    const delay=immediate?0:Math.min(30000,1000 * 2 ** Math.min(hostAttempts++,5));
    hostRetryAt=Date.now()+delay;
    hostRetryTimer=setTimeout(retryHost,delay);
  }
  function closeCalls() {
    Object.values(calls).forEach(entry=>{try {entry.call.close();} catch (_) {}}); calls={};
  }
  function retryPeer() {
    clearTimeout(peerRetryTimer); peerRetryTimer=null; peerRetryAt=0;
    if(!wantsGroup) return;
    if(!peer || peer.destroyed) {
      // A destroyed Peer cannot reconnect. Recreate it with the same host code.
      peer=null; hostConn=null; hostReady=false; connections={}; roster={}; floor=null; selfId=null; closeCalls();
      try {createPeer();} catch (_) {schedulePeerRetry();}
    } else if(peer.disconnected) {
      try {peer.reconnect();} catch (_) { /* retry after PeerJS finishes closing */ }
      schedulePeerRetry();
    } else if(!peer.open) schedulePeerRetry();
    else {markRecovered(); if(!isHost && !hostReady) scheduleHostRetry(true);}
  }
  function retryHost() {
    clearTimeout(hostRetryTimer); hostRetryTimer=null; hostRetryAt=0;
    if(!wantsGroup || isHost || hostReady || hostConn) return;
    if(!peer || peer.destroyed || peer.disconnected || !peer.open) {schedulePeerRetry(); return;}
    let conn;
    try {conn=peer.connect(roomId,{reliable:true});} catch (_) {scheduleHostRetry(); return;}
    if(!conn) {scheduleHostRetry(); return;}
    hostConn=conn;
    conn.on('data',data=>{if(hostConn===conn) handleClientData(data);});
    conn.on('close',()=>hostLinkLost(conn)); conn.on('error',()=>hostLinkLost(conn));
    linkDeadline=Date.now()+12000;
    linkTimer=setTimeout(expireHostLink,12000);
  }
  function expireHostLink() {
    if(!linkDeadline || Date.now()<linkDeadline || !hostConn || hostReady) return;
    const conn=hostConn; try {conn.close();} catch (_) {} hostLinkLost(conn);
  }
  function hostLinkLost(conn) {
    if(hostConn!==conn) return;
    hostConn=null; hostReady=false; clearTimeout(linkTimer); linkTimer=null; linkDeadline=0;
    if(active) {markRecovering(); floor=null; closeCalls(); roster=selfId?{[selfId]:{name:selfName,pos:position(),host:false}}:{}; render();}
    scheduleHostRetry();
  }
  function createPeer() {
    const next=isHost?new Peer(roomId):new Peer(); peer=next;
    next.on('open',id=>{
      if(peer!==next || !wantsGroup) return;
      selfId=id;
      if(watchId==null) {
        watchId=navigator.geolocation.watchPosition(p=>{lastPosition={lat:p.coords.latitude,lon:p.coords.longitude,time:Date.now()}; sendPosition();},()=>{if(nativeSession && document.hidden) return; lastPosition=null; release(); sendPosition(); msg('Location unavailable. Enable location to use the radio.');},{enableHighAccuracy:true,maximumAge:5000,timeout:20000});
        locationTimer=setInterval(()=>{if(!position()) release(); sendPosition(); refreshLocation();},10000);
      }
      if(isHost) {
        if(!roster[selfId]) roster[selfId]={name:selfName,pos:position(),host:true};
        if(!active) enter(); else broadcast();
        markRecovered();
      } else if(hostReady && hostConn?.open) markRecovered(); else scheduleHostRetry(true);
    });
    next.on('connection',conn=>{if(peer===next && wantsGroup && isHost) hostConnection(conn); else conn.close();});
    next.on('call',call=>{if(peer===next && wantsGroup) incoming(call); else call.close();});
    next.on('disconnected',()=>{if(peer!==next || !wantsGroup) return; markRecovering(); schedulePeerRetry();});
    next.on('close',()=>{if(peer!==next || !wantsGroup) return; markRecovering(); schedulePeerRetry();});
    next.on('error',err=>{
      if(peer!==next || !wantsGroup) return;
      if(['browser-incompatible','invalid-id','invalid-key','ssl-unavailable'].includes(err.type)) {cleanup(); msg('This device cannot connect to the radio network.'); return;}
      if(err.type==='peer-unavailable') {
        if(!isHost && !hostReady && hostConn) {const conn=hostConn; try {conn.close();} catch (_) {} hostLinkLost(conn);}
        else if(active && !recovering) msg('Could not reach one group member.');
        return;
      }
      if(err.type==='webrtc') {if(active && !recovering) msg('A radio connection was interrupted.'); return;}
      markRecovering(); schedulePeerRetry();
    });
  }
  window.radioNetworkAvailable=()=>{if(!wantsGroup) return; if(!peer || peer.destroyed || peer.disconnected || !peer.open) {clearTimeout(peerRetryTimer); peerRetryTimer=null; peerRetryAt=0; schedulePeerRetry(true);} else if(!isHost && !hostReady && !hostConn) {clearTimeout(hostRetryTimer); hostRetryTimer=null; hostRetryAt=0; scheduleHostRetry(true);}};
  window.radioBackgroundTick=()=>{
    if(!wantsGroup) return;
    if(initialDeadline && !active && Date.now()>=initialDeadline) {initialConnectionExpired(); return;}
    // PeerJS 1.5.5's public socket sends this same heartbeat; native ticks are not throttled like hidden-page timers.
    try {if(peer?.open && !peer.disconnected && peer.socket?.send) peer.socket.send({type:'HEARTBEAT'});} catch (_) {}
    if(linkDeadline && Date.now()>=linkDeadline) expireHostLink();
    if(peerRetryTimer && Date.now()>=peerRetryAt) retryPeer();
    if(hostRetryTimer && Date.now()>=hostRetryAt) retryHost();
  };
  window.addEventListener('online',window.radioNetworkAvailable);
  function sendPosition() {
    if(!selfId) return;
    if(isHost) { if(roster[selfId]) {roster[selfId].pos=position(); broadcast();} }
    else safeSend(hostConn,{type:'position',pos:position()});
    render();
  }
  function refreshLocation() {
    navigator.geolocation.getCurrentPosition(p=>{ lastPosition={lat:p.coords.latitude,lon:p.coords.longitude,time:Date.now()}; sendPosition(); },()=>{}, {enableHighAccuracy:true,maximumAge:5000,timeout:15000});
  }
  function enforceAudio() {
    Object.entries(calls).forEach(([id,entry])=>{
      if(entry.audio) entry.audio.muted=!(floor===id && inRange(position(),roster[id]?.pos));
      if(entry.outgoing && (!held || floor!==selfId || !inRange(position(),roster[id]?.pos))) {entry.call.close(); delete calls[id];}
    });
  }
  function incoming(call) {
    const id=call.peer;
    if(!active || floor!==id || !inRange(position(),roster[id]?.pos)) {call.close(); return;}
    call.answer(); const audio=document.createElement('audio'); audio.autoplay=true; audio.playsInline=true;
    calls[id]={call,audio,outgoing:false};
    call.on('stream',stream=>{audio.srcObject=stream; if(calls[id]?.call===call) calls[id].analyser=analyserFor(stream); enforceAudio(); audio.play().catch(()=>msg('Tap anywhere on the page to enable incoming audio.'));});
    call.on('close',()=>{audio.remove(); if(calls[id]?.call===call) delete calls[id];});
    call.on('error',()=>{audio.remove(); if(calls[id]?.call===call) delete calls[id];});
  }
  function beginTransmit() {
    if(!held || !position()) {release(); return;}
    floor=selfId; localStream.getAudioTracks().forEach(t=>t.enabled=true); render();
    // Let the host's floor announcement reach listeners before the media call.
    holdTimer=setTimeout(()=>{
      if(!held || floor!==selfId) return;
      Object.keys(roster).filter(id=>id!==selfId && inRange(position(),roster[id].pos)).forEach(id=>{
        const call=peer.call(id,localStream); if(!call) return;
        calls[id]={call,outgoing:true}; call.on('close',()=>{if(calls[id]?.call===call) delete calls[id];}); call.on('error',()=>{if(calls[id]?.call===call) delete calls[id];});
      });
    },250);
  }
  function requestTalk(e) {
    if(e) e.preventDefault(); if(!active || recovering || held || !position() || floor) return;
    held=true; msg(''); startAudio(); updateScreen();
    if(isHost) {floor=selfId; broadcast(); beginTransmit();} else safeSend(hostConn,{type:'request'});
  }
  function release() {
    if(!held && floor!==selfId) return; held=false; clearTimeout(holdTimer);
    localStream?.getAudioTracks().forEach(t=>t.enabled=false);
    Object.entries(calls).forEach(([id,entry])=>{if(entry.outgoing){entry.call.close();delete calls[id];}});
    if(isHost && floor===selfId) {floor=null; broadcast();} else if(!isHost) safeSend(hostConn,{type:'release'});
    if(floor===selfId) floor=null; render();
  }
  $('talk').addEventListener('pointerdown',e=>{ $('talk').setPointerCapture(e.pointerId); requestTalk(e); });
  ['pointerup','pointercancel','lostpointercapture'].forEach(event=>$('talk').addEventListener(event,release));
  $('talk').addEventListener('keydown',e=>{if((e.key===' '||e.key==='Enter')&&!e.repeat) requestTalk(e);});
  $('talk').addEventListener('keyup',e=>{if(e.key===' '||e.key==='Enter') release();});
  // A long press would otherwise open a context menu (and can drop the button's pressed look).
  $('talk').addEventListener('contextmenu',e=>e.preventDefault());
  $('crew-details').addEventListener('toggle',()=>drawMap());
  window.addEventListener('blur',release); document.addEventListener('visibilitychange',()=>{if(document.hidden) release();});
  function enter() {
    active=true; clearTimeout(initialTimer); initialTimer=null; initialDeadline=0; if (nativeSession) window.TrailRadioNative.radioConnected(groupName); document.body.classList.add('is-connected'); $('setup').hidden=true; $('connected').hidden=false; $('crew-details').hidden=false; $('room-code').textContent=roomId; $('connection-status').textContent='ON THE AIR'; msg(''); render(); window.scrollTo?.(0,0);
  }
  function cleanup() {
    wantsGroup=false; recovering=false; hostReady=false; clearRetryTimers();
    release(); active=false; if(nativeSession) { nativeSession=false; window.TrailRadioNative.stopRadioSession(); } document.body.classList.remove('is-connected'); clearInterval(locationTimer); if(watchId!=null) navigator.geolocation.clearWatch(watchId); watchId=null;
    Object.values(calls).forEach(x=>x.call.close()); calls={}; localStream?.getTracks().forEach(t=>t.stop()); localStream=null; localAnalyser=null;
    const oldPeer=peer; peer=null; hostConn=null; connections={}; roster={}; floor=null; lastPosition=null; selfId=null; oldPeer?.destroy();
    $('setup').hidden=false; $('connected').hidden=true; $('crew-details').hidden=true; $('crew-details').open=false; $('start').disabled=false; updateScreen();
  }
  $('leave').onclick=()=>{cleanup(); msg('You left the group.');};
  $('copy').onclick=async()=>{
    const invite=new URL(location.hostname==='trailradio.local'?'trailradio://join':location.href); invite.search=''; invite.searchParams.set('room',roomId);
    const text=`Join my Big Walk Radio group “${groupName}”\n${invite.href}\nGroup code: ${roomId}`;
    try {if(window.TrailRadioNative) { window.TrailRadioNative.shareInvite(text); } else if(navigator.share) await navigator.share({title:'Join my Big Walk Radio group',text}); else {await navigator.clipboard.writeText(text); msg('Invitation copied. Send it to your crew!');}} catch(e) {if(e.name!=='AbortError') {try{await navigator.clipboard.writeText(text);msg('Invitation copied. Send it to your crew!');}catch(_){msg('Copy this group code and send it to your crew: '+roomId);}}}
  };
  function requestPhonePermissions() {
    if (!window.TrailRadioNative) return Promise.resolve();
    return new Promise((resolve,reject)=>{
      window.trailRadioPermissionResult=granted=>{
        delete window.trailRadioPermissionResult;
        if(granted) resolve(); else {const error=new Error('Phone permissions denied'); error.name='PhonePermissionDenied'; reject(error);}
      };
      try {window.TrailRadioNative.requestPhonePermissions();} catch(error) {delete window.trailRadioPermissionResult; reject(error);}
    });
  }
  function startNativeSession() {
    if (!window.TrailRadioNative) return Promise.resolve();
    return new Promise((resolve,reject)=>{
      window.radioServiceStarted=ok=>{
        delete window.radioServiceStarted;
        if(ok) {nativeSession=true; resolve();}
        else {const error=new Error('Background radio could not start'); error.name='RadioServiceError'; reject(error);}
      };
      try {window.TrailRadioNative.startRadioSession();} catch(error) {delete window.radioServiceStarted; reject(error);}
    });
  }
  window.radioNativeLocation=(lat,lon,time)=>{
    if (!nativeSession || !Number.isFinite(lat) || !Number.isFinite(lon) || !Number.isFinite(time) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return;
    if (!lastPosition || time >= lastPosition.time) {lastPosition={lat,lon,time}; sendPosition();}
  };
  window.radioServiceStopped=()=>{cleanup(); msg('The background radio stopped. Start or join the group again.');};
  function getLocation() {return new Promise((resolve,reject)=>navigator.geolocation.getCurrentPosition(resolve,reject,{enableHighAccuracy:true,timeout:20000,maximumAge:10000}));}
  $('setup-form').onsubmit=async e=>{
    e.preventDefault(); if(!window.Peer){msg('The radio network could not load. Check your internet connection and try again.');return;}
    if(!navigator.geolocation || !navigator.mediaDevices?.getUserMedia){msg(window.TrailRadioNative?'Your phone needs microphone and location support to use the radio.':'This browser needs HTTPS, microphone and location support to use the radio.');return;}
    selfName=clean($('name').value); if(!selfName)return; isHost=mode==='host';
    roomId=isHost?'tr-'+Array.from(crypto.getRandomValues(new Uint8Array(8)),x=>x.toString(16).padStart(2,'0')).join(''):clean($('code').value).toLowerCase();
    if(!/^tr-[a-f0-9]{16}$/.test(roomId)){msg('That group code doesn’t look right. Ask your host for a fresh invitation.');return;}
    startAudio(); groupName=isHost?(clean($('room-name').value)||'The Wanderers'):'Your group'; rangeFeet=isHost?cleanRange($('range').value):DEFAULT_RANGE_FT; if(isHost) {try {localStorage.setItem('trailRadioRange',String(rangeFeet));} catch (_) {}} $('start').disabled=true; $('permission-settings').hidden=true; msg('Getting your microphone and location ready…');
    let stage='permissions';
    try {
      await requestPhonePermissions();
      stage='background';
      await startNativeSession();
      stage='microphone';
      localStream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false}); localStream.getAudioTracks().forEach(t=>t.enabled=false); localAnalyser=analyserFor(localStream);
      stage='location';
      const loc=await getLocation(); lastPosition={lat:loc.coords.latitude,lon:loc.coords.longitude,time:Date.now()};
      stage='network';
      wantsGroup=true; recovering=false; hostReady=false; peerAttempts=0; hostAttempts=0;
      initialDeadline=Date.now()+30000;
      initialTimer=setTimeout(initialConnectionExpired,30000);
      createPeer();
    } catch(err) {
      cleanup();
      const denied=err.code===1||err.name==='NotAllowedError'||err.name==='PhonePermissionDenied';
      if (window.TrailRadioNative && denied && stage!=='network') $('permission-settings').hidden=false;
      if (err.name==='RadioServiceError') msg('Could not keep the radio running. Check your microphone and location settings, then try again.');
      else if (err.name==='PhonePermissionDenied') msg('Allow microphone and location for Big Walk Radio in your phone settings, then try again.');
      else if (stage==='microphone') msg(denied?'Microphone access was blocked. Check the app’s microphone permission and try again.':'Could not start the microphone. Check that another app is not using it, then try again.');
      else if (stage==='location') msg(denied?'Location access was blocked. Check the app’s location permission and try again.':'Could not get your location. Turn on location services and try again, preferably outdoors.');
      else msg(denied?'Allow microphone and location access in your browser settings, then try again.':'Could not start the radio. Check your connection and try again.');
    }
  };
  window.addEventListener('pagehide',()=>{release(); if(!window.TrailRadioNative) peer?.destroy();});
})();
