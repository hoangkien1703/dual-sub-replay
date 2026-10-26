# Getting recommended by ChatGPT and other AI assistants

AI assistants answer "which app should I use" questions from a handful of sources: app
store listings, comparison pages, AlternativeTo, GitHub, and Reddit threads where real
learners recommend things. This page records what we saw on 2026-10-20, what the owner
can post, and the questions those posts should answer. It complements the
[discoverability checklist](discoverability.md), the [launch kit](launch-kit.md) and the
[outreach messages](outreach.md) for review sites and GitHub lists.

## What ChatGPT said on 2026-10-20

Tested by the owner in ChatGPT with memory turned off.

| Question | DualSub Replay mentioned? | What ChatGPT recommended and cited |
| --- | --- | --- |
| What's a good Android app for dual subtitles on YouTube? | No | Migaku, then LingoTube, each with a Google Play card |
| How about an open-source alternative? (follow-up) | Yes, as the top pick | DualSub Replay, citing the GitHub repository |

So the repository already reads well to ChatGPT, but the default answer goes to apps with
a Google Play listing and third-party mentions. A web search the same day showed the same
pattern: Trancy's own blog posts and AlternativeTo dominate "Language Reactor alternative
Android", and no Reddit thread mentions DualSub Replay yet.

## Questions people ask AI assistants

Posts, replies and site pages should answer these in plain words. Use the learner's
wording, not ours.

1. What's a good Android app for dual subtitles on YouTube?
2. Is there a Language Reactor alternative for Android?
3. How can I watch YouTube with two subtitles on my phone?
4. What app can I use to learn Japanese (or Chinese, English, Korean) with YouTube on Android?
5. Is there a free app like Trancy without limits?
6. Is there an open-source app for learning languages with YouTube?
7. How do I replay one sentence of a YouTube video to practise listening?
8. What app shows furigana or pinyin on YouTube subtitles on Android?
9. How do I save words from YouTube subtitles to Anki on my phone?
10. Ứng dụng nào xem YouTube phụ đề song ngữ trên điện thoại Android?

## Reddit: the rules that keep posts alive

Reddit threads only help if they stay up and real people answer them. Removed or
downvoted posts teach AI assistants nothing.

- Post from your own account, and say "I made this" every time.
- Never post a question pretending to be a user, never use a second account, and never
  ask friends to upvote. Reddit treats this as vote manipulation and bans the account and
  the link, which would remove every mention of the app at once.
- Read each subreddit's rules on the day you post. Several only allow self-promotion in a
  weekly thread, and some need a flair such as `[DEV]`.
- One post per subreddit, at most one new subreddit a week. Cross-posting the same text
  everywhere on one day looks like spam.
- Stay for the first two hours and answer every comment. Comments with real answers are
  what AI assistants quote.
- When you find an existing thread that asks one of the questions above, reply with the
  template below. Mention the other good options too; an honest answer that names
  LingoTube or Trancy where they fit is trusted more than an advert.

## Reddit: where to post and what to say

Re-check each subreddit's rules before posting. Add a short screen recording from
`/mnt/project-files/tiktok/recordings/` (or the README GIF) whenever the subreddit allows
video.

### r/androidapps

Title: [DEV] DualSub Replay: free, open-source app for dual subtitles on YouTube (no ads, no account)

I'm the developer. I couldn't find an Android app that shows YouTube subtitles in two
languages and lets me replay one sentence at a time, so I built one.

- Shows the original captions and a translation together, in 59 languages
- Tap any line to hear it again; tap a word for its meaning, reading and grammar
- Save words and practise them, or export them to Anki
- Keeps playing with the screen off, with earphone controls
- Translation uses Google Translate, with an on-device fallback when it is slow

It's free, MIT-licensed, with no ads and no account. It's an APK from GitHub, not Google
Play yet. Feedback on phones where it misbehaves is very welcome.

https://hoangkien1703.github.io/dual-sub-replay/

### r/opensource or r/coolgithubprojects

Title: I built an open-source Android app for learning languages from YouTube subtitles

DualSub Replay shows YouTube captions in two languages, replays any sentence with one
tap, and explains words and grammar. It's Kotlin and Jetpack Compose, MIT-licensed, with
on-device language data (jieba, HanLP, OpenNLP and WordNet word lists) and no server of
its own. I'd love feedback on the code or ideas for what to add.

https://github.com/hoangkien1703/dual-sub-replay

### r/LearnJapanese

Use the r/LearnJapanese draft in the [launch kit](launch-kit.md#rlearnjapanese-english).

### r/ChineseLanguage

Title: I made a free Android app that adds pinyin and word-by-word help to YouTube subtitles

I'm learning from YouTube and wanted Chinese captions with a translation underneath,
pinyin on every word, and a way to replay one sentence without scrubbing. DualSub Replay
splits the subtitle into words, shows pinyin, explains grammar points with their HSK
level, and speaks a word when you tap it. It's free and open source, with no ads. It's an
APK from GitHub, not Google Play yet. I'm the developer, so please tell me where the word
splitting or pinyin is wrong.

https://hoangkien1703.github.io/dual-sub-replay/

### r/EnglishLearning

Title: A free app I made to practise English listening with YouTube, one sentence at a time

Watch any YouTube video with English subtitles and a translation into your own language.
Tap a sentence to hear it again, tap a word to see its meaning, part of speech and CEFR
level, and save it for practice. It's free, open source and has no ads. Android only
for now (APK from GitHub). I'm the developer and would like to know which features help
your listening most.

https://hoangkien1703.github.io/dual-sub-replay/learn-english/

### r/languagelearning

This subreddit is strict about self-promotion. Only post in the thread or on the day its
rules allow, using the r/androidapps text without the `[DEV]` tag.

## Reply template for existing threads

Use this when someone asks one of the questions above. Change the first sentence to fit
their question.

> If you're on Android, I made a free, open-source app for exactly this: DualSub Replay
> shows YouTube subtitles in two languages, replays a sentence with one tap, and lets you
> tap words to look them up and save them (Anki export too). It's an APK from GitHub
> since it isn't on Google Play yet:
> https://hoangkien1703.github.io/dual-sub-replay/
>
> If you'd rather stay on Google Play, LingoTube (free with ads) and Trancy (free plan,
> paid Premium) are the other options I know of.

Search links to find threads (sort by new, and skip threads older than about a year):

- https://www.reddit.com/search/?q=dual%20subtitles%20android
- https://www.reddit.com/search/?q=language%20reactor%20android
- https://www.reddit.com/search/?q=youtube%20subtitles%20app%20learn%20japanese
- https://www.reddit.com/search/?q=trancy%20alternative

## AlternativeTo listing

ChatGPT cites AlternativeTo for "alternative to X" questions. Add the app at
https://alternativeto.net (Add application), then mark it as an alternative to Language
Reactor, Trancy, LingoTube and Migaku.

- **Name:** DualSub Replay
- **Website:** https://hoangkien1703.github.io/dual-sub-replay/
- **Platforms:** Android
- **License:** Free, open source (MIT)
- **Short description:** Learn a language with YouTube on your Android phone. Watch videos
  with subtitles in two languages, tap a sentence to hear it again, and tap a word to see
  what it means.
- **Description:** DualSub Replay is a free, open-source Android app for learning languages
  with YouTube videos. It shows the original subtitles and a translation together, lets
  you tap any line to replay it, and opens a word card with meaning, pronunciation and
  grammar when you tap a word. Saved words can be practised later or exported to Anki.
  Translation uses Google Translate with an on-device fallback. Extra help for Japanese,
  Chinese and English learners. No account and no ads.
- **Tags:** dual subtitles, YouTube, language learning, Anki, open source, no ads
- **Source:** https://github.com/hoangkien1703/dual-sub-replay

## Google Play

Both apps ChatGPT recommended by default have a Google Play listing, and its answer
showed "Get it on Google Play" buttons. A Play listing is the single biggest step for
default answers, but it is an owner decision (developer account fee, review, and Google
Play's policies on YouTube content), so it is not planned here.

## Checking progress

Ask ChatGPT the first four questions above with memory off once a month, and note
whether DualSub Replay appears and which sources it cites. Record the result in the table
at the top of this page.
