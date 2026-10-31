// Turns an OpenStreetMap export (.osm XML, "Export" on openstreetmap.org) into dist/map.json,
// the crew map's background: roads, paths, buildings, green space and water, without names or
// point features.
//
//   node tools/build-map.cjs <export.osm> [dist/map.json]
//
// Coordinates are stored in units of 1e-5 degrees (about a metre): each feature is a flat list,
// the first point relative to the export's south-west corner and every later point relative to
// the one before, which keeps the file small.
const fs = require('node:fs');

const [input, output = 'dist/map.json'] = process.argv.slice(2);
if (!input) { console.error('usage: node tools/build-map.cjs <export.osm> [dist/map.json]'); process.exit(2); }
const xml = fs.readFileSync(input, 'utf8');

const bounds = /<bounds minlat="([-\d.]+)" minlon="([-\d.]+)" maxlat="([-\d.]+)" maxlon="([-\d.]+)"/.exec(xml);
if (!bounds) throw new Error('no <bounds> in the export');
const [minLat, minLon, maxLat, maxLon] = bounds.slice(1).map(Number);
const Q = 1e5;

const nodes = new Map();
for (const m of xml.matchAll(/<node id="(\d+)"[^>]*? lat="([-\d.]+)" lon="([-\d.]+)"/g)) {
  nodes.set(m[1], [Math.round((Number(m[2]) - minLat) * Q), Math.round((Number(m[3]) - minLon) * Q)]);
}

const MAJOR = /^(motorway|trunk|primary|secondary|tertiary)(_link)?$/;
const MINOR = /^(residential|unclassified|service|living_street|road)$/;
const PATH = /^(footway|path|pedestrian|cycleway|steps|track|bridleway)$/;
const GREEN_LANDUSE = /^(grass|forest|meadow|recreation_ground|cemetery|village_green|orchard)$/;
const GREEN_LEISURE = /^(park|garden|pitch|playground|golf_course|nature_reserve|dog_park)$/;
const GREEN_NATURAL = /^(wood|scrub|grassland|heath)$/;

// The layer a way belongs to, or null to leave it out. Areas must be closed to be filled.
function layerOf(tags, closed) {
  if (tags.natural === 'water' || tags.waterway === 'riverbank' || tags.landuse === 'reservoir' || tags.water) return closed ? 'water' : null;
  if (tags.waterway && /^(river|stream|canal|ditch|drain)$/.test(tags.waterway)) return 'waterline';
  if (tags.building) return closed ? 'building' : null;
  if (closed && (GREEN_LANDUSE.test(tags.landuse || '') || GREEN_LEISURE.test(tags.leisure || '') || GREEN_NATURAL.test(tags.natural || ''))) return 'green';
  if (tags.railway === 'rail') return 'rail';
  if (MAJOR.test(tags.highway || '')) return 'major';
  if (MINOR.test(tags.highway || '')) return 'minor';
  if (PATH.test(tags.highway || '')) return 'path';
  return null;
}

const layers = { water: [], green: [], building: [], waterline: [], rail: [], path: [], minor: [], major: [] };
let kept = 0, skipped = 0;
for (const m of xml.matchAll(/<way id="\d+"[^>]*>([\s\S]*?)<\/way>/g)) {
  const body = m[1];
  const refs = [...body.matchAll(/<nd ref="(\d+)"\/>/g)].map(r => r[1]);
  const tags = Object.fromEntries([...body.matchAll(/<tag k="([^"]+)" v="([^"]*)"\/>/g)].map(t => [t[1], t[2]]));
  const layer = layerOf(tags, refs.length > 3 && refs[0] === refs[refs.length - 1]);
  const points = refs.map(r => nodes.get(r)).filter(Boolean);
  if (!layer || points.length < 2) { skipped++; continue; }
  const flat = [];
  let prev = [0, 0];
  for (const p of points) { flat.push(p[0] - prev[0], p[1] - prev[1]); prev = p; }
  layers[layer].push(flat);
  kept++;
}

const data = {
  attribution: '© OpenStreetMap contributors',
  license: 'ODbL 1.0, https://www.openstreetmap.org/copyright',
  bounds: [minLat, minLon, maxLat, maxLon],
  scale: Q,
  layers,
};
fs.writeFileSync(output, JSON.stringify(data));
const counts = Object.entries(layers).map(([k, v]) => `${k} ${v.length}`).join(', ');
console.log(`${output}: ${kept} ways kept (${counts}), ${skipped} left out, ${(fs.statSync(output).size / 1024).toFixed(0)} KB`);
