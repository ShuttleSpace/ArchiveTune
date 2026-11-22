#!/usr/bin/env node
/*
 * Pack a ListTwo runtime source (`dist-runtime/<id>.mjs` + `<id>.manifest.json`)
 * into an ArchiveTune `.arch` module.
 *
 * The source bundle is a self-contained ESM ending in `export{A as default,...}`.
 * ArchiveTune evaluates provider.js as a plain script inside
 * `(function(){ const module={exports:{}}; ... })()`, so we strip the export
 * statement and let tools/lt/adapter.js reference the default binding by name.
 *
 * Usage: node tools/lt/pack.mjs <distRuntimeDir> <outDir> <id> [<id>...]
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const adapterTemplate = readFileSync(join(here, 'adapter.js'), 'utf8');

function stripExport(code, id) {
  const match = /export\s*\{([^}]*)\}\s*;?\s*$/.exec(code);
  if (!match) throw new Error(`${id}: bundle does not end with an export{} statement`);
  const specifiers = match[1].split(',').map((part) => part.trim()).filter(Boolean);
  const names = new Map();
  for (const specifier of specifiers) {
    const [local, exported] = specifier.split(/\s+as\s+/).map((part) => part.trim());
    names.set(exported ?? local, local);
  }
  const fallback = names.get('default');
  if (!fallback) throw new Error(`${id}: bundle has no default export`);
  const body = code.slice(0, match.index);
  if (/(^|[\s;])export[\s{]/.test(body) || /(^|[\s;])import[\s{]/.test(body)) {
    throw new Error(`${id}: bundle still contains module syntax after stripping exports`);
  }
  return { body, fallback };
}

export function buildProvider(distDir, id) {
  const scriptPath = join(distDir, `${id}.mjs`);
  const manifestPath = join(distDir, `${id}.manifest.json`);
  if (!existsSync(scriptPath)) throw new Error(`${id}: missing ${scriptPath}`);
  if (!existsSync(manifestPath)) throw new Error(`${id}: missing ${manifestPath}`);
  const sourceManifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const { body, fallback } = stripExport(readFileSync(scriptPath, 'utf8'), id);
  const adapter = adapterTemplate.replaceAll('__LT_DEFAULT__', fallback);
  const provider = `/* ${sourceManifest.id}@${sourceManifest.version} | packed by tools/lt/pack.mjs */\n${body}\n${adapter}`;
  try {
    new Function(provider);
  } catch (error) {
    throw new Error(`${id}: packed provider.js is not valid script syntax: ${error.message}`);
  }
  return { sourceManifest, provider };
}

function pack(distDir, outDir, id) {
  const { sourceManifest, provider } = buildProvider(distDir, id);

  const script = Buffer.from(provider, 'utf8');
  const manifest = {
    format: 'archivetune.module',
    formatVersion: 1,
    apiVersion: 1,
    entry: 'provider.js',
    sha256: createHash('sha256').update(script).digest('hex'),
    module: {
      id: sourceManifest.id,
      name: sourceManifest.name,
      displayName: sourceManifest.name,
      version: sourceManifest.version,
      author: sourceManifest.author ?? '',
      description: sourceManifest.homepage ?? '',
      homepage: sourceManifest.homepage ?? '',
      type: ['audio_provider'],
      capabilities: ['searchTracks', 'getTrackStreamUrl'],
      labels: [],
    },
  };

  const stage = mkdtempSync(join(tmpdir(), `lt-arch-${id}-`));
  try {
    writeFileSync(join(stage, 'manifest.json'), JSON.stringify(manifest, null, 2));
    writeFileSync(join(stage, 'provider.js'), script);
    mkdirSync(outDir, { recursive: true });
    const target = join(outDir, `${id}.arch`);
    rmSync(target, { force: true });
    execFileSync('zip', ['-q', '-X', '-j', target, 'manifest.json', 'provider.js'], { cwd: stage });
    return { id, target, providerBytes: Buffer.byteLength(provider) };
  } finally {
    rmSync(stage, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [distDir, outDir, ...ids] = process.argv.slice(2);
  if (!distDir || !outDir || ids.length === 0) {
    console.error('usage: node tools/lt/pack.mjs <distRuntimeDir> <outDir> <id>...');
    process.exit(2);
  }
  let failed = 0;
  for (const id of ids) {
    try {
      const result = pack(resolve(distDir), resolve(outDir), id);
      console.log(`${result.id}.arch  ${(result.providerBytes / 1024).toFixed(0)} KiB provider.js  -> ${result.target}`);
    } catch (error) {
      failed += 1;
      console.error(`SKIP ${id}: ${error.message}`);
    }
  }
  if (failed) console.error(`${failed} of ${ids.length} source(s) could not be packed`);
  process.exit(0);
}
