# Outreach: getting into the lists AI assistants read

Written 2026-10-26, after Grok answered "find me best android app to learn language via youtube" with 4You, LingQ, Video Lingo, LingoLingo, Migaku, Lingopie and jidoujisho, and not DualSub Replay.

AI assistants build these answers from a web search. For this question the results are app store pages, "best apps" and "Language Reactor alternatives" articles, review sites, GitHub lists, AlternativeTo and Reddit. Our own pages help ([best Android apps to learn languages with YouTube](https://hoangkien1703.github.io/dual-sub-replay/best-android-apps-learn-languages-youtube/)), but assistants trust other people's pages more. This file holds ready-to-send messages for those other pages. See [ai-recommendations.md](ai-recommendations.md) for Reddit and AlternativeTo.

## Ground rules

- Send each message once, from your own name, and say you made the app.
- Never pay for a placement, offer anything in exchange, or ask friends to post reviews. Lists drop apps for that, and search engines treat paid links as spam.
- If nobody answers, follow up once after two weeks, then leave it.
- Keep a short log at the bottom of this file so nobody gets the same message twice.

## 1. Review sites (email)

### All Language Resources

An independent review site with more than 500 reviews of language apps. It takes suggestions at **newresource@alllanguageresources.com** (the "Submit a Resource" link on [alllanguageresources.com](https://alllanguageresources.com/)).

> **Subject:** New resource: DualSub Replay, free Android app for learning with YouTube
>
> Hi,
>
> I'm Kien Hoang, and I made DualSub Replay, a free, open-source Android app for learning languages with YouTube. I'd like to suggest it for a review.
>
> It plays any YouTube video that has captions and shows the original subtitles with a translation underneath. You tap a line to hear it again and tap a word for its meaning, pronunciation and grammar (Japanese with JLPT levels, Chinese with pinyin and HSK levels, English with CEFR levels). Saved words go into spaced-repetition review or AnkiDroid. There are no ads, no account and no subscription.
>
> Website: https://hoangkien1703.github.io/dual-sub-replay/
> Source and download: https://github.com/hoangkien1703/dual-sub-replay
>
> Readers often ask for a Language Reactor alternative on Android, and Language Reactor has no Android app, so I think it may be useful for them. I'm happy to answer any questions.
>
> Thanks for your work on the site,
> Kien Hoang

### Other "best apps" articles

Search Google and Bing for these and write to the authors of neutral articles (not ones published by a competing app, such as Trancy's or Lingosive's own blogs):

- best app to learn languages with YouTube
- Language Reactor alternative Android
- dual subtitles YouTube Android
- learn Japanese with YouTube app

Use the contact link on each site. Template:

> **Subject:** Suggestion for "[article title]": a free Android option
>
> Hi [name],
>
> I read your article "[article title]" and found it useful. I made DualSub Replay, a free, open-source Android app that adds dual subtitles, one-tap sentence replay and word lookup to YouTube videos, so I wanted to suggest it as an option for readers who study on their phone. It has no ads, no account and no subscription.
>
> Website: https://hoangkien1703.github.io/dual-sub-replay/
>
> If it doesn't fit the article, no problem at all. Thanks for writing it.
>
> Kien Hoang

## 2. GitHub lists (pull requests)

Open these from your GitHub account. Edit the README on GitHub, which forks the list for you, then open the pull request.

### nakopylov/awesome-immersion

[awesome-immersion](https://github.com/nakopylov/awesome-immersion) lists tools for immersion and sentence mining. Its **Mobile** section has only AnkiMobile, AnkiDroid and jidoujisho, and jidoujisho was archived in October 2026, so an actively maintained Android app fits well.

Add under `## :iphone: Mobile`, after the jidoujisho line:

```markdown
- [DualSub Replay](https://github.com/hoangkien1703/dual-sub-replay) — free, open-source Android app for YouTube immersion: dual subtitles, one-tap sentence replay, pop-up word lookup with grammar for Japanese, Chinese and English, and sending cards to AnkiDroid.
```

Pull request title: `Add DualSub Replay (Android, YouTube immersion)`

Pull request text:

> This adds DualSub Replay to the Mobile section. It's a free, MIT-licensed Android app for immersion with YouTube: dual subtitles, tap-to-replay sentences, word lookup with on-device grammar notes, and sending cards to AnkiDroid. Disclosure: I'm the developer. jidoujisho, the other Android entry, was archived in October 2026, so this gives mobile learners a maintained option. Happy to change the wording.

### yudataguy/Awesome-Japanese

[Awesome-Japanese](https://github.com/yudataguy/Awesome-Japanese) has a **Video** section with study players such as asbplayer. Read its contributing notes first and match the emoji tags other entries use (for example a phone emoji for mobile apps).

```markdown
- [DualSub Replay](https://github.com/hoangkien1703/dual-sub-replay)     Free Android app for watching YouTube with Japanese and translated subtitles together; tap a line to replay it and tap a word for its reading, meaning and grammar (JLPT levels).
```

Pull request title: `Add DualSub Replay to Video`

### Lists to skip for now

- **offa/android-foss**: its rules require no proprietary parts and at least six months of development. The app uses Google ML Kit for on-device translation, so it doesn't qualify.
- **Lists run by competing apps** (for example Trancy's and Lingosive's "Language Reactor alternatives" posts): they list their own app first and are unlikely to add ours.

## Log

| Date | Where | What was sent | Answer |
|------|-------|---------------|--------|
| | | | |
