// Translation language codes, as the app's TranslationLanguages.targetCode and googleLanguageCode; the list itself is
// data/languages.js (generated). Shared by ui/content.js, ui/popup.js and background.js so they never disagree.

// [code] as a language to translate to: its base, except Traditional Chinese (zh-TW, zh-HK, zh-MO, zh-Hant) stays
// "zh-TW"; "iw" is Hebrew's old code. "zh-CN", which earlier versions stored, becomes the app's "zh".
function targetCode(code) {
  const parts = String(code || '').trim().toLowerCase().split(/[-_]/);
  if (parts[0] === 'zh' && !parts.includes('hans') && parts.slice(1).some((p) => ['hant', 'tw', 'hk', 'mo'].includes(p))) return 'zh-TW';
  return parts[0] === 'iw' ? 'he' : parts[0];
}

// The language to translate into before the user picks one: the browser's own (chrome.i18n.getUILanguage()) when
// the app offers it, else English.
function defaultTarget(uiLanguage) {
  const code = targetCode(uiLanguage);
  return DualSub.TRANSLATION_LANGUAGES.some(([c]) => c === code) ? code : 'en';
}

// Google Translate's code for a target: Simplified Chinese is "zh-CN" there.
const googleTarget = (code) => (targetCode(code) === 'zh' ? 'zh-CN' : targetCode(code));

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { targetCode, defaultTarget, googleTarget });
