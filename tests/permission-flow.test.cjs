const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync('dist/app.js', 'utf8');

async function scenario(grant) {
  const events = [], elements = new Map();
  class Element {
    constructor() { this.hidden = false; this.disabled = false; this.value = ''; this.textContent = ''; this.firstChild = {textContent:''}; this.classList = {add(){}, remove(){}, toggle(){}}; }
    addEventListener() {} setAttribute() {} replaceChildren() {} append() {}
  }
  const get = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const document = {getElementById:get, createElement:()=>new Element(), addEventListener(){}, body:new Element()};
  get('name').value = 'Sunny'; get('room-name').value = 'The Crew';
  const window = {addEventListener(){}};
  const navigator = {
    geolocation: {getCurrentPosition(resolve) { events.push('location'); resolve({coords:{latitude:0,longitude:0}}); }, watchPosition() {return 1;}, clearWatch() {}},
    mediaDevices: {async getUserMedia() {events.push('microphone'); return {getAudioTracks:()=>[{enabled:false}], getTracks:()=>[]};}}
  };
  class Peer {
    constructor(id) {this.id = id; this.events={}; queueMicrotask(()=>this.events.open?.(id));}
    on(name, callback) {this.events[name]=callback;} destroy() {}
  }
  window.Peer = Peer;
  window.TrailRadioNative = {
    requestPhonePermissions() {events.push('phone-permissions'); queueMicrotask(()=>window.trailRadioPermissionResult(grant));},
    openAppSettings() {events.push('settings');}, checkForUpdates() {}
  };
  const context = {window, document, navigator, Peer, location:{href:'https://trailradio.local/index.html',hostname:'trailradio.local'}, URL,
    crypto:{getRandomValues(array){array.fill(1);return array;}}, setTimeout, clearTimeout, setInterval:()=>1, clearInterval, Date, Number, Math, String, Object, Array, Promise};
  vm.runInNewContext(code, context, {filename:'app.js'});
  await get('setup-form').onsubmit({preventDefault(){}});
  await new Promise(resolve=>setImmediate(resolve));
  return {events,get};
}
(async()=>{
  const allowed = await scenario(true);
  assert.deepEqual(allowed.events.slice(0,3), ['phone-permissions','microphone','location'], 'phone permission precedes microphone and location');
  assert.equal(allowed.get('connected').hidden, false, 'granted access joins a hosted group');
  assert.equal(allowed.get('permission-settings').hidden, true);
  const denied = await scenario(false);
  assert.deepEqual(denied.events, ['phone-permissions'], 'denied phone permission never starts microphone or location');
  assert.equal(denied.get('permission-settings').hidden, false, 'settings action is offered after denial');
  assert.match(denied.get('message').textContent, /phone settings/i);
  assert.doesNotMatch(denied.get('message').textContent, /browser/i);
  denied.get('permission-settings').onclick();
  assert.equal(denied.events.at(-1), 'settings');
  console.log('Phone permission flow checks passed.');
})().catch(error=>{console.error(error); process.exitCode=1;});
