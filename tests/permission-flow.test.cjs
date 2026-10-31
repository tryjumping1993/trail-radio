const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync('dist/app.js', 'utf8');

async function scenario(grant, failure) {
  const events = [], elements = new Map();
  class Element {
    constructor() { this.hidden = false; this.disabled = false; this.value = ''; this.textContent = ''; this.firstChild = {textContent:''}; this.classList = {add(){}, remove(){}, toggle(){}}; }
    addEventListener() {} setAttribute() {} replaceChildren() {} append() {}
  }
  const get = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const document = {getElementById:get, createElement:()=>new Element(), addEventListener(){}, body:new Element()};
  get('name').value = 'Sunny'; get('room-name').value = 'The Crew';
  const listeners = {};
  const window = {addEventListener(name, listener){listeners[name]=listener;}};
  const navigator = {
    geolocation: {getCurrentPosition(resolve, reject) { events.push('location'); if (failure === 'location') reject({code:2}); else resolve({coords:{latitude:0,longitude:0}}); }, watchPosition() {return 1;}, clearWatch() {}},
    mediaDevices: {async getUserMedia() {events.push('microphone'); if (failure === 'microphone') throw Object.assign(new Error('Audio unavailable'), {name:'NotReadableError'}); return {getAudioTracks:()=>[{enabled:false}], getTracks:()=>[]};}}
  };
  class Peer {
    constructor(id) {this.id = id; this.events={}; queueMicrotask(()=>this.events.open?.(id));}
    on(name, callback) {this.events[name]=callback;} destroy() {events.push('peer-destroy');}
  }
  window.Peer = Peer;
  window.TrailRadioNative = {
    requestPhonePermissions() {events.push('phone-permissions'); queueMicrotask(()=>window.trailRadioPermissionResult(grant));},
    startRadioSession() {events.push('service-start'); queueMicrotask(()=>window.radioServiceStarted(failure !== 'service'));},
    stopRadioSession() {events.push('service-stop');}, radioConnected() {events.push('service-connected');},
    openAppSettings() {events.push('settings');}, checkForUpdates() {}
  };
  const context = {window, document, navigator, Peer, location:{href:'https://trailradio.local/index.html',hostname:'trailradio.local'}, URL,
    crypto:{getRandomValues(array){array.fill(1);return array;}}, setTimeout, clearTimeout, setInterval:()=>1, clearInterval, Date, Number, Math, String, Object, Array, Promise};
  vm.runInNewContext(code, context, {filename:'app.js'});
  await get('setup-form').onsubmit({preventDefault(){}});
  await new Promise(resolve=>setImmediate(resolve));
  return {events,get,listeners};
}
(async()=>{
  const allowed = await scenario(true);
  assert.deepEqual(allowed.events.slice(0,4), ['phone-permissions','service-start','microphone','location'], 'phone permissions and service precede microphone and location');
  assert.equal(allowed.get('connected').hidden, false, 'granted access joins a hosted group');
  assert.equal(allowed.get('permission-settings').hidden, true);
  assert.equal(allowed.get('crew-details').hidden, false, 'crew panel becomes available after joining');
  allowed.listeners.pagehide();
  assert.equal(allowed.events.includes('peer-destroy'), false, 'hiding the native page keeps the group connected');
  allowed.get('leave').onclick();
  assert.equal(allowed.events.includes('service-stop'), true, 'leaving stops the background service');
  assert.equal(allowed.events.includes('peer-destroy'), true, 'leaving disconnects the group');
  assert.equal(allowed.get('crew-details').hidden, true, 'crew panel is hidden after leaving');
  assert.equal(allowed.get('setup').hidden, false, 'group setup is restored after leaving');
  const denied = await scenario(false);
  assert.deepEqual(denied.events, ['phone-permissions'], 'denied phone permission never starts microphone or location');
  assert.equal(denied.get('permission-settings').hidden, false, 'settings action is offered after denial');
  assert.match(denied.get('message').textContent, /phone settings/i);
  assert.doesNotMatch(denied.get('message').textContent, /browser/i);
  denied.get('permission-settings').onclick();
  assert.equal(denied.events.at(-1), 'settings');
  const serviceFailure = await scenario(true, 'service');
  assert.match(serviceFailure.get('message').textContent, /could not keep the radio running/i);
  assert.equal(serviceFailure.events.includes('microphone'), false, 'microphone is not started if the foreground service fails');
  const micFailure = await scenario(true, 'microphone');
  assert.match(micFailure.get('message').textContent, /could not start the microphone/i);
  assert.equal(micFailure.get('permission-settings').hidden, true, 'hardware failure is not presented as denied phone permissions');
  const locationFailure = await scenario(true, 'location');
  assert.match(locationFailure.get('message').textContent, /could not get your location/i);
  assert.equal(locationFailure.get('permission-settings').hidden, true, 'GPS failure is not presented as denied phone permissions');
  console.log('Phone permission flow checks passed.');
})().catch(error=>{console.error(error); process.exitCode=1;});
