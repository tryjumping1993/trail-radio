const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('dist/app.js', 'utf8');

async function createScenario(mode, range='1500', saved=new Map()) {
  let now = 100000, nextTimer = 1, watches = 0, seed = saved.size*101+7;
  const timers = new Map(), elements = new Map(), peers = [], events = [], listeners = {};
  function setTimer(fn, delay, interval=false) { const id=nextTimer++; timers.set(id,{fn,at:now+Number(delay||0),interval:interval?Number(delay):0}); return id; }
  function clearTimer(id) {timers.delete(id);}
  function advance(ms) {
    const end=now+ms;
    while (true) {
      let selected=null;
      for (const [id,timer] of timers) if(timer.at<=end && (!selected || timer.at<selected[1].at)) selected=[id,timer];
      if(!selected) break;
      const [id,timer]=selected; now=timer.at; timers.delete(id);
      if(timer.interval) timers.set(id,{...timer,at:now+timer.interval});
      timer.fn();
    }
    now=end;
  }
  class FakeDate extends Date { static now(){return now;} }
  class Element {
    constructor(){this.listeners={};this.hidden=false;this.disabled=false;this.value='';this.textContent='';this.firstChild={textContent:''};this.classList={add(){},remove(){},toggle(){}};}
    addEventListener(name,fn){(this.listeners[name]||=[]).push(fn);} dispatch(name,e={}){(this.listeners[name]||[]).forEach(fn=>fn({preventDefault(){},pointerId:1,...e}));}
    setPointerCapture(){} setAttribute(){} remove(){}
    play(){this.played=true;return Promise.resolve();}
    replaceChildren(...c){this.children=c;} append(...c){this.children=[...(this.children||[]),...c];}
    requestSubmit(){return this.onsubmit?.({preventDefault(){}});}
  }
  const get=id=>{if(!elements.has(id)) elements.set(id,new Element());return elements.get(id);};
  get('name').value='Sunny'; get('room-name').value='Crew'; get('code').value='tr-0123456789abcdef'; get('range').value=range;
  const document={getElementById:get,createElement:()=>new Element(),addEventListener(){},body:new Element(),hidden:false};
  const window={addEventListener(name,fn){listeners[name]=fn;},scrollTo(){}};
  class FakeConnection {
    constructor(peer){this.peer=peer;this.events={};this.open=false;this.sent=[];}
    on(name,fn){this.events[name]=fn;return this;}
    emit(name,data){this.events[name]?.(data);}
    send(data){this.sent.push(data);}
    close(){this.open=false;this.emit('close');}
  }
  class FakePeer {
    constructor(id){this.id=id || 'guest-'+(peers.length+1);this.events={};this.open=false;this.disconnected=false;this.destroyed=false;this.reconnects=0;this.links=[];this.sent=[];this.socket={send:data=>this.sent.push(data)};peers.push(this);}
    on(name,fn){this.events[name]=fn;return this;}
    emit(name,data){this.events[name]?.(data);}
    opened(){this.open=true;this.disconnected=false;this.emit('open',this.id);}
    loseNetwork(){this.emit('error',{type:'network'});this.open=false;this.disconnected=true;this.emit('disconnected',this.id);}
    reconnect(){this.reconnects++;this.disconnected=false;}
    connect(id){const link=new FakeConnection(id);this.links.push(link);return link;}
    destroy(){this.destroyed=true;this.open=false;this.emit('close');events.push('peer-destroy');}
  }
  window.Peer=FakePeer;
  window.TrailRadioNative={
    requestPhonePermissions(){queueMicrotask(()=>window.trailRadioPermissionResult(true));},
    startRadioSession(){queueMicrotask(()=>window.radioServiceStarted(true));},
    stopRadioSession(){events.push('service-stop');},
    radioConnected(){events.push('service-connected');},
    radioNetworkStatus(ready){events.push(ready?'network-ready':'network-reconnecting');},
    checkForUpdates(){},openAppSettings(){}
  };
  const navigator={geolocation:{getCurrentPosition(ok){ok({coords:{latitude:1,longitude:1}});},watchPosition(){watches++;return watches;},clearWatch(){}},mediaDevices:{async getUserMedia(){return {getAudioTracks:()=>[{enabled:false}],getTracks:()=>[]};}}};
  const context={window,document,navigator,Peer:FakePeer,location:{href:'https://trailradio.local/index.html',hostname:'trailradio.local'},URL,crypto:{getRandomValues(a){for(let i=0;i<a.length;i++) a[i]=(seed++*37+i)&255;return a;}},
    localStorage:{getItem:k=>saved.has(k)?saved.get(k):null,setItem:(k,v)=>saved.set(k,String(v))},
    setTimeout:(fn,ms)=>setTimer(fn,ms),clearTimeout:clearTimer,setInterval:(fn,ms)=>setTimer(fn,ms,true),clearInterval:clearTimer,Date:FakeDate,Number,Math,String,Object,Array,Promise};
  vm.runInNewContext(source,context,{filename:'app.js'});
  if(mode==='join') get('join-tab').onclick();
  await get('setup-form').onsubmit({preventDefault(){}});
  await Promise.resolve();
  return {get,window,events,peers,advance,skip:ms=>{now+=ms;},watches:()=>watches,saved};
}
(async()=>{
  const host=await createScenario('host');
  const first=host.peers[0]; first.opened();
  assert.equal(host.get('connected').hidden,false,'host starts a group');
  const code=host.get('room-code').textContent;
  first.loseNetwork();
  assert.equal(host.get('connected').hidden,false,'temporary signaling error does not leave group');
  assert.equal(host.get('screen-label').textContent,'RECONNECTING');
  assert.equal(host.get('talk').disabled,true,'talking is disabled while signaling is unavailable');
  assert.equal(host.events.includes('service-stop'),false,'background service remains running');
  host.advance(1000); assert.equal(first.reconnects,1,'disconnected Peer is retried');
  first.opened();
  assert.equal(host.get('room-code').textContent,code,'host code remains stable after reconnect');
  assert.equal(host.get('screen-label').textContent,'CHANNEL OPEN');
  assert.equal(host.watches(),1,'reconnection does not start duplicate GPS watches');
  host.window.radioBackgroundTick();
  assert.equal(first.sent.at(-1).type,'HEARTBEAT','native tick sends a signaling heartbeat');
  first.emit('error',{type:'socket-error'}); first.destroyed=true; first.open=false; first.emit('close');
  host.advance(1000);
  assert.equal(host.peers.length,2,'destroyed Peer is recreated');
  assert.equal(host.peers[1].id,code,'recreated host uses the original group code');
  host.peers[1].opened();
  assert.equal(host.get('connected').hidden,false,'group remains visible after peer recreation');
  host.get('leave').onclick();
  const count=host.peers.length; host.advance(60000);
  assert.equal(host.peers.length,count,'leaving cancels retries');
  assert.equal(host.events.filter(x=>x==='service-stop').length,1,'leaving stops the service once');

  const sleeping=await createScenario('host');
  sleeping.peers[0].opened(); sleeping.peers[0].loseNetwork();
  sleeping.skip(2000); // Hidden-page timers can be throttled while native service ticks continue.
  sleeping.window.radioBackgroundTick();
  assert.equal(sleeping.peers[0].reconnects,1,'native tick runs an overdue reconnection');
  assert.equal(sleeping.events.includes('service-stop'),false,'background retry keeps the service running');
  sleeping.get('leave').onclick();

  const guest=await createScenario('join');
  const p=guest.peers[0]; p.opened(); guest.advance(0);
  const link=p.links[0]; link.open=true; link.emit('data',{type:'welcome-request'});
  link.emit('data',{type:'state',groupName:'Crew',floor:null,roster:{[p.id]:{name:'Sunny',pos:{lat:1,lon:1,time:100000},host:false}}});
  assert.equal(guest.get('connected').hidden,false,'guest joins group');
  p.emit('error',{type:'peer-unavailable'});
  assert.equal(guest.get('connected').hidden,false,'unavailable member does not destroy the group');
  link.close();
  assert.equal(guest.get('screen-label').textContent,'RECONNECTING');
  assert.equal(guest.events.includes('service-stop'),false,'lost host link does not leave group');
  guest.advance(1000);
  assert.equal(p.links.length,2,'guest retries the host link');
  const replacement=p.links[1]; replacement.open=true; replacement.emit('data',{type:'welcome-request'});
  replacement.emit('data',{type:'state',groupName:'Crew',floor:null,roster:{[p.id]:{name:'Sunny',pos:{lat:1,lon:1,time:101000},host:false}}});
  assert.equal(guest.get('screen-label').textContent,'CHANNEL OPEN','guest resumes after host state arrives');
  link.emit('close');
  assert.equal(guest.get('screen-label').textContent,'CHANNEL OPEN','stale link events cannot tear down the new link');
  guest.get('leave').onclick();

  const missing=await createScenario('join');
  missing.peers[0].opened(); missing.advance(0);
  missing.advance(30000);
  assert.equal(missing.get('setup').hidden,false,'an unreachable initial invite eventually returns to setup');
  assert.match(missing.get('message').textContent,/check the code/i);
  assert.equal(missing.events.includes('service-stop'),true,'failed initial join stops the foreground service');
  const mile=await createScenario('host','5280');
  mile.peers[0].opened();
  assert.equal(mile.get('range-tag').textContent,'◉ 1 MILE RANGE','the host picks the talk range');
  const far=mile.peers[0]; const guestLink=new (far.connect('x').constructor)('guest-9');
  far.emit('connection',guestLink); guestLink.open=true; guestLink.emit('open'); guestLink.emit('data',{type:'hello',name:'Fern'});
  assert.equal(guestLink.sent.at(-1).range,5280,'the range is shared with the group');
  mile.get('leave').onclick();

  const ranged=await createScenario('join');
  const rp=ranged.peers[0]; rp.opened(); ranged.advance(0);
  const rl=rp.links[0]; rl.open=true;
  const state=range=>rl.emit('data',{type:'state',groupName:'Crew',floor:null,range,roster:{[rp.id]:{name:'Sunny',pos:{lat:1,lon:1,time:100000},host:false}}});
  state(5280); assert.equal(ranged.get('range-tag').textContent,'◉ 1 MILE RANGE','guests use the host range');
  for(let miles=2;miles<=5;miles++) {
    state(miles*5280); assert.equal(ranged.get('range-tag').textContent,`◉ ${miles} MILES RANGE`,'guests use extended host ranges');
  }
  state(99999); assert.equal(ranged.get('range-tag').textContent,'◉ 5 MILES RANGE','the range never exceeds five miles');
  state(undefined); assert.equal(ranged.get('range-tag').textContent,'◉ 1,500 FT RANGE','older hosts keep 1,500 ft');
  ranged.get('leave').onclick();
  // Audio recipients are selected by their distance from the speaker, up to five miles.
  const extended=await createScenario('host','26400');
  const ep=extended.peers[0]; ep.opened(); const recipients=[];
  ep.call=id=>{recipients.push(id); return {on(){},close(){}};};
  for(const [id,miles] of [['inside',4.99],['outside',5.01]]) {
    const c=new (ep.connect('x').constructor)(id);
    ep.emit('connection',c); c.open=true; c.emit('open'); c.emit('data',{type:'hello',name:id});
    const lat=1+(miles*1609.344/6371000)*180/Math.PI;
    c.emit('data',{type:'position',pos:{lat,lon:1,time:100000}});
    assert.equal(c.sent.at(-1).range,26400,'the host broadcasts the five-mile range');
  }
  assert.equal(extended.get('range-tag').textContent,'◉ 5 MILES RANGE');
  extended.get('talk').dispatch('pointerdown'); extended.advance(250);
  assert.deepEqual(recipients,['inside'],'audio reaches a member just inside five miles, but not beyond');
  extended.get('talk').dispatch('pointerup'); extended.get('leave').onclick();
  // Signaling and group-state messages travel independently: the media offer can arrive first.
  const listener=await createScenario('join');
  const lp=listener.peers[0]; lp.opened(); listener.advance(0);
  const lc=lp.links[0]; lc.open=true;
  const listeningState=(floor,lat=1)=>lc.emit('data',{type:'state',groupName:'Crew',floor,range:26400,roster:{
    [lp.id]:{name:'Sunny',pos:{lat:1,lon:1,time:100000}},
    speaker:{name:'Fern',pos:{lat,lon:1,time:100000},host:true}
  }});
  class MediaCall {
    constructor(peer='speaker'){this.peer=peer;this.events={};this.answered=false;this.closed=false;}
    on(event,fn){(this.events[event]||=[]).push(fn);return this;}
    emit(event,data){(this.events[event]||[]).forEach(fn=>fn(data));}
    answer(){this.answered=true;this.emit('stream',{});}
    close(){this.closed=true;this.emit('close');}
  }
  listeningState(null);
  const early=new MediaCall(); lp.emit('call',early);
  assert.equal(early.closed,false,'an early media offer is retained');
  assert.equal(early.answered,false,'audio is not answered before host authorization');
  listener.advance(1500); listeningState('speaker');
  assert.equal(early.answered,true,'a delayed floor announcement starts the retained call');
  early.close(); listeningState(null);
  const unauthorized=new MediaCall(); lp.emit('call',unauthorized); listener.advance(8000);
  assert.equal(unauthorized.answered,false); assert.equal(unauthorized.closed,true,'unauthorized offers expire');
  listeningState('speaker',2);
  const outside=new MediaCall(); lp.emit('call',outside); listener.advance(8000);
  assert.equal(outside.answered,false); assert.equal(outside.closed,true,'floor ownership cannot bypass GPS range');
  const unknown=new MediaCall('unknown'); lp.emit('call',unknown);
  assert.equal(unknown.closed,true,'calls from outside the group are rejected');
  listeningState(null);
  const abandoned=new MediaCall(); lp.emit('call',abandoned); listener.get('leave').onclick();
  assert.equal(abandoned.closed,true,'leaving closes early offers immediately');
  // A member who comes back on a new connection (switched apps, reloaded) is counted once.
  const dedupe=await createScenario('host');
  const dp=dedupe.peers[0]; dp.opened();
  const join=(peerId,key)=>{const c=new (dp.connect('x').constructor)(peerId); dp.emit('connection',c); c.open=true; c.emit('open'); c.emit('data',{type:'hello',name:'Fern',key}); return c;};
  const KEY='ab'.repeat(16);
  const original=join('guest-a',KEY); join('guest-b','cd'.repeat(16));
  assert.equal(dedupe.get('count').textContent,'03','host and two guests');
  const again=join('guest-a2',KEY);
  assert.equal(dedupe.get('count').textContent,'03','the returning guest replaces their old entry');
  assert.equal(original.open,false,'the old connection is closed');
  assert.deepEqual(Object.keys(again.sent.at(-1).roster).sort(),[dp.id,'guest-a2','guest-b'].sort());
  assert.equal(Object.values(again.sent.at(-1).roster).some(m=>'key' in m),false,'member keys are not shared');
  dedupe.get('leave').onclick();

  // Closing the app keeps the group offered for a rejoin; leaving removes it.
  const saved=new Map();
  const before=await createScenario('host','26400',saved);
  before.peers[0].opened();
  const hostRoom=before.get('room-code').textContent;
  const later=await createScenario('join','1500',saved);
  assert.equal(later.get('rejoin').hidden,false,'a recent group is offered after a restart');
  const recent=JSON.parse(saved.get('trailRadioGroups'));
  assert.equal(recent[0].room,hostRoom); assert.equal(recent[0].host,true); assert.equal(recent[0].range,26400);
  later.get('leave').onclick();
  const rehost=await createScenario('host','1500',saved);
  rehost.peers[0].opened(); rehost.get('leave').onclick();
  const offered=rehost.get('rejoin-list').children;
  assert.equal(offered.length,1,'only the group that was not left is offered');
  assert.match(offered[0].textContent,/YOU HOST/);
  offered[0].onclick(); await new Promise(r=>setImmediate(r));
  assert.equal(rehost.peers.at(-1).id,hostRoom,'a host rejoins with the same group code');
  rehost.peers.at(-1).opened();
  assert.equal(rehost.get('range-tag').textContent,'◉ 5 MILES RANGE','with the same five-mile range');
  rehost.get('leave').onclick();
  assert.equal(JSON.parse(saved.get('trailRadioGroups')).length,0,'leaving forgets the group');
  // Five quick presses switch the map flag; it reaches the host and the group's state.
  const quiet=await createScenario('host');
  const qp=quiet.peers[0]; qp.opened();
  const qc=new (qp.connect('x').constructor)('guest-q'); qp.emit('connection',qc); qc.open=true; qc.emit('open'); qc.emit('data',{type:'hello',name:'Fern'});
  const press=n=>{for(let k=0;k<n;k++) {quiet.get('talk').dispatch('pointerdown'); quiet.get('talk').dispatch('pointerup'); quiet.skip(150);}};
  press(4); assert.equal(qc.sent.at(-1).roster[qp.id].hidden,false,'four presses change nothing');
  quiet.skip(1000); press(5); assert.equal(qc.sent.at(-1).roster[qp.id].hidden,true,'five presses within a second');
  quiet.skip(1000); press(5); assert.equal(qc.sent.at(-1).roster[qp.id].hidden,false,'and five more switch it back');
  qc.emit('data',{type:'visibility',hidden:true}); assert.equal(qc.sent.at(-1).roster['guest-q'].hidden,true,'a guest can switch it too');
  quiet.get('leave').onclick();
  // The home-screen widget: tap to talk, tap again to stop, and a minute at most.
  const widget=await createScenario('host');
  widget.peers[0].opened();
  assert.equal(widget.window.radioWidgetState().state,'open');
  widget.window.radioWidgetToggle(); assert.equal(widget.window.radioWidgetState().state,'talking','a tap starts talking');
  widget.window.radioWidgetToggle(); assert.equal(widget.window.radioWidgetState().state,'open','the next tap stops');
  widget.window.radioWidgetToggle(); widget.skip(61000);
  assert.notEqual(widget.window.radioWidgetState().state,'talking','a widget transmission ends after a minute');
  widget.get('leave').onclick();
  assert.equal(widget.window.radioWidgetState().state,'offline');
  console.log('Reconnection and background heartbeat checks passed.');
})().catch(error=>{console.error(error);process.exitCode=1;});
