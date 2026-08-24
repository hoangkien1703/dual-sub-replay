import {execFileSync} from 'node:child_process';
import {copyFileSync, mkdirSync, statSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import ffmpegPath from 'ffmpeg-static';

const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(toolRoot, '..', '..');
const out = path.join(toolRoot, 'out');
const images = path.join(repoRoot, 'docs', 'images');
const promo = path.join(repoRoot, 'docs', 'media', 'dualsub-replay-promo.mp4');
const loop = path.join(out, 'readme-loop.mp4');
const gif = path.join(images, 'dualsub-replay-demo.gif');
const cleanedPromo = path.join(out, 'dualsub-replay-promo-clean.mp4');
mkdirSync(out, {recursive: true});
mkdirSync(images, {recursive: true});

const run = (args) => execFileSync(ffmpegPath, ['-hide_banner', '-loglevel', 'error', ...args], {stdio: 'inherit'});

run([
  '-i', promo, '-an', '-c:v', 'libx264', '-preset', 'slow', '-crf', '20',
  '-pix_fmt', 'yuv420p', '-color_range', 'tv', '-movflags', '+faststart', '-y', cleanedPromo,
]);
copyFileSync(cleanedPromo, promo);

const renderGif = (width) => run([
  '-i', loop,
  '-filter_complex', `[0:v]fps=12,scale=${width}:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle`,
  '-loop', '0', '-y', gif,
]);

renderGif(480);
if (statSync(gif).size > 8 * 1024 * 1024) renderGif(420);

run([
  '-i', promo, '-vf', 'fps=1/3,scale=270:-2:flags=lanczos,tile=5x2:padding=8:margin=8',
  '-frames:v', '1', '-q:v', '2', '-y', path.join(out, 'promo-contact-sheet.jpg'),
]);

console.log(`README loop: ${(statSync(gif).size / 1024 / 1024).toFixed(2)} MiB`);
