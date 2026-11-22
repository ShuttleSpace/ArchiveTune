#!/usr/bin/env node
/*
 * Run a packed ListTwo provider under a Node "mini module host" that mirrors
 * ArchiveTune's QuickJS wrapper (ModuleRuntime.kt:333-336) so the adapter,
 * the export rewrite and a real source round-trip can be checked without an
 * Android build.
 *
 * Usage: node tools/lt/verify.mjs <distRuntimeDir> <id> [query]
 */
import { resolve } from 'node:path';
import { buildProvider } from './pack.mjs';

const [distDir, id, query = '周杰伦'] = process.argv.slice(2);
if (!distDir || !id) {
  console.error('usage: node tools/lt/verify.mjs <distRuntimeDir> <id> [query]');
  process.exit(2);
}

const calls = [];
globalThis.__moduleExtensionCall = (json) => {
  const input = JSON.parse(json);
  calls.push(input.action);
  if (input.action === 'cookieGet') return 'null';
  if (input.action === 'cookieSet') return 'true';
  return 'null';
};

if (process.env.LT_DEBUG) {
  const inner = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const response = await inner(input, init);
    const bytes = new Uint8Array(await response.clone().arrayBuffer());
    let utf8 = true;
    try { new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch (error) { utf8 = false; }
    let parsed = 'n/a';
    if (utf8) {
      try { JSON.parse(new TextDecoder().decode(bytes)); parsed = 'json-ok'; }
      catch (error) { parsed = `json-fail: ${error.message.slice(0, 70)}`; }
    }
    console.log(`[fetch] ${response.status} ${response.headers.get('content-type')} ${bytes.length}B utf8=${utf8} ${parsed} ${String(input).slice(0, 80)}`);
    return response;
  };
}

const { provider } = buildProvider(resolve(distDir), id);
// Same shape as ModuleRuntime.evaluate(): plain script, CommonJS exports.
const factory = new Function(
  `const module={exports:{}};const exports=module.exports;const self=globalThis;${provider}\n;return module.exports;`
);
const mod = factory();

const assert = (condition, message) => {
  if (!condition) throw new Error(`FAIL ${id}: ${message}`);
};

assert(typeof mod.searchTracks === 'function', 'searchTracks missing');
assert(typeof mod.getTrackStreamUrl === 'function', 'getTrackStreamUrl missing');
for (const key of ['id', 'name', 'version']) assert(typeof mod[key] === 'string' && mod[key].length > 0, `manifest.${key} missing`);

const tracks = await mod.searchTracks(query, 10);
assert(Array.isArray(tracks), 'searchTracks did not return an array');
console.log(`${id} ${mod.version} — ${tracks.length} track(s) for "${query}"`);
for (const track of tracks.slice(0, 5)) {
  assert(typeof track.id === 'string' && track.id.length > 0, 'track without id');
  assert(typeof track.title === 'string' && track.title.length > 0, 'track without title');
  assert(Array.isArray(track.artists), 'track.artists must be an array of {name}');
  const artist = track.artists.map((item) => item.name).join(', ') || '(none)';
  const seconds = track.durationMs ? Math.round(track.durationMs / 1000) : null;
  console.log(`  ${track.id}  ${track.title} — ${artist}  ${seconds ? `${seconds}s` : ''}`);
}

if (tracks.length === 0) {
  console.log(`${id}: no results, skipping stream check`);
  process.exit(0);
}
// VIP/login-only tracks legitimately have no URL, so try a few before giving up.
let resolved = null;
for (const track of tracks.slice(0, 5)) {
  const stream = await mod.getTrackStreamUrl(track.id);
  if (stream && typeof stream.streamUrl === 'string') { resolved = { track, stream }; break; }
}
if (!resolved) {
  console.log(`  stream  (none in first 5 tracks - may all be VIP/login-only)`);
} else {
  const { track, stream } = resolved;
  assert(/^https?:\/\//.test(stream.streamUrl), `streamUrl is not http(s): ${stream.streamUrl}`);
  console.log(`  stream  ${track.title} -> ${new URL(stream.streamUrl).host}  ${stream.mimeType ?? ''}`);
}
console.log(`${id}: OK`);
