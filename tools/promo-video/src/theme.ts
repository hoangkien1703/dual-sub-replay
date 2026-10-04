import type React from 'react';
import {continueRender, delayRender, interpolate} from 'remotion';
import '@fontsource/roboto/400.css';
import '@fontsource/roboto/500.css';
import '@fontsource/roboto/700.css';
import '@fontsource/roboto/900.css';
import '@fontsource/noto-sans-jp/400.css';
import '@fontsource/noto-sans-jp/700.css';

// Roboto is the Android system font the app renders with; Noto Sans JP covers the
// Japanese subtitle lines. Both are bundled from pinned @fontsource packages, so renders
// match on Windows, macOS and Linux and need no font download while rendering.
export const fontFamily = 'Roboto, "Noto Sans JP", sans-serif';

/** Every Japanese character the scenes draw, so their font subsets load before frame 0. */
export const japaneseText = '山梨県はワインの生産量が日本一ですこのスパークリング、美味しいんよでは一升瓶売っていま';

const fontsReady = delayRender('Loading fonts');
Promise.all([
  ...['400', '500', '700', '900'].map((weight) => document.fonts.load(`${weight} 32px Roboto`)),
  ...['400', '700'].map((weight) => document.fonts.load(`${weight} 32px "Noto Sans JP"`, japaneseText)),
]).then(() => continueRender(fontsReady), (error) => {
  console.error(error);
  continueRender(fontsReady);
});

// Mirrors the dark color scheme in app/src/main/java/.../ui/theme/Theme.kt.
export const colors = {
  background: '#061416',
  surface: '#0C2023',
  surfaceVariant: '#173438',
  surfaceContainer: '#0E2427',
  surfaceContainerHigh: '#132D31',
  surfaceContainerHighest: '#1A383C',
  primaryContainer: '#0F3F45',
  outlineVariant: '#2A4649',
  primary: '#13C6D7',
  onPrimary: '#001F23',
  secondary: '#8CD9E2',
  translated: '#7FD8DF',
  text: '#E4F5F6',
  muted: '#B8CDD0',
  faint: '#6F8B8F',
};

export const base: React.CSSProperties = {
  backgroundColor: colors.background,
  color: colors.text,
  fontFamily,
};

export const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

/** 0 → 1 over [start, start + length], clamped. */
export const ramp = (frame: number, start: number, length = 10) => interpolate(frame, [start, start + length], [0, 1], clamp);

/** Fades a scene in and out over its first and last 12 frames. */
export const fade = (frame: number, duration: number) => interpolate(frame, [0, 12, duration - 12, duration], [0, 1, 1, 0], clamp);
