import React from 'react';
import {
  AbsoluteFill,
  Img,
  interpolate,
  OffthreadVideo,
  Sequence,
  staticFile,
  useCurrentFrame,
} from 'remotion';
import {PhoneShell, PracticeScreen, ProgressScreen, WordLookupScreen} from './AppScreens';
import {base, clamp, colors, fade} from './theme';

export type PromoProps = {repositoryUrl: string};

const BrandMark: React.FC<{size?: number}> = ({size = 120}) => (
  <div style={{width: size, height: size, borderRadius: size * 0.28, background: colors.primary, display: 'grid', placeItems: 'center', boxShadow: `0 0 ${size * 0.7}px #13C6D766`}}>
    <div style={{width: size * 0.54, height: size * 0.42, borderRadius: size * 0.12, background: colors.background, position: 'relative'}}>
      <div style={{position: 'absolute', left: '42%', top: '25%', width: 0, height: 0, borderTop: `${size * 0.11}px solid transparent`, borderBottom: `${size * 0.11}px solid transparent`, borderLeft: `${size * 0.17}px solid ${colors.primary}`}} />
    </div>
  </div>
);

const Background: React.FC = () => (
  <AbsoluteFill style={{...base, backgroundImage: 'radial-gradient(circle at 50% 28%, #0F4248 0%, #061416 48%, #020A0B 100%)'}}>
    <div style={{position: 'absolute', inset: 0, opacity: 0.18, backgroundImage: 'linear-gradient(#13C6D722 1px, transparent 1px), linear-gradient(90deg, #13C6D722 1px, transparent 1px)', backgroundSize: '72px 72px'}} />
  </AbsoluteFill>
);

const Pill: React.FC<{children: React.ReactNode}> = ({children}) => (
  <div style={{padding: '18px 32px', borderRadius: 999, border: `2px solid ${colors.primary}88`, background: '#0C2023DD', color: colors.secondary, fontSize: 28, fontWeight: 700, letterSpacing: 1.2, textTransform: 'uppercase'}}>{children}</div>
);

const RecordedScreen: React.FC<{src: string; playbackRate?: number}> = ({src, playbackRate = 1}) => (
  <OffthreadVideo src={staticFile(src)} muted playbackRate={playbackRate} style={{width: '100%', height: '100%', objectFit: 'cover'}} />
);

const FeatureScene: React.FC<{duration: number; eyebrow: string; title: string; detail: string; zoom?: boolean; children: React.ReactNode}> = ({duration, eyebrow, title, detail, zoom, children}) => {
  const frame = useCurrentFrame();
  const progress = interpolate(frame, [0, duration], [0, 1], clamp);
  const phoneScale = zoom ? interpolate(progress, [0, 0.55, 1], [0.91, 1.04, 1.08]) : interpolate(progress, [0, 1], [0.94, 1]);
  return (
    <AbsoluteFill style={{...base, opacity: fade(frame, duration)}}>
      <Background />
      <div style={{position: 'absolute', top: 76, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}><Pill>{eyebrow}</Pill></div>
      <div style={{position: 'absolute', top: 170, left: 90, right: 90, textAlign: 'center'}}>
        <div style={{fontSize: 70, fontWeight: 900, lineHeight: 1.02}}>{title}</div>
        <div style={{fontSize: 31, color: colors.muted, marginTop: 18}}>{detail}</div>
      </div>
      <div style={{position: 'absolute', top: 370, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}>
        <PhoneShell scale={phoneScale}>{children}</PhoneShell>
      </div>
    </AbsoluteFill>
  );
};

const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const lift = interpolate(frame, [0, 40], [60, 0], clamp);
  return (
    <AbsoluteFill style={{...base, opacity: fade(frame, 90), alignItems: 'center', justifyContent: 'center', textAlign: 'center'}}>
      <Background />
      <div style={{transform: `translateY(${lift}px)`, display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
        <BrandMark size={170} />
        <div style={{fontSize: 84, fontWeight: 900, marginTop: 62, lineHeight: 1.05}}>Learn from YouTube,<br /><span style={{color: colors.primary}}>one sentence at a time.</span></div>
        <div style={{fontSize: 34, color: colors.muted, marginTop: 42}}>Dual subtitles, instant replay and word lookup on Android</div>
      </div>
    </AbsoluteFill>
  );
};

const FullscreenScene: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 50, duration], [0.82, 0.94, 1], clamp);
  return (
    <AbsoluteFill style={{...base, opacity: fade(frame, duration)}}>
      <Background />
      <div style={{position: 'absolute', top: 92, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}><Pill>Immersive learning</Pill></div>
      <div style={{position: 'absolute', top: 190, left: 60, right: 60, textAlign: 'center'}}>
        <div style={{fontSize: 68, fontWeight: 900}}>Optimized fullscreen mode</div>
        <div style={{fontSize: 31, color: colors.muted, marginTop: 18}}>More video. Clear subtitles. Fewer distractions.</div>
      </div>
      <div style={{position: 'absolute', top: 570, left: '50%', width: 1020, height: 464, transform: `translateX(-50%) scale(${scale})`, borderRadius: 42, padding: 13, background: '#020708', border: `3px solid ${colors.surfaceVariant}`, boxShadow: '0 30px 100px #000D, 0 0 90px #13C6D744', overflow: 'hidden'}}>
        <OffthreadVideo src={staticFile('source/fullscreen.mp4')} muted style={{width: '100%', height: '100%', objectFit: 'cover', borderRadius: 30}} />
      </div>
      <div style={{position: 'absolute', top: 1160, left: 110, right: 110, display: 'flex', gap: 24, justifyContent: 'center'}}>
        {['Edge-to-edge', 'Auto landscape', 'Replay controls'].map((label) => <div key={label} style={{background: colors.surface, border: `2px solid ${colors.surfaceVariant}`, borderRadius: 24, padding: '22px 24px', fontSize: 25, color: colors.secondary}}>{label}</div>)}
      </div>
    </AbsoluteFill>
  );
};

const EndCard: React.FC<PromoProps> = ({repositoryUrl}) => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 35], [0.8, 1], clamp);
  const chips = ['On-device translation', '59 languages', 'No account', 'No ads'];
  return (
    <AbsoluteFill style={{...base, alignItems: 'center', justifyContent: 'center', textAlign: 'center'}}>
      <Background />
      <div style={{transform: `scale(${scale})`, display: 'flex', alignItems: 'center', flexDirection: 'column'}}>
        <BrandMark size={180} />
        <div style={{fontSize: 105, fontWeight: 900, marginTop: 48}}>DualSub <span style={{color: colors.primary}}>Replay</span></div>
        <div style={{fontSize: 38, color: colors.secondary, marginTop: 24}}>Free&nbsp;&nbsp;•&nbsp;&nbsp;Open source&nbsp;&nbsp;•&nbsp;&nbsp;Android</div>
        <div style={{display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: 20, width: 1000, marginTop: 56}}>
          {chips.map((label, index) => (
            <div key={label} style={{opacity: interpolate(frame, [20 + index * 6, 32 + index * 6], [0, 1], clamp), background: colors.surface, border: `2px solid ${colors.surfaceVariant}`, borderRadius: 24, padding: '18px 26px', fontSize: 28, color: colors.secondary}}>{label}</div>
          ))}
        </div>
        <div style={{marginTop: 64, fontSize: 34, fontWeight: 700}}>Download the free APK on GitHub</div>
        <div style={{marginTop: 22, padding: '24px 36px', borderRadius: 22, background: colors.surfaceVariant, color: colors.text, fontSize: 29, fontWeight: 700}}>{repositoryUrl}</div>
      </div>
    </AbsoluteFill>
  );
};

/** Scene lengths in frames at 30 fps; PROMO_FRAMES is the composition length. */
const scenes = {intro: 90, dual: 180, replay: 210, words: 300, practice: 210, fullscreen: 180, progress: 180, end: 180};
export const PROMO_FRAMES = Object.values(scenes).reduce((sum, length) => sum + length, 0);

export const PromoVideo: React.FC<PromoProps> = (props) => {
  let at = 0;
  const next = (length: number) => {
    const from = at;
    at += length;
    return {from, durationInFrames: length};
  };
  return (
    <AbsoluteFill style={base}>
      <Sequence {...next(scenes.intro)}><Intro /></Sequence>
      <Sequence {...next(scenes.dual)}>
        <FeatureScene duration={scenes.dual} eyebrow="See every meaning" title="Dual subtitles" detail="Original and translated captions stay together.">
          <RecordedScreen src="source/dual-subtitles.mp4" />
        </FeatureScene>
      </Sequence>
      <Sequence {...next(scenes.replay)}>
        <FeatureScene duration={scenes.replay} eyebrow="Tap. Listen. Repeat." title="Replay a sentence instantly" detail="Jump back to the exact moment with one tap." zoom>
          <RecordedScreen src="source/instant-replay.mp4" playbackRate={0.425} />
        </FeatureScene>
      </Sequence>
      <Sequence {...next(scenes.words)}>
        <FeatureScene duration={scenes.words} eyebrow="Tap any word" title="Look up words and grammar" detail="See the meaning, hear it, and save it for later.">
          <WordLookupScreen />
        </FeatureScene>
      </Sequence>
      <Sequence {...next(scenes.practice)}>
        <FeatureScene duration={scenes.practice} eyebrow="Remember what you hear" title="Practice saved words" detail="Spaced-repetition reviews with the original sentence.">
          <PracticeScreen />
        </FeatureScene>
      </Sequence>
      <Sequence {...next(scenes.fullscreen)}><FullscreenScene duration={scenes.fullscreen} /></Sequence>
      <Sequence {...next(scenes.progress)}>
        <FeatureScene duration={scenes.progress} eyebrow="Build a daily habit" title="Track your progress" detail="Daily goal, streak, and time per language.">
          <ProgressScreen />
        </FeatureScene>
      </Sequence>
      <Sequence {...next(scenes.end)}><EndCard {...props} /></Sequence>
    </AbsoluteFill>
  );
};

/** The README GIF: real dual-subtitle footage, then the word lookup. */
export const README_LOOP_FRAMES = 420;
const LOOP_SWITCH = 180;

export const ReadmeLoop: React.FC<PromoProps> = () => {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [0, 12, README_LOOP_FRAMES - 14, README_LOOP_FRAMES - 1], [0, 1, 1, 0], clamp);
  const swap = interpolate(frame, [LOOP_SWITCH - 8, LOOP_SWITCH + 8], [0, 1], clamp);
  const phoneScale = 0.52;
  const heading = (first: string, second: string, visible: number) => (
    <div style={{position: 'absolute', top: 36, left: 0, right: 0, textAlign: 'center', fontSize: 42, fontWeight: 900, lineHeight: 1.12, opacity: visible}}>
      {first}<br /><span style={{color: colors.primary}}>{second}</span>
    </div>
  );
  return (
    <AbsoluteFill style={{...base, opacity, overflow: 'hidden'}}>
      <Background />
      {heading('Dual subtitles.', 'Tap to replay.', 1 - swap)}
      {heading('Tap any word', 'to learn it.', swap)}
      <div style={{position: 'absolute', top: 160, left: (540 - 690 * phoneScale) / 2, transform: `scale(${phoneScale})`, transformOrigin: 'top left'}}>
        <PhoneShell>
          <Sequence durationInFrames={LOOP_SWITCH + 8} layout="none"><RecordedScreen src="source/dual-subtitles.mp4" /></Sequence>
          <Sequence from={LOOP_SWITCH - 8} layout="none">
            <AbsoluteFill style={{opacity: swap}}><WordLookupScreen /></AbsoluteFill>
          </Sequence>
        </PhoneShell>
      </div>
    </AbsoluteFill>
  );
};

export const PromoPoster: React.FC<PromoProps> = ({repositoryUrl}) => (
  <AbsoluteFill style={base}>
    <Background />
    <div style={{position: 'absolute', top: 90, left: 80, right: 80, textAlign: 'center'}}>
      <div style={{fontSize: 78, fontWeight: 900}}>Dual subtitles.<br /><span style={{color: colors.primary}}>Replay any sentence.</span></div>
      <div style={{fontSize: 31, color: colors.muted, marginTop: 25}}>Learn languages naturally with YouTube on Android.</div>
    </div>
    <div style={{position: 'absolute', top: 440, left: '50%', transform: 'translateX(-50%)', width: 622, height: 1368, borderRadius: 62, padding: 14, background: '#020708', border: `3px solid ${colors.surfaceVariant}`, boxShadow: '0 30px 100px #000D, 0 0 90px #13C6D744', overflow: 'hidden'}}>
      <Img src={staticFile('source/poster-source.jpg')} style={{width: '100%', height: '100%', objectFit: 'cover', borderRadius: 48}} />
    </div>
    <div style={{position: 'absolute', bottom: 42, left: 0, right: 0, textAlign: 'center', color: colors.secondary, fontSize: 24}}>{repositoryUrl}</div>
  </AbsoluteFill>
);

export const SocialCard: React.FC<PromoProps> = () => (
  <AbsoluteFill style={{...base, backgroundImage: 'radial-gradient(circle at 77% 42%, #13505A 0%, #061416 48%, #020A0B 100%)'}}>
    <div style={{position: 'absolute', left: 68, top: 72}}><BrandMark size={92} /></div>
    <div style={{position: 'absolute', left: 68, top: 195, width: 680}}>
      <div style={{fontSize: 72, lineHeight: 1, fontWeight: 900}}>DualSub <span style={{color: colors.primary}}>Replay</span></div>
      <div style={{fontSize: 39, lineHeight: 1.18, fontWeight: 700, marginTop: 28}}>Dual subtitles.<br />Tap any sentence to replay.</div>
      <div style={{fontSize: 24, color: colors.muted, marginTop: 34}}>Free, open-source Android language learning</div>
    </div>
    <div style={{position: 'absolute', right: 80, top: 25, width: 250, height: 550, borderRadius: 34, padding: 7, background: '#020708', border: `2px solid ${colors.surfaceVariant}`, boxShadow: '0 20px 70px #000D, 0 0 60px #13C6D744', overflow: 'hidden', transform: 'rotate(3deg)'}}>
      <Img src={staticFile('source/poster-source.jpg')} style={{width: '100%', height: '100%', objectFit: 'cover', borderRadius: 27}} />
    </div>
  </AbsoluteFill>
);
