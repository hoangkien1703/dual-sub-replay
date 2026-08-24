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

export type PromoProps = {repositoryUrl: string};

const colors = {
  background: '#061416',
  surface: '#0C2023',
  surfaceVariant: '#173438',
  primary: '#13C6D7',
  secondary: '#8CD9E2',
  text: '#E4F5F6',
  muted: '#B8CDD0',
};

const base: React.CSSProperties = {
  backgroundColor: colors.background,
  color: colors.text,
  fontFamily: 'Arial, Helvetica, sans-serif',
};

const fade = (frame: number, duration: number) =>
  interpolate(frame, [0, 12, duration - 12, duration], [0, 1, 1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

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

const Phone: React.FC<{src: string; playbackRate?: number; scale?: number}> = ({src, playbackRate = 1, scale = 1}) => (
  <div style={{width: 690, height: 1518, borderRadius: 62, padding: 14, background: '#020708', border: `3px solid ${colors.surfaceVariant}`, boxShadow: '0 30px 100px #000C, 0 0 80px #13C6D733', transform: `scale(${scale})`, overflow: 'hidden'}}>
    <OffthreadVideo src={staticFile(src)} muted playbackRate={playbackRate} style={{width: '100%', height: '100%', objectFit: 'cover', borderRadius: 48}} />
  </div>
);

const FeatureScene: React.FC<{duration: number; eyebrow: string; title: string; detail: string; src: string; playbackRate?: number; zoom?: boolean}> = ({duration, eyebrow, title, detail, src, playbackRate, zoom}) => {
  const frame = useCurrentFrame();
  const progress = interpolate(frame, [0, duration], [0, 1], {extrapolateRight: 'clamp'});
  const phoneScale = zoom ? interpolate(progress, [0, 0.55, 1], [0.91, 1.04, 1.08]) : interpolate(progress, [0, 1], [0.94, 1]);
  return (
    <AbsoluteFill style={{...base, opacity: fade(frame, duration)}}>
      <Background />
      <div style={{position: 'absolute', top: 76, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}><Pill>{eyebrow}</Pill></div>
      <div style={{position: 'absolute', top: 170, left: 90, right: 90, textAlign: 'center'}}>
        <div style={{fontSize: 70, fontWeight: 800, lineHeight: 1.02}}>{title}</div>
        <div style={{fontSize: 31, color: colors.muted, marginTop: 18}}>{detail}</div>
      </div>
      <div style={{position: 'absolute', top: 370, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}>
        <Phone src={src} playbackRate={playbackRate} scale={phoneScale} />
      </div>
    </AbsoluteFill>
  );
};

const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const lift = interpolate(frame, [0, 40], [60, 0], {extrapolateRight: 'clamp'});
  return (
    <AbsoluteFill style={{...base, opacity: fade(frame, 90), alignItems: 'center', justifyContent: 'center', textAlign: 'center'}}>
      <Background />
      <div style={{transform: `translateY(${lift}px)`, display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
        <BrandMark size={170} />
        <div style={{fontSize: 84, fontWeight: 850, marginTop: 62, lineHeight: 1.05}}>Learn from YouTube,<br /><span style={{color: colors.primary}}>one sentence at a time.</span></div>
        <div style={{fontSize: 34, color: colors.muted, marginTop: 42}}>Dual subtitles and instant replay on Android</div>
      </div>
    </AbsoluteFill>
  );
};

const FullscreenScene: React.FC = () => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 50, 210], [0.82, 0.94, 1], {extrapolateRight: 'clamp'});
  return (
    <AbsoluteFill style={{...base, opacity: fade(frame, 210)}}>
      <Background />
      <div style={{position: 'absolute', top: 92, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}><Pill>Immersive learning</Pill></div>
      <div style={{position: 'absolute', top: 190, left: 60, right: 60, textAlign: 'center'}}>
        <div style={{fontSize: 68, fontWeight: 800}}>Optimized fullscreen mode</div>
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
  const scale = interpolate(frame, [0, 35], [0.8, 1], {extrapolateRight: 'clamp'});
  return (
    <AbsoluteFill style={{...base, alignItems: 'center', justifyContent: 'center', textAlign: 'center'}}>
      <Background />
      <div style={{transform: `scale(${scale})`, display: 'flex', alignItems: 'center', flexDirection: 'column'}}>
        <BrandMark size={180} />
        <div style={{fontSize: 105, fontWeight: 850, marginTop: 48}}>DualSub <span style={{color: colors.primary}}>Replay</span></div>
        <div style={{fontSize: 38, color: colors.secondary, marginTop: 24}}>Free&nbsp;&nbsp;•&nbsp;&nbsp;Open source&nbsp;&nbsp;•&nbsp;&nbsp;Android</div>
        <div style={{marginTop: 72, padding: '24px 36px', borderRadius: 22, background: colors.surfaceVariant, color: colors.text, fontSize: 29, fontWeight: 700}}>{repositoryUrl}</div>
      </div>
    </AbsoluteFill>
  );
};

export const PromoVideo: React.FC<PromoProps> = (props) => (
  <AbsoluteFill style={base}>
    <Sequence from={0} durationInFrames={90}><Intro /></Sequence>
    <Sequence from={90} durationInFrames={210}><FeatureScene duration={210} eyebrow="See every meaning" title="Dual subtitles" detail="Original and translated captions stay together." src="source/dual-subtitles.mp4" /></Sequence>
    <Sequence from={300} durationInFrames={240}><FeatureScene duration={240} eyebrow="Tap. Listen. Repeat." title="Replay a sentence instantly" detail="Jump back to the exact moment with one tap." src="source/instant-replay.mp4" playbackRate={0.425} zoom /></Sequence>
    <Sequence from={540} durationInFrames={210}><FullscreenScene /></Sequence>
    <Sequence from={750} durationInFrames={150}><EndCard {...props} /></Sequence>
  </AbsoluteFill>
);

export const ReadmeLoop: React.FC<PromoProps> = () => {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [0, 16, 220, 239], [0, 1, 1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const scale = interpolate(frame, [0, 239], [0.93, 1.02]);
  return (
    <AbsoluteFill style={{...base, opacity, overflow: 'hidden'}}>
      <Background />
      <div style={{position: 'absolute', top: 40, left: 0, right: 0, textAlign: 'center', fontSize: 43, fontWeight: 800}}>Dual subtitles.<br /><span style={{color: colors.primary}}>Tap to replay.</span></div>
      <div style={{position: 'absolute', top: 190, left: '50%', transform: `translateX(-50%) scale(${scale})`}}><Phone src="source/dual-subtitles.mp4" scale={0.63} /></div>
    </AbsoluteFill>
  );
};

export const PromoPoster: React.FC<PromoProps> = ({repositoryUrl}) => (
  <AbsoluteFill style={base}>
    <Background />
    <div style={{position: 'absolute', top: 90, left: 80, right: 80, textAlign: 'center'}}>
      <div style={{fontSize: 78, fontWeight: 850}}>Dual subtitles.<br /><span style={{color: colors.primary}}>Replay any sentence.</span></div>
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
      <div style={{fontSize: 72, lineHeight: 1, fontWeight: 850}}>DualSub <span style={{color: colors.primary}}>Replay</span></div>
      <div style={{fontSize: 39, lineHeight: 1.18, fontWeight: 700, marginTop: 28}}>Dual subtitles.<br />Tap any sentence to replay.</div>
      <div style={{fontSize: 24, color: colors.muted, marginTop: 34}}>Free, open-source Android language learning</div>
    </div>
    <div style={{position: 'absolute', right: 80, top: 25, width: 250, height: 550, borderRadius: 34, padding: 7, background: '#020708', border: `2px solid ${colors.surfaceVariant}`, boxShadow: '0 20px 70px #000D, 0 0 60px #13C6D744', overflow: 'hidden', transform: 'rotate(3deg)'}}>
      <Img src={staticFile('source/poster-source.jpg')} style={{width: '100%', height: '100%', objectFit: 'cover', borderRadius: 27}} />
    </div>
  </AbsoluteFill>
);
