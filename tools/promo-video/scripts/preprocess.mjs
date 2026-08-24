import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import ffmpegPath from 'ffmpeg-static';
import ffprobe from 'ffprobe-static';

const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.resolve(process.argv[2] ?? 'C:/Users/hoang/Downloads/video_2026-08-24_12-22-09.mp4');
const output = path.join(toolRoot, 'public', 'source');
mkdirSync(output, {recursive: true});

const run = (args) => execFileSync(ffmpegPath, ['-hide_banner', '-loglevel', 'error', ...args], {stdio: 'inherit'});

const clip = (name, start, duration, filter) => {
  run([
    '-ss', String(start), '-t', String(duration), '-i', source,
    '-an', '-vf', filter,
    '-r', '30', '-c:v', 'libx264', '-preset', 'slow', '-crf', '22',
    '-bf', '0', '-g', '30', '-keyint_min', '30', '-sc_threshold', '0',
    '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-y', path.join(output, name),
  ]);
};

clip('dual-subtitles.mp4', 1.8, 7, 'scale=582:1280:flags=lanczos');
clip('instant-replay.mp4', 39.8, 3.4, 'scale=582:1280:flags=lanczos');
clip('fullscreen.mp4', 47.2, 7, 'transpose=2,scale=1280:582:flags=lanczos');

run([
  '-ss', '15.8', '-i', source, '-frames:v', '1',
  '-vf', 'scale=582:1280:flags=lanczos', '-q:v', '2', '-y', path.join(output, 'poster-source.jpg'),
]);

const metadata = execFileSync(ffprobe.path, [
  '-v', 'error', '-show_entries', 'format=duration:stream=codec_type,codec_name,width,height,avg_frame_rate',
  '-of', 'json', source,
], {encoding: 'utf8'});
console.log(`Prepared source clips from ${source}`);
console.log(metadata.trim());
