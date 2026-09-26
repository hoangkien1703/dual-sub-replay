# Discoverability checklist

How people find DualSub Replay: Google results for searches such as "dual subtitles
YouTube Android" or "phụ đề song ngữ YouTube", the GitHub repository page, and
community posts. The repository carries the landing page (`site/`), README wording,
and structured data. The steps below need the owner's GitHub or Google account.
Tracking issue: [#53](https://github.com/hoangkien1703/dual-sub-replay/issues/53).

Website: https://hoangkien1703.github.io/dual-sub-replay/ (English) and
https://hoangkien1703.github.io/dual-sub-replay/vi/ (Vietnamese).

## Owner steps, in priority order

1. **Turn on GitHub Pages.** Settings → Pages → Build and deployment → Source:
   **GitHub Actions**. The **Publish website** workflow deploys on the next `main`
   change to `site/`; to publish immediately, use Actions → Publish website → Run workflow.
2. **Point the repository at the website.** On the repository page, About (gear icon) →
   Website: `https://hoangkien1703.github.io/dual-sub-replay/`. Add topics up to
   GitHub's limit of 20, for example `language-learning-app`, `learn-english`,
   `youtube-subtitles`, `anki`, `spaced-repetition`, and `vocabulary`.
3. **Set the social preview.** Settings → General → Social preview → upload
   `docs/images/dualsub-replay-social-preview.png` so shared repository links show
   the app instead of a generic card.
4. **Register with Google Search Console.** Add a **URL prefix** property for the
   website URL, choose the **HTML tag** method, and copy only the `content` value.
   Save it as the repository variable `GOOGLE_SITE_VERIFICATION` (Settings → Secrets
   and variables → Actions → Variables), re-run Publish website, then press Verify.
   Submit `sitemap.xml` and request indexing for both pages. (Done 2026-09-26 with the
   HTML file method instead: `site/googlea6eec9334d18b877.html`. Keep that file, or
   Search Console loses verification.) Bing Webmaster Tools can
   import the verified site from Search Console.
5. **Earn links from places learners already look.** Each is a backlink that helps
   ranking and a direct source of users and stars:
   - List the app on AlternativeTo as an alternative to Language Reactor and similar tools.
   - Propose it to curated lists such as awesome open-source Android app lists, following
     each list's contribution rules.
   - Post once per community with the drafts in [launch kit](launch-kit.md): a language
     learning subreddit that allows self-promotion, r/androidapps, Show HN, and Vietnamese
     language-learning Facebook groups. Disclose that you built it and ask for feedback,
     not stars.
6. **Consider wider stores later.** Google Play reaches far more users than a GitHub APK,
   but its policies on apps that use YouTube content are a real rejection risk. Read the
   current policy first. Obtainium users can already track the GitHub releases.

## Measuring

- Search Console → Performance shows queries, impressions, and clicks once indexed.
- Repository Insights → Traffic shows referrers and page views for the last 14 days.
- Release download counts appear on each release's assets.

## Keeping the site accurate

- `site/` is plain HTML and CSS with no build step. Keep English and Vietnamese pages in
  step, and keep FAQ answers identical in visible text and JSON-LD.
- `tools/tests/test_site.py` checks titles, descriptions, canonical and hreflang links,
  FAQ structured data, local links, the sitemap, and that the language list matches
  `TranslationLanguages.kt`.
- Do not add ratings, review counts, or download numbers to structured data unless they
  come from a real, visible source.
