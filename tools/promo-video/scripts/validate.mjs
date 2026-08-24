import {execFileSync} from 'node:child_process';
import {readFileSync, statSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import ffprobe from 'ffprobe-static';

const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(toolRoot, '..', '..');
const promo = path.join(repoRoot, 'docs', 'media', 'dualsub-replay-promo.mp4');
const gif = path.join(repoRoot, 'docs', 'images', 'dualsub-replay-demo.gif');
const poster = path.join(repoRoot, 'docs', 'images', 'dualsub-replay-promo-poster.jpg');
const social = path.join(repoRoot, 'docs', 'images', 'dualsub-replay-social-preview.png');
const sourceDir = path.join(toolRoot, 'public', 'source');

const probe = JSON.parse(execFileSync(ffprobe.path, [
  '-v', 'error', '-show_entries', 'format=duration:stream=codec_type,codec_name,width,height,pix_fmt,avg_frame_rate',
  '-of', 'json', promo,
], {encoding: 'utf8'}));
const video = probe.streams.find((stream) => stream.codec_type === 'video');
const audio = probe.streams.find((stream) => stream.codec_type === 'audio');
const duration = Number(probe.format.duration);

const probeVisual = (file, countPackets = false) => JSON.parse(execFileSync(ffprobe.path, [
  '-v', 'error', ...(countPackets ? ['-count_packets'] : []), '-select_streams', 'v:0',
  '-show_entries', 'stream=width,height,avg_frame_rate,nb_read_packets', '-of', 'json', file,
], {encoding: 'utf8'})).streams[0];
const gifVideo = probeVisual(gif, true);
const posterVideo = probeVisual(poster);
const socialVideo = probeVisual(social);
const sourceClips = [
  ['dual-subtitles.mp4', 582, 1280],
  ['instant-replay.mp4', 582, 1280],
  ['fullscreen.mp4', 1280, 582],
].map(([name, width, height]) => {
  const result = JSON.parse(execFileSync(ffprobe.path, [
    '-v', 'error', '-show_entries', 'stream=codec_type,codec_name,width,height,pix_fmt,avg_frame_rate',
    '-of', 'json', path.join(sourceDir, name),
  ], {encoding: 'utf8'}));
  return {name, width, height, streams: result.streams};
});

const checks = [
  [video?.codec_name === 'h264', 'Promo codec is H.264'],
  [video?.width === 1080 && video?.height === 1920, 'Promo is 1080x1920'],
  [video?.pix_fmt === 'yuv420p', 'Promo uses yuv420p'],
  [video?.avg_frame_rate === '30/1', 'Promo is 30 fps'],
  [duration >= 29.9 && duration <= 30.1, `Promo duration is 30 seconds (${duration})`],
  [audio === undefined, 'Promo has no audio stream'],
  [statSync(promo).size < 20 * 1024 * 1024, 'Promo is under 20 MiB'],
  [statSync(gif).size < 8 * 1024 * 1024, 'README GIF is under 8 MiB'],
  [gifVideo?.width === 480 && gifVideo?.height === 854, 'README GIF is 480x854'],
  [gifVideo?.avg_frame_rate === '12/1' && gifVideo?.nb_read_packets === '96', 'README GIF is 8 seconds at 12 fps'],
  [posterVideo?.width === 1080 && posterVideo?.height === 1920, 'Poster is 1080x1920'],
  [statSync(social).size < 1024 * 1024, 'Social card is under 1 MiB'],
  [socialVideo?.width === 1280 && socialVideo?.height === 640, 'Social card is 1280x640'],
];

const bytes = readFileSync(promo);
checks.push([bytes.indexOf(Buffer.from('moov')) < bytes.indexOf(Buffer.from('mdat')), 'Promo metadata is fast-start optimized']);
for (const sourceClip of sourceClips) {
  const sourceVideo = sourceClip.streams.find((stream) => stream.codec_type === 'video');
  const sourceAudio = sourceClip.streams.find((stream) => stream.codec_type === 'audio');
  checks.push([
    sourceVideo?.codec_name === 'h264' && sourceVideo?.pix_fmt === 'yuv420p' && sourceVideo?.avg_frame_rate === '30/1',
    `${sourceClip.name} is normalized H.264/YUV420p at 30 fps`,
  ]);
  checks.push([
    sourceVideo?.width === sourceClip.width && sourceVideo?.height === sourceClip.height,
    `${sourceClip.name} has the expected corrected dimensions`,
  ]);
  checks.push([sourceAudio === undefined, `${sourceClip.name} is silent`]);
}

let failed = false;
for (const [ok, label] of checks) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`);
  failed ||= !ok;
}
if (failed) process.exit(1);
