import {existsSync, readFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(toolRoot, '..', '..');
const readme = readFileSync(path.join(repoRoot, 'README.md'), 'utf8');
const found = new Set();

for (const pattern of [/\(([^)\s]+)(?:\s+"[^"]*")?\)/g, /<(?:a|img)\s+[^>]*(?:href|src)="([^"]+)"/g]) {
  for (const match of readme.matchAll(pattern)) found.add(match[1]);
}

let failed = false;
for (const target of [...found].sort()) {
  if (/^https?:\/\//i.test(target)) {
    try {
      const response = await fetch(target, {redirect: 'follow', headers: {'user-agent': 'DualSub-Replay-link-check'}});
      const ok = response.status < 400;
      console.log(`${ok ? 'PASS' : 'FAIL'} ${response.status} ${target}`);
      failed ||= !ok;
      await response.body?.cancel();
    } catch (error) {
      failed = true;
      console.log(`FAIL ${target}: ${error.message}`);
    }
    continue;
  }

  const clean = decodeURIComponent(target.split('#')[0]);
  const ok = clean.length === 0 || existsSync(path.resolve(repoRoot, clean));
  console.log(`${ok ? 'PASS' : 'FAIL'} local ${target}`);
  failed ||= !ok;
}

if (failed) process.exit(1);
