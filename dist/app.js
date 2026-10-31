(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const RANGE = 457.2, FRESH = 30000;
  let mode = new URL(location.href).searchParams.has('room') ? 'join' : 'host';
  let peer, hostConn, localStream, watchId, roomId, selfId, selfName, groupName, isHost = false;
  let roster = {}, connections = {}, calls = {}, floor = null, held = false, active = false, lastPosition = null, locationTimer, holdTimer;
  const msg = text => $('message').textContent = text || '';
  const safeSend = (conn, data) => { if (conn && conn.open) { try { conn.send(data); } catch (_) {} } };
  const clean = text => String(text || '').trim().slice(0, 32);
  function distance(a,b) {
    if (!a || !b) return null;
    const rad = n => n * Math.PI / 180, dLat = rad(b.lat-a.lat), dLon = rad(b.lon-a.lon);
    const h = Math.sin(dLat/2)**2 + Math.cos(rad(a.lat))*Math.cos(rad(b.lat))*Math.sin(dLon/2)**2;
    return 6371000 * 2 * Math.atan2(Math.sqrt(h),Math.sqrt(1-h));
  }
  function current(p) { return p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && Date.now()-p.time < FRESH; }
  function inRange(a,b) { return current(a) && current(b) && distance(a,b) <= RANGE; }
  function position() { return current(lastPosition) ? lastPosition : null; }
  function setMode(next) {
    mode=next; $('host-tab').classList.toggle('active',mode==='host'); $('join-tab').classList.toggle('active',mode==='join');
    $('host-tab').setAttribute('aria-selected',mode==='host'); $('join-tab').setAttribute('aria-selected',mode==='join');
    $('room-name-wrap').hidden=mode!=='host'; $('code-wrap').hidden=mode!=='join'; $('start').firstChild.textContent=mode==='host'?'START A GROUP ':'JOIN THE GROUP '; msg('');
  }
  $('host-tab').onclick=()=>setMode('host'); $('join-tab').onclick=()=>setMode('join');
  if (window.TrailRadioNative) { $('check-update').hidden=false; $('check-update').onclick=()=>window.TrailRadioNative.checkForUpdates(); }
  const urlRoom = new URL(location.href).searchParams.get('room'); if(urlRoom) $('code').value=urlRoom; setMode(mode);
  function updateScreen() {
    if(!active) { $('screen-label').textContent='OFFLINE'; $('screen-main').textContent='READY?'; $('screen-sub').textContent='YOUR NEXT ADVENTURE AWAITS'; $('talk').disabled=true; return; }
    $('talk').disabled=!position() || (!!floor && floor!==selfId);
    $('screen-label').textContent=position() ? (floor ? 'TRANSMITTING' : 'CHANNEL OPEN') : 'LOCATION NEEDED';
    $('screen-main').textContent=floor ? (floor===selfId?'YOU’RE ON!':(roster[floor]?.name||'SOMEONE').toUpperCase()) : (position()?'HELLO!':'NO GPS');
    $('screen-sub').textContent=!position()?'WAITING FOR A FRESH LOCATION':floor?(floor===selfId?'RELEASE WHEN YOU’RE DONE':'CHANNEL IS IN USE'):'HOLD THE BUTTON TO TALK';
    $('talk').classList.toggle('transmitting',floor===selfId);
  }
  function render() {
    if(!active) return;
    $('group-title').textContent=groupName; $('count').textContent=String(Object.keys(roster).length).padStart(2,'0'); $('roster').replaceChildren();
    Object.entries(roster).forEach(([id, member])=>{
      const row=document.createElement('div'); row.className='member';
      const avatar=document.createElement('div'); avatar.className='avatar'; avatar.textContent=(member.name||'?').charAt(0).toUpperCase();
      const info=document.createElement('div'); info.className='member-info'; const name=document.createElement('div'); name.className='member-name'; name.textContent=member.name+(id===selfId?' (you)':'');
      const detail=document.createElement('div'); detail.className='member-detail'; detail.textContent=member.host?'GROUP HOST':'WANDERER'; info.append(name,detail);
      const state=document.createElement('div'); state.className='member-state';
      if(id===selfId) state.textContent=position()?'● YOU ARE HERE':'LOCATION NEEDED';
      else if(!current(member.pos)||!position()) {state.textContent='LOCATION NEEDED';state.classList.add('far');}
      else {const feet=Math.round(distance(position(),member.pos)*3.28084); state.textContent=feet<=1500?`● ${feet} FT AWAY`:`○ OUT OF RANGE`; if(feet>1500)state.classList.add('far');}
      row.append(avatar,info,state); $('roster').append(row);
    });
    updateScreen(); enforceAudio();
  }
  function broadcast() {
    const data={type:'state',roster,floor,groupName}; Object.values(connections).forEach(c=>safeSend(c,data)); render();
  }
  function removeMember(id) {
    if(!roster[id]) return; delete roster[id]; delete connections[id]; if(floor===id) floor=null; broadcast();
  }
  function onHostData(id,data) {
    if(!data || !roster[id]) return;
    if(data.type==='position') {roster[id].pos=data.pos; broadcast();}
    if(data.type==='request') {
      if(!floor && current(roster[id].pos)) {floor=id; broadcast(); safeSend(connections[id],{type:'granted'});}
      else safeSend(connections[id],{type:'denied'});
    }
    if(data.type==='release' && floor===id) {floor=null; broadcast();}
  }
  function hostConnection(conn) {
    conn.on('open',()=>{ connections[conn.peer]=conn; safeSend(conn,{type:'welcome-request'}); });
    conn.on('data',data=>{
      if(data?.type==='hello') { roster[conn.peer]={name:clean(data.name)||'Wanderer',pos:null,host:false}; broadcast(); }
      else onHostData(conn.peer,data);
    });
    conn.on('close',()=>removeMember(conn.peer)); conn.on('error',()=>removeMember(conn.peer));
  }
  function handleClientData(data) {
    if(!data) return;
    if(data.type==='welcome-request') {safeSend(hostConn,{type:'hello',name:selfName}); sendPosition();}
    if(data.type==='state') { roster=data.roster||{}; floor=data.floor; groupName=data.groupName||'Your group'; if(!active && roster[selfId]) enter(); render(); }
    if(data.type==='granted') { if(held) beginTransmit(); else safeSend(hostConn,{type:'release'}); }
    if(data.type==='denied') {held=false; msg('Someone else has the channel. Try again when they finish.'); updateScreen();}
  }
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
    call.on('stream',stream=>{audio.srcObject=stream; enforceAudio(); audio.play().catch(()=>msg('Tap anywhere on the page to enable incoming audio.'));});
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
    if(e) e.preventDefault(); if(!active || held || !position() || floor) return;
    held=true; msg('');
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
  window.addEventListener('blur',release); document.addEventListener('visibilitychange',()=>{if(document.hidden) release();});
  function enter() {
    active=true; $('setup').hidden=true; $('connected').hidden=false; $('room-code').textContent=roomId; $('connection-status').textContent='ON THE AIR'; msg(''); render();
  }
  function cleanup() {
    release(); active=false; clearInterval(locationTimer); if(watchId!=null) navigator.geolocation.clearWatch(watchId); watchId=null;
    Object.values(calls).forEach(x=>x.call.close()); calls={}; localStream?.getTracks().forEach(t=>t.stop()); localStream=null;
    peer?.destroy(); peer=null; hostConn=null; connections={}; roster={}; floor=null; lastPosition=null; selfId=null;
    $('setup').hidden=false; $('connected').hidden=true; $('start').disabled=false; updateScreen();
  }
  $('leave').onclick=()=>{cleanup(); msg('You left the group.');};
  $('copy').onclick=async()=>{
    const invite=new URL(location.hostname==='trailradio.local'?'trailradio://join':location.href); invite.search=''; invite.searchParams.set('room',roomId);
    const text=`Join my Trail Radio group “${groupName}”\n${invite.href}\nGroup code: ${roomId}`;
    try {if(window.TrailRadioNative) { window.TrailRadioNative.shareInvite(text); } else if(navigator.share) await navigator.share({title:'Join my Trail Radio group',text}); else {await navigator.clipboard.writeText(text); msg('Invitation copied. Send it to your crew!');}} catch(e) {if(e.name!=='AbortError') {try{await navigator.clipboard.writeText(text);msg('Invitation copied. Send it to your crew!');}catch(_){msg('Copy this group code and send it to your crew: '+roomId);}}}
  };
  function getLocation() {return new Promise((resolve,reject)=>navigator.geolocation.getCurrentPosition(resolve,reject,{enableHighAccuracy:true,timeout:20000,maximumAge:10000}));}
  $('setup-form').onsubmit=async e=>{
    e.preventDefault(); if(!window.Peer){msg('The radio network could not load. Check your internet connection and try again.');return;}
    if(!navigator.geolocation || !navigator.mediaDevices?.getUserMedia){msg('This browser needs HTTPS, microphone and location support to use the radio.');return;}
    selfName=clean($('name').value); if(!selfName)return; isHost=mode==='host';
    roomId=isHost?'tr-'+Array.from(crypto.getRandomValues(new Uint8Array(8)),x=>x.toString(16).padStart(2,'0')).join(''):clean($('code').value).toLowerCase();
    if(!/^tr-[a-f0-9]{16}$/.test(roomId)){msg('That group code doesn’t look right. Ask your host for a fresh invitation.');return;}
    groupName=isHost?(clean($('room-name').value)||'The Wanderers'):'Your group'; $('start').disabled=true; msg('Getting your microphone and location ready…');
    try {
      localStream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false}); localStream.getAudioTracks().forEach(t=>t.enabled=false);
      const loc=await getLocation(); lastPosition={lat:loc.coords.latitude,lon:loc.coords.longitude,time:Date.now()};
      peer=isHost?new Peer(roomId):new Peer();
      peer.on('open',id=>{
        selfId=id; watchId=navigator.geolocation.watchPosition(p=>{lastPosition={lat:p.coords.latitude,lon:p.coords.longitude,time:Date.now()}; sendPosition();},()=>{lastPosition=null; release(); sendPosition(); msg('Location unavailable. Enable location to use the radio.');},{enableHighAccuracy:true,maximumAge:5000,timeout:20000});
        locationTimer=setInterval(()=>{if(!position()) release(); sendPosition(); refreshLocation();},10000);
        if(isHost){roster[selfId]={name:selfName,pos:position(),host:true}; enter();}
        else {hostConn=peer.connect(roomId,{reliable:true}); hostConn.on('data',handleClientData); hostConn.on('close',()=>{cleanup();msg('The host went offline. Ask them to start a new group.');}); hostConn.on('error',()=>{cleanup();msg('Could not reach the host. Check the code and try again.');});}
      });
      peer.on('connection',conn=>isHost?hostConnection(conn):conn.close()); peer.on('call',incoming);
      peer.on('disconnected',()=>{if(active)msg('Radio network disconnected. Reconnecting…'); peer?.reconnect();});
      peer.on('error',err=>{const text=err.type==='peer-unavailable'?'Could not find that group. Make sure the host is online and check the code.':'Could not connect to the radio network. Please try again.'; cleanup();msg(text);});
    } catch(err) {cleanup(); msg(err.code===1||err.name==='NotAllowedError'?'Microphone and location permission are needed to join. Allow access in your browser settings and try again.':'Could not get a microphone or fresh location. Check your device settings and try again.');}
  };
  window.addEventListener('pagehide',()=>{release(); peer?.destroy();});
})();
