import React from 'react';
import {AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {clamp, colors, ramp} from './theme';

// Animated recreations of in-app screens that the 2026-08 phone recording predates.
// Text comes from app/src/main/res/values/strings_*.xml and colors from ui/theme/Theme.kt,
// so the scenes show the real UI and wording rather than invented features.

/** Compose dp → px inside the 662 px wide phone screen. */
const dp = (value: number) => value * 2.1;
/** The Progress screen holds more rows, so it keeps the density of a typical phone. */
const pdp = (value: number) => value * 1.85;

export const SCREEN_WIDTH = 662;
export const SCREEN_HEIGHT = 1490;

const Icon: React.FC<{path: string; size?: number; color?: string}> = ({path, size = dp(22), color = 'currentColor'}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{display: 'block', flexShrink: 0}}><path d={path} fill={color} /></svg>
);

const icons = {
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  gear: 'M19.14 12.94a7.07 7.07 0 0 0 0-1.88l2.03-1.58a.5.5 0 0 0 .12-.64l-1.92-3.32a.5.5 0 0 0-.6-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54A.5.5 0 0 0 13.9 2.4h-3.84a.5.5 0 0 0-.5.42l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96a.5.5 0 0 0-.6.22L2.67 8.88a.5.5 0 0 0 .12.64l2.03 1.58a7.07 7.07 0 0 0 0 1.88l-2.03 1.58a.5.5 0 0 0-.12.64l1.92 3.32c.13.22.39.3.6.22l2.39-.96c.49.38 1.03.7 1.62.94l.36 2.54c.05.24.26.42.5.42h3.84c.25 0 .46-.18.5-.42l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .6-.22l1.92-3.32a.5.5 0 0 0-.12-.64zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z',
  volume: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z',
  bookmarkAdd: 'M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2zm-1 8h-3v3h-2v-3H8V9h3V6h2v3h3v2z',
  bookmarkAdded: 'M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2zm-6.5 12-3.5-3.5 1.41-1.41 2.09 2.08 5.09-5.08L17 8.5z',
  play: 'M8 5v14l11-7z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
};

/** A finger tap: a ring that grows and fades from the touched point. */
export const Tap: React.FC<{at: number; size?: number}> = ({at, size = 110}) => {
  const frame = useCurrentFrame();
  if (frame < at - 6 || frame > at + 18) return null;
  const press = interpolate(frame, [at - 6, at, at + 18], [0.55, 0.8, 1.35], clamp);
  const opacity = interpolate(frame, [at - 6, at - 2, at + 6, at + 18], [0, 0.9, 0.7, 0], clamp);
  return (
    <div style={{position: 'absolute', left: '50%', top: '50%', width: size, height: size, marginLeft: -size / 2, marginTop: -size / 2, borderRadius: '50%', background: '#FFFFFF55', border: '4px solid #FFFFFFCC', transform: `scale(${press})`, opacity, pointerEvents: 'none', zIndex: 50}} />
  );
};

const StatusBar: React.FC = () => (
  <div style={{height: 46, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 34px', fontSize: 22, color: colors.text, fontWeight: 500}}>
    <span>11:36</span>
    <span style={{display: 'flex', gap: 8, alignItems: 'center'}}>
      <span style={{width: 26, height: 14, borderRadius: 3, border: `2px solid ${colors.text}`, position: 'relative'}}><span style={{position: 'absolute', inset: 2, right: 6, background: colors.text, borderRadius: 1}} /></span>
    </span>
  </div>
);

/** The phone body around an app screen built from HTML instead of a screen recording. */
export const PhoneShell: React.FC<{children: React.ReactNode; scale?: number}> = ({children, scale = 1}) => (
  <div style={{width: SCREEN_WIDTH + 28, height: SCREEN_HEIGHT + 28, borderRadius: 62, padding: 14, background: '#020708', border: `3px solid ${colors.surfaceVariant}`, boxShadow: '0 30px 100px #000C, 0 0 80px #13C6D733', transform: `scale(${scale})`}}>
    <div style={{position: 'relative', width: SCREEN_WIDTH, height: SCREEN_HEIGHT, borderRadius: 48, overflow: 'hidden', background: colors.background}}>{children}</div>
  </div>
);

const TextButton: React.FC<{children: React.ReactNode; tapAt?: number; color?: string}> = ({children, tapAt, color = colors.primary}) => (
  <div style={{position: 'relative', padding: `${dp(9)}px ${dp(11)}px`, color, fontSize: dp(14), fontWeight: 500, letterSpacing: 0.3}}>
    {children}
    {tapAt !== undefined && <Tap at={tapAt} size={90} />}
  </div>
);

const lines = [
  {before: '山梨県は', word: 'ワイン', after: 'の生産量が日本一です', translated: 'Yamanashi Prefecture is the best of wine production'},
  {before: 'このスパークリングワイン、美味しいんですよ', translated: 'This sparkling wine is delicious'},
  {before: '山梨では、一升瓶ワインが売っています', translated: 'In Yamanashi, a bottle wine is sold'},
];

/** Original and translated line in a replayable card, like CompactSubtitleCard. */
const SubtitleCard: React.FC<{active?: boolean; children: React.ReactNode; translated: string; overlay?: React.ReactNode}> = ({active, children, translated, overlay}) => (
  <div style={{position: 'relative', display: 'flex', gap: dp(12), alignItems: 'center', padding: `${dp(10)}px ${dp(12)}px`, borderRadius: dp(14), background: active ? colors.primaryContainer : colors.surface, border: `${active ? 3 : 2}px solid ${active ? colors.primary : colors.surfaceVariant}`}}>
    <div style={{width: dp(34), height: dp(34), borderRadius: '50%', flexShrink: 0, display: 'grid', placeItems: 'center', background: active ? colors.primary : colors.surfaceVariant}}>
      <Icon path={icons.play} size={dp(20)} color={active ? colors.onPrimary : colors.text} />
    </div>
    <div style={{flex: 1}}>
      <div style={{fontSize: dp(19), lineHeight: 1.32, color: colors.text}}>{children}</div>
      <div style={{fontSize: dp(14), lineHeight: 1.3, color: colors.translated, marginTop: dp(3)}}>{translated}</div>
    </div>
    {overlay}
  </div>
);

/** The single YouTube page on top, the dual-subtitle panel below it. */
const WatchScreen: React.FC<{selectedFrom?: number; tapWordAt?: number; selectionBar?: React.ReactNode}> = ({selectedFrom, tapWordAt, selectionBar}) => {
  const frame = useCurrentFrame();
  const selected = selectedFrom !== undefined && frame >= selectedFrom;
  return (
    <AbsoluteFill>
      <StatusBar />
      <Img src={staticFile('source/japanese-page.png')} style={{width: SCREEN_WIDTH, height: 387, display: 'block'}} />
      <div style={{display: 'flex', justifyContent: 'center', padding: '12px 0 4px'}}><div style={{width: 64, height: 7, borderRadius: 4, background: colors.faint}} /></div>
      <div style={{display: 'flex', alignItems: 'center', gap: dp(12), padding: `${dp(6)}px ${dp(14)}px ${dp(10)}px`}}>
        <div style={{width: dp(26), height: dp(26), borderRadius: dp(5), background: colors.primary, color: colors.onPrimary, fontSize: dp(11), fontWeight: 900, display: 'grid', placeItems: 'center'}}>CC</div>
        <div style={{flex: 1}}>
          <div style={{fontSize: dp(15), fontWeight: 500}}>Japanese  →  English</div>
          <div style={{fontSize: dp(12), color: colors.muted}}>Translating 64 of 116…</div>
        </div>
        <Icon path={icons.gear} color={colors.text} />
        <div style={{width: dp(8)}} />
        <Icon path={icons.close} color={colors.text} />
      </div>
      <div style={{display: 'flex', flexDirection: 'column', gap: dp(10), padding: `0 ${dp(10)}px`}}>
        {lines.map((line, index) => (
          <SubtitleCard key={line.before} active={index === 0} translated={line.translated} overlay={index === 0 && selected ? selectionBar : undefined}>
            {line.before}
            {line.word && (
              <span style={{position: 'relative', background: selected ? '#13C6D761' : 'transparent', borderRadius: 4}}>
                {line.word}
                {tapWordAt !== undefined && <Tap at={tapWordAt} />}
              </span>
            )}
            {line.after}
          </SubtitleCard>
        ))}
      </div>
    </AbsoluteFill>
  );
};

/** The action bar that floats above a selected subtitle word, like PhraseActionBar. */
const SelectionBar: React.FC<{shownAt: number; tapTranslateAt: number}> = ({shownAt, tapTranslateAt}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const pop = spring({frame: frame - shownAt, fps, config: {damping: 16, stiffness: 180}});
  const translated = frame >= shownAt + 8;
  return (
    <div style={{position: 'absolute', bottom: `calc(100% - ${dp(2)}px)`, left: dp(24), right: dp(4), transform: `scale(${0.8 + 0.2 * pop})`, transformOrigin: '45% 100%', opacity: pop, zIndex: 40, background: colors.surfaceContainerHighest, border: '2px solid #13C6D773', borderRadius: dp(20), boxShadow: '0 16px 40px #000A', padding: `${dp(2)}px ${dp(6)}px`, fontSize: dp(14)}}>
      <div style={{display: 'flex', alignItems: 'center'}}>
        <TextButton>Copy</TextButton>
        <TextButton tapAt={tapTranslateAt}>Translate</TextButton>
        <TextButton>Pronounce</TextButton>
        <div style={{marginLeft: 'auto', padding: dp(8)}}><Icon path={icons.close} size={dp(18)} color={colors.text} /></div>
      </div>
      <div style={{padding: `0 ${dp(12)}px ${dp(6)}px`, fontSize: dp(16), fontWeight: 500, color: translated ? colors.text : colors.muted}}>{translated ? 'wine' : 'Translating…'}</div>
      <div style={{padding: `0 ${dp(12)}px ${dp(6)}px`, fontSize: dp(12), color: colors.muted}}>Tap another word to select a phrase</div>
    </div>
  );
};

/** WordLearningDialog: meaning, part of speech, grammar of the next particle, Save to vocabulary. */
const WordCard: React.FC<{shownAt: number; tapSaveAt: number}> = ({shownAt, tapSaveAt}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const pop = spring({frame: frame - shownAt, fps, config: {damping: 18, stiffness: 160}});
  const loaded = frame >= shownAt + 18;
  const saved = frame >= tapSaveAt + 4;
  const savedNote = ramp(frame, tapSaveAt + 8, 8);
  return (
    <AbsoluteFill style={{background: `rgba(0,0,0,${0.55 * pop})`, alignItems: 'center', justifyContent: 'center', zIndex: 60}}>
      <div style={{width: SCREEN_WIDTH - dp(28), borderRadius: dp(24), background: colors.surfaceContainerHigh, padding: `${dp(8)}px ${dp(8)}px ${dp(20)}px ${dp(20)}px`, transform: `scale(${0.85 + 0.15 * pop})`, opacity: pop, boxShadow: '0 30px 80px #000C', fontSize: dp(15)}}>
        <div style={{display: 'flex', alignItems: 'flex-start'}}>
          <div style={{display: 'flex', alignItems: 'center', gap: dp(8), flex: 1, paddingTop: dp(12)}}>
            <span style={{fontSize: dp(24)}}>ワイン</span>
            <div style={{padding: dp(8)}}><Icon path={icons.volume} color={colors.text} /></div>
          </div>
          <div style={{padding: dp(12)}}><Icon path={icons.close} color={colors.text} /></div>
        </div>
        <div style={{paddingRight: dp(12), display: 'flex', flexDirection: 'column', gap: dp(9)}}>
          <div>Noun</div>
          <div style={{height: dp(4), borderRadius: 2, background: colors.surfaceVariant, overflow: 'hidden', opacity: loaded ? 0 : 1}}>
            <div style={{width: `${interpolate(frame, [shownAt, shownAt + 18], [10, 90], clamp)}%`, height: '100%', background: colors.primary}} />
          </div>
          <div style={{position: 'relative', border: `2px solid ${colors.faint}`, borderRadius: dp(5), padding: `${dp(15)}px ${dp(14)}px`, fontSize: dp(16)}}>
            <span style={{position: 'absolute', top: -dp(9), left: dp(10), padding: `0 ${dp(4)}px`, background: colors.surfaceContainerHigh, fontSize: dp(12), color: colors.muted}}>Meaning (en)</span>
            {loaded ? 'wine' : ' '}
          </div>
          <div style={{fontSize: dp(14), fontWeight: 500, marginTop: dp(2)}}>Grammar</div>
          <div style={{background: colors.surfaceContainerHighest, borderRadius: dp(12), padding: `${dp(8)}px ${dp(12)}px`, display: 'flex', gap: dp(8)}}>
            <span style={{color: colors.primary, fontWeight: 700}}>の</span>
            <span>A’s B; B of A</span>
          </div>
          <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
            <span>Online example</span>
            <div style={{width: dp(20), height: dp(20), margin: dp(12), borderRadius: dp(3), background: colors.primary, display: 'grid', placeItems: 'center'}}><Icon path={icons.check} size={dp(17)} color={colors.onPrimary} /></div>
          </div>
          <div style={{position: 'relative', height: dp(42), borderRadius: dp(21), display: 'flex', alignItems: 'center', justifyContent: 'center', gap: dp(8), fontWeight: 500, fontSize: dp(14), background: saved ? '#E4F5F61F' : colors.primary, color: saved ? '#E4F5F680' : colors.onPrimary}}>
            <Icon path={saved ? icons.bookmarkAdded : icons.bookmarkAdd} size={dp(18)} color={saved ? '#E4F5F680' : colors.onPrimary} />
            {saved ? 'Saved' : 'Save to vocabulary'}
            <Tap at={tapSaveAt} />
          </div>
          <div style={{opacity: savedNote, color: colors.secondary}}>Saved to your vocabulary</div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** Tap a subtitle word → selection bar with its meaning → Translate → word card → Save. */
export const WordLookupScreen: React.FC = () => (
  <>
    <WatchScreen selectedFrom={34} tapWordAt={30} selectionBar={<SelectionBar shownAt={36} tapTranslateAt={88} />} />
    <WordCardMount />
  </>
);

const WordCardMount: React.FC = () => {
  const frame = useCurrentFrame();
  return frame >= 96 ? <WordCard shownAt={96} tapSaveAt={190} /> : null;
};

const ratings = ['Again · 10 min', 'Hard · 1 day', 'Good · 3 days', 'Easy · 7 days'];

/** The Practice dialog in SavedWordsScreen during a due-word session. */
export const PracticeScreen: React.FC = () => {
  const frame = useCurrentFrame();
  const revealAt = 50;
  const rateAt = 130;
  const revealed = frame >= revealAt + 4;
  const reveal = ramp(frame, revealAt + 4, 10);
  const done = frame >= rateAt + 10;
  const doneIn = ramp(frame, rateAt + 10, 10);
  return (
    <AbsoluteFill style={{background: colors.background}}>
      <StatusBar />
      <div style={{position: 'absolute', top: 70, left: dp(8), right: dp(8), bottom: 60, borderRadius: dp(16), background: colors.surfaceContainer, padding: dp(16), display: 'flex', flexDirection: 'column', gap: dp(8), fontSize: dp(15)}}>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
          <span style={{fontSize: dp(22)}}>Practice</span>
          <TextButton>Close</TextButton>
        </div>
        {done ? (
          <div style={{opacity: doneIn, display: 'flex', flexDirection: 'column', gap: dp(8)}}>
            <div style={{fontSize: dp(20), color: colors.primary, fontWeight: 500}}>Session complete</div>
            <div>Your reviews are saved. Come back when more words are due.</div>
            <div style={{alignSelf: 'flex-start', marginLeft: -dp(11)}}><TextButton>Back to saved words</TextButton></div>
          </div>
        ) : (
          <>
            <div style={{fontSize: dp(30), marginTop: dp(6)}}>ワイン</div>
            <div style={{alignSelf: 'flex-start', marginLeft: -dp(11)}}><TextButton>Pronounce</TextButton></div>
            {!revealed ? (
              <div style={{position: 'relative', alignSelf: 'flex-start', height: dp(40), padding: `0 ${dp(24)}px`, borderRadius: dp(20), background: colors.primary, color: colors.onPrimary, display: 'flex', alignItems: 'center', fontWeight: 500, fontSize: dp(14)}}>
                Show meaning
                <Tap at={revealAt} />
              </div>
            ) : (
              <div style={{opacity: reveal, display: 'flex', flexDirection: 'column', gap: dp(8)}}>
                <div style={{fontSize: dp(20), color: colors.secondary}}>wine</div>
                <div>山梨県はワインの生産量が日本一です</div>
                <div style={{color: colors.muted}}>Yamanashi Prefecture is the best of wine production</div>
                <div style={{alignSelf: 'flex-start', marginLeft: -dp(11)}}><TextButton>Play online example</TextButton></div>
                {ratings.map((label, index) => (
                  <div key={label} style={{position: 'relative', height: dp(42), borderRadius: dp(21), border: `2px solid ${index === 2 && frame >= rateAt ? colors.primary : colors.faint}`, background: index === 2 && frame >= rateAt ? '#13C6D72A' : 'transparent', color: colors.primary, display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 500, fontSize: dp(14)}}>
                    {label}
                    {index === 2 && <Tap at={rateAt} />}
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </AbsoluteFill>
  );
};

const week = [
  {label: 'Mon', minutes: 22},
  {label: 'Tue', minutes: 31},
  {label: 'Wed', minutes: 18},
  {label: 'Thu', minutes: 26},
  {label: 'Fri', minutes: 40},
  {label: 'Sat', minutes: 35},
  {label: 'Sun', minutes: 24, current: true},
];

/** ProgressScreen: today against the daily goal, streak, totals, week chart and languages. */
export const ProgressScreen: React.FC = () => {
  const frame = useCurrentFrame();
  const todayMinutes = Math.round(interpolate(frame, [10, 60], [11, 24], clamp));
  const goalProgress = Math.min(todayMinutes / 20, 1);
  const reached = todayMinutes >= 20;
  const grow = interpolate(frame, [20, 70], [0, 1], {...clamp, easing: (t) => 1 - (1 - t) ** 3});
  const tile = (label: string, value: string) => (
    <div style={{flex: 1, background: colors.surfaceContainer, borderRadius: pdp(12), padding: pdp(12)}}>
      <div style={{fontSize: pdp(12), color: colors.muted, fontWeight: 500}}>{label}</div>
      <div style={{fontSize: pdp(16), fontWeight: 500, marginTop: pdp(2)}}>{value}</div>
    </div>
  );
  return (
    <AbsoluteFill style={{background: colors.background}}>
      <StatusBar />
      <div style={{position: 'absolute', top: 70, left: pdp(8), right: pdp(8), bottom: 40, borderRadius: pdp(16), background: colors.surface, padding: pdp(16), display: 'flex', flexDirection: 'column', gap: pdp(12), fontSize: pdp(14)}}>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
          <span style={{fontSize: pdp(22)}}>Progress</span>
          <TextButton>Close</TextButton>
        </div>
        <div style={{background: colors.surfaceContainerHigh, borderRadius: pdp(16), padding: pdp(16), display: 'flex', flexDirection: 'column', gap: pdp(6)}}>
          <div style={{fontSize: pdp(14), color: colors.muted, fontWeight: 500}}>Today</div>
          <div style={{fontSize: pdp(28), fontWeight: 600}}>{todayMinutes} min</div>
          <div style={{height: pdp(4), borderRadius: 2, background: colors.surfaceVariant, overflow: 'hidden'}}><div style={{width: `${goalProgress * 100}%`, height: '100%', background: colors.primary}} /></div>
          <div>{reached ? 'Daily goal of 20 min reached' : 'Daily goal: 20 min'}</div>
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
            <span style={{color: colors.primary}}>{reached ? '12-day streak' : '11-day streak'}</span>
            <TextButton>Change goal</TextButton>
          </div>
        </div>
        <div style={{display: 'flex', gap: pdp(8)}}>{tile('This week', '3 h 16 min')}{tile('This month', '11 h 42 min')}</div>
        <div style={{display: 'flex', gap: pdp(8)}}>{tile('This year', '58 h 5 min')}{tile('Total', '61 h 30 min')}</div>
        <div style={{display: 'flex', gap: pdp(8)}}>
          {['Week', 'Month', 'Year', 'All'].map((label, index) => (
            <div key={label} style={{padding: `${pdp(6)}px ${pdp(14)}px`, borderRadius: pdp(8), fontSize: pdp(13), fontWeight: 500, border: `2px solid ${index === 0 ? 'transparent' : colors.outlineVariant}`, background: index === 0 ? '#1B4448' : 'transparent', display: 'flex', gap: pdp(6), alignItems: 'center'}}>
              {index === 0 && <Icon path={icons.check} size={pdp(16)} color={colors.text} />}
              {label}
            </div>
          ))}
        </div>
        <div>
          <div style={{height: pdp(120), display: 'flex', alignItems: 'flex-end', gap: pdp(6)}}>
            {week.map((bar) => (
              <div key={bar.label} style={{flex: 1, height: `${(bar.minutes / 40) * 100 * grow}%`, borderRadius: `${pdp(3)}px ${pdp(3)}px 0 0`, background: bar.current ? colors.primary : '#1B4448'}} />
            ))}
          </div>
          <div style={{display: 'flex', gap: pdp(6), marginTop: pdp(4)}}>
            {week.map((bar) => <div key={bar.label} style={{flex: 1, fontSize: pdp(11), color: bar.current ? colors.primary : colors.muted}}>{bar.label}</div>)}
          </div>
        </div>
        <div style={{fontSize: pdp(16), fontWeight: 500}}>Languages</div>
        {[
          {name: 'Japanese', time: '2 h 5 min', share: 0.64, counts: '9 videos · 34 saved words'},
          {name: 'English', time: '1 h 11 min', share: 0.36, counts: '5 videos · 12 saved words'},
        ].map((language) => (
          <div key={language.name} style={{display: 'flex', flexDirection: 'column', gap: pdp(4)}}>
            <div style={{display: 'flex', justifyContent: 'space-between', fontSize: pdp(16)}}><span>{language.name}</span><span style={{fontWeight: 500}}>{language.time}</span></div>
            <div style={{height: pdp(4), borderRadius: 2, background: colors.surfaceVariant, overflow: 'hidden'}}><div style={{width: `${language.share * 100 * grow}%`, height: '100%', background: colors.primary}} /></div>
            <div style={{fontSize: pdp(12), color: colors.muted}}>{language.counts}</div>
          </div>
        ))}
      </div>
    </AbsoluteFill>
  );
};
