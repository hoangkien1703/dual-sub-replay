import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import ffmpegPath from 'ffmpeg-static';

// The word-lookup scenes sit under a real app screenshot: the YouTube page from the
// landscape README image (header, video and title), cropped and kept in public/source.
const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(toolRoot, '..', '..');
const output = path.join(toolRoot, 'public', 'source');
mkdirSync(output, {recursive: true});

execFileSync(ffmpegPath, [
  '-hide_banner', '-loglevel', 'error',
  '-i', path.join(repoRoot, 'docs', 'images', 'dualsub-replay-landscape.png'),
  '-vf', 'crop=631:369:83:83', '-y', path.join(output, 'japanese-page.png'),
], {stdio: 'inherit'});
console.log('Prepared public/source/japanese-page.png');
