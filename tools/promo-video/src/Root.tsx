import React from 'react';
import {Composition} from 'remotion';
import {PromoPoster, PromoVideo, ReadmeLoop, SocialCard, type PromoProps} from './PromoVideo';

const defaultProps: PromoProps = {
  repositoryUrl: 'github.com/hoangkien1703/dual-sub-replay',
};

export const PromoRoot: React.FC = () => (
  <>
    <Composition id="PromoVideo" component={PromoVideo} durationInFrames={900} fps={30} width={1080} height={1920} defaultProps={defaultProps} />
    <Composition id="ReadmeLoop" component={ReadmeLoop} durationInFrames={240} fps={30} width={540} height={960} defaultProps={defaultProps} />
    <Composition id="PromoPoster" component={PromoPoster} durationInFrames={1} fps={30} width={1080} height={1920} defaultProps={defaultProps} />
    <Composition id="SocialCard" component={SocialCard} durationInFrames={1} fps={30} width={1280} height={640} defaultProps={defaultProps} />
  </>
);
