#!/usr/bin/env python3
"""Generates strings_grammar_de.xml for every interface language, plus GermanGrammarText.kt.
Usage: python3 tools/german/build_german_strings.py app/src/main
Texts: German terms and examples stay German; only the explanation is translated. Drafts, unreviewed."""
import sys
from xml.sax.saxutils import escape

OUT = sys.argv[1]  # app/src/main
LANGS = ["en", "vi", "es", "pt", "ja", "ko", "zh", "in", "az"]
FOLDER = {"en": "values", "vi": "values-vi", "es": "values-es", "pt": "values-pt-rBR", "ja": "values-ja", "ko": "values-ko", "zh": "values-zh-rCN", "in": "values-in", "az": "values-az"}

# Shared phrases (per language) used inside several entries.
END = {
    "en": "the verb goes to the end of the clause",
    "vi": "động từ đứng ở cuối mệnh đề",
    "es": "el verbo va al final de la oración subordinada",
    "pt": "o verbo vai para o fim da oração",
    "ja": "動詞は節の最後に来ます",
    "ko": "동사는 절의 맨 끝에 옵니다",
    "zh": "动词放在从句的最后",
    "in": "kata kerja ada di akhir klausa",
    "az": "fel cümlənin sonuna keçir",
}

# name -> {lang: text}. A text may contain {END}.
T = {}


def add(name, en, vi, es, pt, ja, ko, zh, in_, az):
    T[name] = dict(zip(LANGS, [en, vi, es, pt, ja, ko, zh, in_, az]))


add("praesens", "Präsens: the present tense (ich gehe, er geht)",
    "Präsens: thì hiện tại (ich gehe, er geht)", "Präsens: el presente (ich gehe, er geht)", "Präsens: o presente (ich gehe, er geht)",
    "Präsens: 現在形(ich gehe, er geht)", "Präsens: 현재 시제(ich gehe, er geht)", "Präsens: 现在时(ich gehe, er geht)",
    "Präsens: bentuk sekarang (ich gehe, er geht)", "Präsens: indiki zaman (ich gehe, er geht)")
add("praeteritum", "Präteritum: the simple past, mostly written, and common for sein, haben and modals (ich ging, ich war)",
    "Präteritum: quá khứ đơn, chủ yếu trong văn viết, thường dùng với sein, haben và động từ khuyết thiếu (ich ging, ich war)",
    "Präteritum: el pasado simple, sobre todo escrito, y habitual con sein, haben y los modales (ich ging, ich war)",
    "Präteritum: o passado simples, mais escrito, comum com sein, haben e modais (ich ging, ich war)",
    "Präteritum: 単純過去。主に書き言葉で、sein・haben・助動詞でよく使います(ich ging, ich war)",
    "Präteritum: 단순 과거. 주로 글에서 쓰고 sein, haben, 화법조동사에 흔합니다(ich ging, ich war)",
    "Präteritum: 简单过去时,多用于书面,sein、haben 和情态动词常用(ich ging, ich war)",
    "Präteritum: lampau sederhana, terutama tertulis, umum untuk sein, haben, dan kata kerja modal (ich ging, ich war)",
    "Präteritum: sadə keçmiş zaman, əsasən yazıda; sein, haben və modal fellərdə geniş işlənir (ich ging, ich war)")
add("perfekt", "Perfekt: the usual spoken past, haben or sein + Partizip II (ich habe gesehen, ich bin gegangen)",
    "Perfekt: quá khứ thường dùng khi nói, haben hoặc sein + Partizip II (ich habe gesehen, ich bin gegangen)",
    "Perfekt: el pasado habitual al hablar, haben o sein + Partizip II (ich habe gesehen, ich bin gegangen)",
    "Perfekt: o passado usado na fala, haben ou sein + Partizip II (ich habe gesehen, ich bin gegangen)",
    "Perfekt: 話し言葉の普通の過去。haben または sein + Partizip II(ich habe gesehen, ich bin gegangen)",
    "Perfekt: 말할 때 쓰는 보통의 과거. haben 또는 sein + Partizip II(ich habe gesehen, ich bin gegangen)",
    "Perfekt: 口语中常用的过去时,haben 或 sein + Partizip II(ich habe gesehen, ich bin gegangen)",
    "Perfekt: lampau yang lazim dalam percakapan, haben atau sein + Partizip II (ich habe gesehen, ich bin gegangen)",
    "Perfekt: danışıqda adi keçmiş zaman, haben və ya sein + Partizip II (ich habe gesehen, ich bin gegangen)")
add("plusquamperfekt", "Plusquamperfekt: the past before another past, hatte or war + Partizip II (er hatte gelesen)",
    "Plusquamperfekt: quá khứ xảy ra trước một quá khứ khác, hatte hoặc war + Partizip II (er hatte gelesen)",
    "Plusquamperfekt: el pasado anterior a otro pasado, hatte o war + Partizip II (er hatte gelesen)",
    "Plusquamperfekt: o passado anterior a outro passado, hatte ou war + Partizip II (er hatte gelesen)",
    "Plusquamperfekt: 別の過去より前の過去。hatte または war + Partizip II(er hatte gelesen)",
    "Plusquamperfekt: 다른 과거보다 먼저 일어난 과거. hatte 또는 war + Partizip II(er hatte gelesen)",
    "Plusquamperfekt: 发生在另一个过去之前的过去,hatte 或 war + Partizip II(er hatte gelesen)",
    "Plusquamperfekt: lampau sebelum lampau lainnya, hatte atau war + Partizip II (er hatte gelesen)",
    "Plusquamperfekt: başqa keçmişdən əvvəlki keçmiş, hatte və ya war + Partizip II (er hatte gelesen)")
add("futur_i", "Futur I: werden + Infinitiv, a plan or a guess about the future (ich werde kommen)",
    "Futur I: werden + Infinitiv, kế hoạch hoặc dự đoán về tương lai (ich werde kommen)",
    "Futur I: werden + Infinitiv, un plan o una suposición sobre el futuro (ich werde kommen)",
    "Futur I: werden + Infinitiv, um plano ou uma suposição sobre o futuro (ich werde kommen)",
    "Futur I: werden + Infinitiv。未来の予定や推量(ich werde kommen)",
    "Futur I: werden + Infinitiv. 미래의 계획이나 추측(ich werde kommen)",
    "Futur I: werden + Infinitiv,对将来的打算或推测(ich werde kommen)",
    "Futur I: werden + Infinitiv, rencana atau dugaan tentang masa depan (ich werde kommen)",
    "Futur I: werden + Infinitiv, gələcək haqqında plan və ya ehtimal (ich werde kommen)")
add("futur_ii", "Futur II: werden + Partizip II + haben or sein, something will be done by then or probably happened (er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben hoặc sein, việc sẽ xong vào lúc đó hoặc có lẽ đã xảy ra (er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben o sein, algo estará hecho para entonces o probablemente ocurrió (er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben ou sein, algo estará feito até lá ou provavelmente aconteceu (er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben/sein。その時までに完了している、または過去の推量(er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben/sein. 그때까지 끝나 있을 일, 또는 과거에 대한 추측(er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben/sein,到那时已完成,或对过去的推测(er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben atau sein, sudah selesai pada saat itu atau mungkin sudah terjadi (er wird es gesehen haben)",
    "Futur II: werden + Partizip II + haben və ya sein, o vaxta qədər bitmiş iş və ya keçmiş haqqında ehtimal (er wird es gesehen haben)")
add("imperativ", "Imperativ: a command or request (komm!, geht!, kommen Sie!)",
    "Imperativ: câu mệnh lệnh hoặc yêu cầu (komm!, geht!, kommen Sie!)", "Imperativ: una orden o un ruego (komm!, geht!, kommen Sie!)",
    "Imperativ: uma ordem ou pedido (komm!, geht!, kommen Sie!)", "Imperativ: 命令や依頼(komm!, geht!, kommen Sie!)",
    "Imperativ: 명령이나 부탁(komm!, geht!, kommen Sie!)", "Imperativ: 命令或请求(komm!, geht!, kommen Sie!)",
    "Imperativ: perintah atau permintaan (komm!, geht!, kommen Sie!)", "Imperativ: əmr və ya xahiş (komm!, geht!, kommen Sie!)")
add("konjunktiv_i", "Konjunktiv I: the form for reported speech (er sagt, er sei krank)",
    "Konjunktiv I: dạng dùng cho lời nói gián tiếp (er sagt, er sei krank)", "Konjunktiv I: la forma del estilo indirecto (er sagt, er sei krank)",
    "Konjunktiv I: a forma do discurso indireto (er sagt, er sei krank)", "Konjunktiv I: 間接話法の形(er sagt, er sei krank)",
    "Konjunktiv I: 간접 화법에 쓰는 형태(er sagt, er sei krank)", "Konjunktiv I: 用于间接引语的形式(er sagt, er sei krank)",
    "Konjunktiv I: bentuk untuk kalimat tidak langsung (er sagt, er sei krank)", "Konjunktiv I: dolayı nitq forması (er sagt, er sei krank)")
add("konjunktiv_ii", "Konjunktiv II: unreal, polite or wishful (ich hätte, wenn ich könnte)",
    "Konjunktiv II: điều không có thật, lịch sự hoặc mong muốn (ich hätte, wenn ich könnte)",
    "Konjunktiv II: lo irreal, la cortesía o el deseo (ich hätte, wenn ich könnte)", "Konjunktiv II: o irreal, a cortesia ou o desejo (ich hätte, wenn ich könnte)",
    "Konjunktiv II: 非現実・丁寧・願望(ich hätte, wenn ich könnte)", "Konjunktiv II: 비현실, 공손함, 바람(ich hätte, wenn ich könnte)",
    "Konjunktiv II: 非真实、委婉或愿望(ich hätte, wenn ich könnte)", "Konjunktiv II: hal tidak nyata, sopan, atau harapan (ich hätte, wenn ich könnte)",
    "Konjunktiv II: qeyri-real, nəzakətli və ya arzu (ich hätte, wenn ich könnte)")
add("wuerde_infinitiv", "würde + Infinitiv: the usual Konjunktiv II for most verbs (ich würde gehen)",
    "würde + Infinitiv: Konjunktiv II thường dùng cho hầu hết động từ (ich würde gehen)", "würde + Infinitiv: el Konjunktiv II habitual de la mayoría de los verbos (ich würde gehen)",
    "würde + Infinitiv: o Konjunktiv II comum para a maioria dos verbos (ich würde gehen)", "würde + Infinitiv: ほとんどの動詞で使う普通の Konjunktiv II(ich würde gehen)",
    "würde + Infinitiv: 대부분의 동사에 쓰는 일반적인 Konjunktiv II(ich würde gehen)", "würde + Infinitiv: 大多数动词常用的 Konjunktiv II(ich würde gehen)",
    "würde + Infinitiv: Konjunktiv II yang umum untuk kebanyakan kata kerja (ich würde gehen)", "würde + Infinitiv: əksər fellər üçün adi Konjunktiv II (ich würde gehen)")
add("konjunktiv_ii_vergangenheit", "Konjunktiv II of the past: something that did not happen, hätte or wäre + Partizip II (ich hätte gewusst)",
    "Konjunktiv II của quá khứ: điều đã không xảy ra, hätte hoặc wäre + Partizip II (ich hätte gewusst)",
    "Konjunktiv II del pasado: algo que no ocurrió, hätte o wäre + Partizip II (ich hätte gewusst)",
    "Konjunktiv II do passado: algo que não aconteceu, hätte ou wäre + Partizip II (ich hätte gewusst)",
    "過去の Konjunktiv II: 実現しなかったこと。hätte または wäre + Partizip II(ich hätte gewusst)",
    "과거의 Konjunktiv II: 일어나지 않은 일. hätte 또는 wäre + Partizip II(ich hätte gewusst)",
    "过去的 Konjunktiv II:没有发生的事,hätte 或 wäre + Partizip II(ich hätte gewusst)",
    "Konjunktiv II masa lampau: sesuatu yang tidak terjadi, hätte atau wäre + Partizip II (ich hätte gewusst)",
    "Keçmişin Konjunktiv II-si: baş verməyən şey, hätte və ya wäre + Partizip II (ich hätte gewusst)")
add("ersatzinfinitiv", "Ersatzinfinitiv: with a modal in a perfect tense, two infinitives stand at the end (ich hätte es wissen müssen)",
    "Ersatzinfinitiv: với động từ khuyết thiếu ở thì hoàn thành, hai động từ nguyên mẫu đứng cuối câu (ich hätte es wissen müssen)",
    "Ersatzinfinitiv: con un modal en un tiempo compuesto, dos infinitivos van al final (ich hätte es wissen müssen)",
    "Ersatzinfinitiv: com um modal em tempo composto, dois infinitivos ficam no fim (ich hätte es wissen müssen)",
    "Ersatzinfinitiv: 助動詞が完了形に入ると、不定詞が二つ最後に並びます(ich hätte es wissen müssen)",
    "Ersatzinfinitiv: 화법조동사가 완료 시제에 쓰이면 부정사 두 개가 끝에 옵니다(ich hätte es wissen müssen)",
    "Ersatzinfinitiv: 情态动词用于完成时,句末出现两个不定式(ich hätte es wissen müssen)",
    "Ersatzinfinitiv: dengan kata kerja modal dalam kala perfekt, dua infinitif berada di akhir (ich hätte es wissen müssen)",
    "Ersatzinfinitiv: modal fel mürəkkəb zamanda işləndikdə, sonda iki infinitiv durur (ich hätte es wissen müssen)")
add("vorgangspassiv", "Vorgangspassiv: werden + Partizip II, the action is done to the subject (das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II, hành động tác động lên chủ ngữ (das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II, la acción recae sobre el sujeto (das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II, a ação recai sobre o sujeito (das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II。主語が動作を受けます(das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II. 주어가 동작을 받습니다(das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II,动作施加于主语(das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II, tindakan dikenakan pada subjek (das Haus wird gebaut)",
    "Vorgangspassiv: werden + Partizip II, hərəkət subyekt üzərində icra olunur (das Haus wird gebaut)")
add("zustandspassiv", "Zustandspassiv: sein + Partizip II, the resulting state (das Haus ist gebaut); it can also read as an adjective",
    "Zustandspassiv: sein + Partizip II, trạng thái kết quả (das Haus ist gebaut); cũng có thể hiểu là tính từ",
    "Zustandspassiv: sein + Partizip II, el estado resultante (das Haus ist gebaut); también puede leerse como adjetivo",
    "Zustandspassiv: sein + Partizip II, o estado resultante (das Haus ist gebaut); também pode ser lido como adjetivo",
    "Zustandspassiv: sein + Partizip II。結果の状態(das Haus ist gebaut)。形容詞とも読めます",
    "Zustandspassiv: sein + Partizip II. 결과 상태(das Haus ist gebaut). 형용사로 볼 수도 있습니다",
    "Zustandspassiv: sein + Partizip II,表示结果状态(das Haus ist gebaut);也可以看作形容词",
    "Zustandspassiv: sein + Partizip II, keadaan hasil (das Haus ist gebaut); bisa juga dibaca sebagai kata sifat",
    "Zustandspassiv: sein + Partizip II, nəticə vəziyyəti (das Haus ist gebaut); sifət kimi də oxuna bilər")
add("passiv_perfekt", "Passiv Perfekt: sein + Partizip II + worden (das Haus ist gebaut worden)",
    "Passiv Perfekt: sein + Partizip II + worden (das Haus ist gebaut worden)", "Passiv Perfekt: sein + Partizip II + worden (das Haus ist gebaut worden)",
    "Passiv Perfekt: sein + Partizip II + worden (das Haus ist gebaut worden)", "Passiv Perfekt: sein + Partizip II + worden(das Haus ist gebaut worden)",
    "Passiv Perfekt: sein + Partizip II + worden(das Haus ist gebaut worden)", "Passiv Perfekt: sein + Partizip II + worden(das Haus ist gebaut worden)",
    "Passiv Perfekt: sein + Partizip II + worden (das Haus ist gebaut worden)", "Passiv Perfekt: sein + Partizip II + worden (das Haus ist gebaut worden)")
add("modalverb", "Modalverb + Infinitiv: can, must, want, should, may; the infinitive goes to the end (ich muss gehen)",
    "Modalverb + Infinitiv: có thể, phải, muốn, nên, được phép; động từ nguyên mẫu đứng cuối (ich muss gehen)",
    "Modalverb + Infinitiv: poder, deber, querer, tener que, permitir; el infinitivo va al final (ich muss gehen)",
    "Modalverb + Infinitiv: poder, dever, querer, ter de, ser permitido; o infinitivo vai para o fim (ich muss gehen)",
    "Modalverb + Infinitiv: できる・しなければならない・したい・すべき・してよい。不定詞は最後に置きます(ich muss gehen)",
    "Modalverb + Infinitiv: 할 수 있다, 해야 한다, 원한다, 해야 한다, 해도 된다. 부정사는 맨 끝에 옵니다(ich muss gehen)",
    "Modalverb + Infinitiv:能、必须、想、应该、可以;不定式放在句末(ich muss gehen)",
    "Modalverb + Infinitiv: bisa, harus, mau, sebaiknya, boleh; infinitif ada di akhir (ich muss gehen)",
    "Modalverb + Infinitiv: bilmək, məcbur olmaq, istəmək, gərək, icazə; infinitiv sonda durur (ich muss gehen)")
add("trennbares_verb", "Trennbares Verb: the prefix leaves the verb and goes to the end of the clause (ich stehe früh auf)",
    "Trennbares Verb: tiền tố tách khỏi động từ và đứng cuối mệnh đề (ich stehe früh auf)",
    "Trennbares Verb: el prefijo se separa del verbo y va al final de la oración (ich stehe früh auf)",
    "Trennbares Verb: o prefixo se separa do verbo e vai para o fim da oração (ich stehe früh auf)",
    "Trennbares Verb: 前つづりが動詞から離れて節の最後に来ます(ich stehe früh auf)",
    "Trennbares Verb: 접두사가 동사에서 떨어져 절의 맨 끝으로 갑니다(ich stehe früh auf)",
    "Trennbares Verb: 前缀与动词分开,放到句末(ich stehe früh auf)",
    "Trennbares Verb: awalan terpisah dari kata kerja dan pindah ke akhir klausa (ich stehe früh auf)",
    "Trennbares Verb: önşəkilçi fellərdən ayrılıb cümlənin sonuna keçir (ich stehe früh auf)")
add("reflexives_verb", "Reflexives Verb: used with a reflexive pronoun (ich freue mich)",
    "Reflexives Verb: dùng với đại từ phản thân (ich freue mich)", "Reflexives Verb: se usa con un pronombre reflexivo (ich freue mich)",
    "Reflexives Verb: usado com um pronome reflexivo (ich freue mich)", "Reflexives Verb: 再帰代名詞とともに使います(ich freue mich)",
    "Reflexives Verb: 재귀대명사와 함께 씁니다(ich freue mich)", "Reflexives Verb: 与反身代词连用(ich freue mich)",
    "Reflexives Verb: dipakai dengan kata ganti refleksif (ich freue mich)", "Reflexives Verb: qayıdış əvəzliyi ilə işlənir (ich freue mich)")
add("zu_infinitiv", "zu + Infinitiv: the infinitive with zu after many verbs and nouns (er hofft, dich zu sehen)",
    "zu + Infinitiv: động từ nguyên mẫu có zu sau nhiều động từ và danh từ (er hofft, dich zu sehen)",
    "zu + Infinitiv: el infinitivo con zu tras muchos verbos y sustantivos (er hofft, dich zu sehen)",
    "zu + Infinitiv: o infinitivo com zu depois de muitos verbos e substantivos (er hofft, dich zu sehen)",
    "zu + Infinitiv: 多くの動詞や名詞のあとで使う zu 付き不定詞(er hofft, dich zu sehen)",
    "zu + Infinitiv: 많은 동사와 명사 뒤에 오는 zu 부정사(er hofft, dich zu sehen)",
    "zu + Infinitiv:许多动词和名词后面带 zu 的不定式(er hofft, dich zu sehen)",
    "zu + Infinitiv: infinitif dengan zu setelah banyak kata kerja dan kata benda (er hofft, dich zu sehen)",
    "zu + Infinitiv: bir çox fel və isimdən sonra zu ilə infinitiv (er hofft, dich zu sehen)")
add("um_zu", "um … zu + Infinitiv: in order to (er lernt, um zu verstehen)",
    "um … zu + Infinitiv: để (er lernt, um zu verstehen)", "um … zu + Infinitiv: para (er lernt, um zu verstehen)", "um … zu + Infinitiv: para (er lernt, um zu verstehen)",
    "um … zu + Infinitiv: 〜するために(er lernt, um zu verstehen)", "um … zu + Infinitiv: ~하기 위해(er lernt, um zu verstehen)",
    "um … zu + Infinitiv:为了……(er lernt, um zu verstehen)", "um … zu + Infinitiv: untuk (er lernt, um zu verstehen)", "um … zu + Infinitiv: …maq üçün (er lernt, um zu verstehen)")
add("ohne_zu", "ohne … zu / (an)statt … zu + Infinitiv: without doing / instead of doing",
    "ohne … zu / (an)statt … zu + Infinitiv: mà không / thay vì", "ohne … zu / (an)statt … zu + Infinitiv: sin hacer / en lugar de hacer",
    "ohne … zu / (an)statt … zu + Infinitiv: sem fazer / em vez de fazer", "ohne … zu / (an)statt … zu + Infinitiv: 〜せずに / 〜する代わりに",
    "ohne … zu / (an)statt … zu + Infinitiv: ~하지 않고 / ~하는 대신에", "ohne … zu / (an)statt … zu + Infinitiv:不……而 / 而不是……",
    "ohne … zu / (an)statt … zu + Infinitiv: tanpa melakukan / alih-alih melakukan", "ohne … zu / (an)statt … zu + Infinitiv: etmədən / etmək əvəzinə")
add("prep_akkusativ", "Preposition with Akkusativ: durch, für, gegen, ohne, um always take the accusative",
    "Giới từ đi với Akkusativ: durch, für, gegen, ohne, um luôn đi với accusative", "Preposición con Akkusativ: durch, für, gegen, ohne, um siempre llevan acusativo",
    "Preposição com Akkusativ: durch, für, gegen, ohne, um pedem sempre o acusativo", "Akkusativ を取る前置詞: durch, für, gegen, ohne, um は常に4格",
    "Akkusativ를 취하는 전치사: durch, für, gegen, ohne, um는 항상 4격", "要求 Akkusativ 的介词:durch、für、gegen、ohne、um 总是接四格",
    "Preposisi dengan Akkusativ: durch, für, gegen, ohne, um selalu memakai akusatif", "Akkusativ tələb edən ön şəkilçilər: durch, für, gegen, ohne, um həmişə təsirlik hal tələb edir")
add("prep_dativ", "Preposition with Dativ: aus, bei, mit, nach, seit, von, zu always take the dative",
    "Giới từ đi với Dativ: aus, bei, mit, nach, seit, von, zu luôn đi với dative", "Preposición con Dativ: aus, bei, mit, nach, seit, von, zu siempre llevan dativo",
    "Preposição com Dativ: aus, bei, mit, nach, seit, von, zu pedem sempre o dativo", "Dativ を取る前置詞: aus, bei, mit, nach, seit, von, zu は常に3格",
    "Dativ를 취하는 전치사: aus, bei, mit, nach, seit, von, zu는 항상 3격", "要求 Dativ 的介词:aus、bei、mit、nach、seit、von、zu 总是接三格",
    "Preposisi dengan Dativ: aus, bei, mit, nach, seit, von, zu selalu memakai datif", "Dativ tələb edən ön şəkilçilər: aus, bei, mit, nach, seit, von, zu həmişə yönlük hal tələb edir")
add("prep_genitiv", "Preposition with Genitiv: wegen, trotz, während, statt take the genitive (wegen des Wetters)",
    "Giới từ đi với Genitiv: wegen, trotz, während, statt đi với genitive (wegen des Wetters)", "Preposición con Genitiv: wegen, trotz, während, statt llevan genitivo (wegen des Wetters)",
    "Preposição com Genitiv: wegen, trotz, während, statt pedem o genitivo (wegen des Wetters)", "Genitiv を取る前置詞: wegen, trotz, während, statt は2格(wegen des Wetters)",
    "Genitiv를 취하는 전치사: wegen, trotz, während, statt는 2격(wegen des Wetters)", "要求 Genitiv 的介词:wegen、trotz、während、statt 接二格(wegen des Wetters)",
    "Preposisi dengan Genitiv: wegen, trotz, während, statt memakai genitif (wegen des Wetters)", "Genitiv tələb edən ön şəkilçilər: wegen, trotz, während, statt (wegen des Wetters)")
add("wechsel_akkusativ", "Wechselpräposition with Akkusativ: a movement toward a place, wohin? (ich gehe in die Stadt)",
    "Wechselpräposition với Akkusativ: chuyển động đến một nơi, wohin? (ich gehe in die Stadt)", "Wechselpräposition con Akkusativ: un movimiento hacia un lugar, ¿wohin? (ich gehe in die Stadt)",
    "Wechselpräposition com Akkusativ: movimento em direção a um lugar, wohin? (ich gehe in die Stadt)", "Wechselpräposition + Akkusativ: 場所への移動、wohin?(ich gehe in die Stadt)",
    "Wechselpräposition + Akkusativ: 어떤 곳으로의 이동, wohin?(ich gehe in die Stadt)", "Wechselpräposition + Akkusativ:朝某个地方的移动,wohin?(ich gehe in die Stadt)",
    "Wechselpräposition dengan Akkusativ: gerakan menuju suatu tempat, wohin? (ich gehe in die Stadt)", "Wechselpräposition + Akkusativ: bir yerə doğru hərəkət, wohin? (ich gehe in die Stadt)")
add("wechsel_dativ", "Wechselpräposition with Dativ: a position or a place, wo? (ich bin in der Stadt)",
    "Wechselpräposition với Dativ: vị trí hoặc nơi chốn, wo? (ich bin in der Stadt)", "Wechselpräposition con Dativ: una posición o un lugar, ¿wo? (ich bin in der Stadt)",
    "Wechselpräposition com Dativ: uma posição ou um lugar, wo? (ich bin in der Stadt)", "Wechselpräposition + Dativ: 位置や場所、wo?(ich bin in der Stadt)",
    "Wechselpräposition + Dativ: 위치나 장소, wo?(ich bin in der Stadt)", "Wechselpräposition + Dativ:位置或地点,wo?(ich bin in der Stadt)",
    "Wechselpräposition dengan Dativ: posisi atau tempat, wo? (ich bin in der Stadt)", "Wechselpräposition + Dativ: vəziyyət və ya yer, wo? (ich bin in der Stadt)")
add("verb_prep_akkusativ", "Verb with a fixed preposition and Akkusativ (warten auf + Akk.): learn the preposition together with the verb",
    "Động từ đi với giới từ cố định và Akkusativ (warten auf + Akk.): học giới từ cùng với động từ", "Verbo con preposición fija y Akkusativ (warten auf + Akk.): aprende la preposición junto con el verbo",
    "Verbo com preposição fixa e Akkusativ (warten auf + Akk.): aprenda a preposição junto com o verbo", "決まった前置詞と Akkusativ を取る動詞(warten auf + Akk.): 前置詞も動詞と一緒に覚えましょう",
    "정해진 전치사와 Akkusativ를 쓰는 동사(warten auf + Akk.): 전치사를 동사와 함께 외우세요", "固定介词加 Akkusativ 的动词(warten auf + Akk.):把介词和动词一起记",
    "Kata kerja dengan preposisi tetap dan Akkusativ (warten auf + Akk.): pelajari preposisi bersama kata kerjanya", "Sabit ön şəkilçili və Akkusativ ilə fel (warten auf + Akk.): ön şəkilçini fellə birlikdə öyrənin")
add("verb_prep_dativ", "Verb with a fixed preposition and Dativ (träumen von + Dat.): learn the preposition together with the verb",
    "Động từ đi với giới từ cố định và Dativ (träumen von + Dat.): học giới từ cùng với động từ", "Verbo con preposición fija y Dativ (träumen von + Dat.): aprende la preposición junto con el verbo",
    "Verbo com preposição fixa e Dativ (träumen von + Dat.): aprenda a preposição junto com o verbo", "決まった前置詞と Dativ を取る動詞(träumen von + Dat.): 前置詞も動詞と一緒に覚えましょう",
    "정해진 전치사와 Dativ를 쓰는 동사(träumen von + Dat.): 전치사를 동사와 함께 외우세요", "固定介词加 Dativ 的动词(träumen von + Dat.):把介词和动词一起记",
    "Kata kerja dengan preposisi tetap dan Dativ (träumen von + Dat.): pelajari preposisi bersama kata kerjanya", "Sabit ön şəkilçili və Dativ ilə fel (träumen von + Dat.): ön şəkilçini fellə birlikdə öyrənin")
add("dativ_verb", "Verb with Dativ: the person is in the dative (ich helfe dir)",
    "Động từ đi với Dativ: người nhận ở dạng dative (ich helfe dir)", "Verbo con Dativ: la persona va en dativo (ich helfe dir)",
    "Verbo com Dativ: a pessoa fica no dativo (ich helfe dir)", "Dativ を取る動詞: 人は3格になります(ich helfe dir)",
    "Dativ를 쓰는 동사: 사람이 3격이 됩니다(ich helfe dir)", "接 Dativ 的动词:人用三格(ich helfe dir)",
    "Kata kerja dengan Dativ: orangnya berbentuk datif (ich helfe dir)", "Dativ ilə fel: şəxs yönlük halda olur (ich helfe dir)")
add("dativ_akkusativ", "Dativ and Akkusativ objects: the person (dative) usually comes before the thing (accusative) (ich gebe dem Kind einen Ball)",
    "Tân ngữ Dativ và Akkusativ: người (dative) thường đứng trước vật (accusative) (ich gebe dem Kind einen Ball)",
    "Objetos en Dativ y Akkusativ: la persona (dativo) suele ir antes que la cosa (acusativo) (ich gebe dem Kind einen Ball)",
    "Objetos em Dativ e Akkusativ: a pessoa (dativo) costuma vir antes da coisa (acusativo) (ich gebe dem Kind einen Ball)",
    "Dativ と Akkusativ の目的語: 人(3格)が物(4格)の前に来るのが普通です(ich gebe dem Kind einen Ball)",
    "Dativ와 Akkusativ 목적어: 보통 사람(3격)이 사물(4격)보다 앞에 옵니다(ich gebe dem Kind einen Ball)",
    "Dativ 和 Akkusativ 宾语:通常人(三格)在物(四格)之前(ich gebe dem Kind einen Ball)",
    "Objek Dativ dan Akkusativ: orang (datif) biasanya di depan benda (akusatif) (ich gebe dem Kind einen Ball)",
    "Dativ və Akkusativ tamamlıqları: şəxs (yönlük) adətən əşyadan (təsirlik) əvvəl gəlir (ich gebe dem Kind einen Ball)")
add("genitivattribut", "Genitivattribut: the genitive says whose something is, and follows the noun (die Bücher der Kinder)",
    "Genitivattribut: genitive cho biết của ai, đứng sau danh từ (die Bücher der Kinder)", "Genitivattribut: el genitivo indica de quién es algo y sigue al sustantivo (die Bücher der Kinder)",
    "Genitivattribut: o genitivo indica de quem é algo e segue o substantivo (die Bücher der Kinder)", "Genitivattribut: 2格は「だれの」を表し、名詞のあとに置きます(die Bücher der Kinder)",
    "Genitivattribut: 2격은 누구의 것인지를 나타내며 명사 뒤에 옵니다(die Bücher der Kinder)", "Genitivattribut:二格表示属于谁,放在名词之后(die Bücher der Kinder)",
    "Genitivattribut: genitif menunjukkan milik siapa, dan berada setelah kata benda (die Bücher der Kinder)", "Genitivattribut: iyəlik halı kimə aid olduğunu bildirir və isimdən sonra gəlir (die Bücher der Kinder)")
add("adjektiv_schwach", "Adjective ending after der, die, das: only -e or -en (der alte Mann)",
    "Đuôi tính từ sau der, die, das: chỉ -e hoặc -en (der alte Mann)", "Terminación del adjetivo tras der, die, das: solo -e o -en (der alte Mann)",
    "Terminação do adjetivo após der, die, das: só -e ou -en (der alte Mann)", "der, die, das の後の形容詞語尾: -e か -en だけ(der alte Mann)",
    "der, die, das 뒤 형용사 어미: -e 또는 -en만(der alte Mann)", "der、die、das 之后的形容词词尾:只有 -e 或 -en(der alte Mann)",
    "Akhiran adjektiva setelah der, die, das: hanya -e atau -en (der alte Mann)", "der, die, das-dan sonra sifət şəkilçisi: yalnız -e və ya -en (der alte Mann)")
add("adjektiv_gemischt", "Adjective ending after ein, kein, mein: -er, -es or -e show the gender (ein alter Mann)",
    "Đuôi tính từ sau ein, kein, mein: -er, -es hoặc -e cho biết giống (ein alter Mann)", "Terminación del adjetivo tras ein, kein, mein: -er, -es o -e muestran el género (ein alter Mann)",
    "Terminação do adjetivo após ein, kein, mein: -er, -es ou -e mostram o gênero (ein alter Mann)", "ein, kein, mein の後の形容詞語尾: -er, -es, -e が性を示します(ein alter Mann)",
    "ein, kein, mein 뒤 형용사 어미: -er, -es, -e가 성을 나타냅니다(ein alter Mann)", "ein、kein、mein 之后的形容词词尾:-er、-es、-e 表示性(ein alter Mann)",
    "Akhiran adjektiva setelah ein, kein, mein: -er, -es atau -e menunjukkan gender (ein alter Mann)", "ein, kein, mein-dən sonra sifət şəkilçisi: -er, -es, -e cinsi göstərir (ein alter Mann)")
add("adjektiv_stark", "Adjective ending without an article: the ending shows case and gender (alter Wein)",
    "Đuôi tính từ khi không có mạo từ: đuôi cho biết cách và giống (alter Wein)", "Terminación del adjetivo sin artículo: la terminación muestra caso y género (alter Wein)",
    "Terminação do adjetivo sem artigo: a terminação mostra caso e gênero (alter Wein)", "冠詞のない形容詞の語尾: 語尾が格と性を示します(alter Wein)",
    "관사가 없을 때 형용사 어미: 어미가 격과 성을 나타냅니다(alter Wein)", "没有冠词时的形容词词尾:词尾表示格和性(alter Wein)",
    "Akhiran adjektiva tanpa artikel: akhiran menunjukkan kasus dan gender (alter Wein)", "Artiklsız sifət şəkilçisi: şəkilçi hal və cinsi göstərir (alter Wein)")
add("komparativ", "Komparativ: -er, comparing two things (größer als)",
    "Komparativ: -er, so sánh hai thứ (größer als)", "Komparativ: -er, para comparar dos cosas (größer als)", "Komparativ: -er, para comparar duas coisas (größer als)",
    "Komparativ: -er、2つを比べます(größer als)", "Komparativ: -er, 두 가지를 비교합니다(größer als)", "Komparativ:-er,比较两样东西(größer als)",
    "Komparativ: -er, membandingkan dua hal (größer als)", "Komparativ: -er, iki şeyi müqayisə edir (größer als)")
add("superlativ", "Superlativ: am … -sten, the most (am schönsten)",
    "Superlativ: am … -sten, nhất (am schönsten)", "Superlativ: am … -sten, el más (am schönsten)", "Superlativ: am … -sten, o mais (am schönsten)",
    "Superlativ: am … -sten、最も(am schönsten)", "Superlativ: am … -sten, 가장(am schönsten)", "Superlativ:am … -sten,最……(am schönsten)",
    "Superlativ: am … -sten, paling (am schönsten)", "Superlativ: am … -sten, ən (am schönsten)")
add("sub_weil", "weil / da: because; {END}", "weil / da: vì; {END}", "weil / da: porque; {END}", "weil / da: porque; {END}", "weil / da: 〜なので。{END}",
    "weil / da: ~ 때문에. {END}", "weil / da:因为;{END}", "weil / da: karena; {END}", "weil / da: çünki; {END}")
add("sub_dass", "dass: that; {END} (ich weiß, dass er kommt)", "dass: rằng; {END} (ich weiß, dass er kommt)", "dass: que; {END} (ich weiß, dass er kommt)", "dass: que; {END} (ich weiß, dass er kommt)",
    "dass: 〜ということ。{END}(ich weiß, dass er kommt)", "dass: ~라는 것. {END}(ich weiß, dass er kommt)", "dass:……;{END}(ich weiß, dass er kommt)",
    "dass: bahwa; {END} (ich weiß, dass er kommt)", "dass: ki; {END} (ich weiß, dass er kommt)")
add("sub_ob", "ob: whether; {END}", "ob: liệu; {END}", "ob: si (duda); {END}", "ob: se (dúvida); {END}", "ob: 〜かどうか。{END}", "ob: ~인지 아닌지. {END}", "ob:是否;{END}", "ob: apakah; {END}", "ob: …-mi; {END}")
add("sub_wenn", "wenn / falls: if or whenever; {END}", "wenn / falls: nếu hoặc mỗi khi; {END}", "wenn / falls: si o cada vez que; {END}", "wenn / falls: se ou sempre que; {END}",
    "wenn / falls: もし/〜のときはいつも。{END}", "wenn / falls: 만약 / ~할 때마다. {END}", "wenn / falls:如果,或每当;{END}", "wenn / falls: jika atau setiap kali; {END}", "wenn / falls: əgər və ya hər dəfə; {END}")
add("sub_als", "als: when, for one event in the past; {END}", "als: khi, cho một sự việc trong quá khứ; {END}", "als: cuando, para un hecho del pasado; {END}",
    "als: quando, para um fato do passado; {END}", "als: 〜したとき(過去の1回の出来事)。{END}", "als: ~했을 때(과거의 한 번의 일). {END}", "als:当……时(过去的一次事件);{END}",
    "als: ketika, untuk satu peristiwa di masa lalu; {END}", "als: …-dıqda (keçmişdə bir hadisə); {END}")
add("sub_obwohl", "obwohl / obgleich: although; {END}", "obwohl / obgleich: mặc dù; {END}", "obwohl / obgleich: aunque; {END}", "obwohl / obgleich: embora; {END}",
    "obwohl / obgleich: 〜だけれども。{END}", "obwohl / obgleich: ~임에도 불구하고. {END}", "obwohl / obgleich:尽管;{END}", "obwohl / obgleich: meskipun; {END}", "obwohl / obgleich: baxmayaraq ki; {END}")
add("sub_damit", "damit: so that, a purpose; {END}", "damit: để, chỉ mục đích; {END}", "damit: para que, una finalidad; {END}", "damit: para que, uma finalidade; {END}",
    "damit: 〜するために(目的)。{END}", "damit: ~하도록(목적). {END}", "damit:为了(目的);{END}", "damit: agar, tujuan; {END}", "damit: ki, məqsəd; {END}")
add("sub_zeit", "A time conjunction (bevor, nachdem, während, sobald, bis …): {END}", "Liên từ chỉ thời gian (bevor, nachdem, während, sobald, bis …): {END}",
    "Conjunción de tiempo (bevor, nachdem, während, sobald, bis …): {END}", "Conjunção de tempo (bevor, nachdem, während, sobald, bis …): {END}",
    "時を表す接続詞(bevor, nachdem, während, sobald, bis …): {END}", "시간 접속사(bevor, nachdem, während, sobald, bis …): {END}", "表示时间的连词(bevor、nachdem、während、sobald、bis …):{END}",
    "Konjungsi waktu (bevor, nachdem, während, sobald, bis …): {END}", "Zaman bağlayıcısı (bevor, nachdem, während, sobald, bis …): {END}")
add("sub_folge", "sodass / indem: so that (a result) or by doing; {END}", "sodass / indem: đến mức (kết quả) hoặc bằng cách; {END}", "sodass / indem: de modo que (resultado) o al hacer; {END}",
    "sodass / indem: de modo que (resultado) ou ao fazer; {END}", "sodass / indem: その結果/〜することで。{END}", "sodass / indem: 그 결과 / ~함으로써. {END}", "sodass / indem:以致(结果)或通过……;{END}",
    "sodass / indem: sehingga (hasil) atau dengan cara; {END}", "sodass / indem: nəticədə (nəticə) və ya … etməklə; {END}")
add("relativsatz", "Relativsatz: a clause that describes a noun, starting with der, die, das …; {END}", "Relativsatz: mệnh đề mô tả danh từ, bắt đầu bằng der, die, das …; {END}",
    "Relativsatz: una oración que describe un sustantivo, empieza con der, die, das …; {END}", "Relativsatz: uma oração que descreve um substantivo, começa com der, die, das …; {END}",
    "Relativsatz: 名詞を説明する節で、der, die, das … で始まります。{END}", "Relativsatz: 명사를 설명하는 절로 der, die, das …로 시작합니다. {END}", "Relativsatz:修饰名词的从句,以 der、die、das …开头;{END}",
    "Relativsatz: klausa yang menjelaskan kata benda, diawali der, die, das …; {END}", "Relativsatz: isimi izah edən cümlə, der, die, das … ilə başlayır; {END}")
add("indirekte_frage", "Indirect question: a question word inside a sentence; {END} (ich weiß nicht, wo er wohnt)", "Câu hỏi gián tiếp: từ để hỏi nằm trong câu; {END} (ich weiß nicht, wo er wohnt)",
    "Pregunta indirecta: una palabra interrogativa dentro de una oración; {END} (ich weiß nicht, wo er wohnt)", "Pergunta indireta: uma palavra interrogativa dentro da frase; {END} (ich weiß nicht, wo er wohnt)",
    "間接疑問文: 文中の疑問詞。{END}(ich weiß nicht, wo er wohnt)", "간접 의문문: 문장 안의 의문사. {END}(ich weiß nicht, wo er wohnt)", "间接疑问句:句中的疑问词;{END}(ich weiß nicht, wo er wohnt)",
    "Pertanyaan tidak langsung: kata tanya di dalam kalimat; {END} (ich weiß nicht, wo er wohnt)", "Dolayı sual: cümlə daxilində sual sözü; {END} (ich weiß nicht, wo er wohnt)")
add("w_frage", "W-Frage: a question word first, then the verb (wo wohnst du?)", "W-Frage: từ để hỏi đứng đầu, rồi đến động từ (wo wohnst du?)", "W-Frage: primero la palabra interrogativa, luego el verbo (wo wohnst du?)",
    "W-Frage: primeiro a palavra interrogativa, depois o verbo (wo wohnst du?)", "W-Frage: 疑問詞が先、そのあとに動詞(wo wohnst du?)", "W-Frage: 의문사가 먼저, 그다음 동사(wo wohnst du?)", "W-Frage:疑问词在前,然后是动词(wo wohnst du?)",
    "W-Frage: kata tanya di depan, lalu kata kerja (wo wohnst du?)", "W-Frage: əvvəl sual sözü, sonra fel (wo wohnst du?)")
add("ja_nein_frage", "Ja/Nein-Frage: a yes/no question puts the verb first (kommst du?)", "Ja/Nein-Frage: câu hỏi có/không đặt động từ lên đầu (kommst du?)", "Ja/Nein-Frage: una pregunta de sí o no pone el verbo primero (kommst du?)",
    "Ja/Nein-Frage: uma pergunta de sim ou não põe o verbo primeiro (kommst du?)", "Ja/Nein-Frage: はい/いいえで答える疑問文は動詞を最初に置きます(kommst du?)", "Ja/Nein-Frage: 예/아니요 의문문은 동사를 맨 앞에 둡니다(kommst du?)",
    "Ja/Nein-Frage:是非问句把动词放在句首(kommst du?)", "Ja/Nein-Frage: pertanyaan ya/tidak meletakkan kata kerja di depan (kommst du?)", "Ja/Nein-Frage: bəli/xeyr sualında fel birinci gəlir (kommst du?)")
add("inversion", "Inversion: after an adverb or another first element, the verb stays second and the subject follows (heute gehe ich)",
    "Inversion: sau trạng từ hoặc thành phần đầu câu khác, động từ vẫn đứng thứ hai và chủ ngữ theo sau (heute gehe ich)",
    "Inversion: tras un adverbio u otro primer elemento, el verbo sigue en segundo lugar y el sujeto va después (heute gehe ich)",
    "Inversion: depois de um advérbio ou outro primeiro elemento, o verbo fica em segundo lugar e o sujeito vem depois (heute gehe ich)",
    "Inversion: 副詞などが文頭に来ても動詞は2番目のままで、主語がそのあとに来ます(heute gehe ich)",
    "Inversion: 부사 등이 문장 앞에 와도 동사는 두 번째에 두고 주어가 뒤따릅니다(heute gehe ich)", "Inversion:副词等放在句首时,动词仍在第二位,主语随后(heute gehe ich)",
    "Inversion: setelah kata keterangan atau unsur pertama lain, kata kerja tetap di posisi kedua dan subjek mengikuti (heute gehe ich)",
    "Inversion: zərf və ya başqa ilk elementdən sonra fel ikinci yerdə qalır, subyekt ardınca gəlir (heute gehe ich)")
add("koordination", "und, aber, oder, denn, sondern: join two clauses without changing the word order", "und, aber, oder, denn, sondern: nối hai mệnh đề mà không đổi trật tự từ",
    "und, aber, oder, denn, sondern: unen dos oraciones sin cambiar el orden de las palabras", "und, aber, oder, denn, sondern: unem duas orações sem mudar a ordem das palavras",
    "und, aber, oder, denn, sondern: 語順を変えずに2つの節をつなぎます", "und, aber, oder, denn, sondern: 어순을 바꾸지 않고 두 절을 연결합니다", "und、aber、oder、denn、sondern:连接两个分句,不改变语序",
    "und, aber, oder, denn, sondern: menghubungkan dua klausa tanpa mengubah urutan kata", "und, aber, oder, denn, sondern: söz sırasını dəyişmədən iki cümləni birləşdirir")
add("nicht", "nicht: not; it negates the verb, the sentence or the word after it", "nicht: không; phủ định động từ, cả câu hoặc từ đứng sau", "nicht: no; niega el verbo, la oración o la palabra siguiente",
    "nicht: não; nega o verbo, a frase ou a palavra seguinte", "nicht: 〜ない。動詞・文全体・直後の語を否定します", "nicht: 아니다. 동사, 문장 전체, 또는 뒤의 단어를 부정합니다", "nicht:不;否定动词、整句或后面的词",
    "nicht: tidak; menolak kata kerja, seluruh kalimat, atau kata sesudahnya", "nicht: deyil; feli, bütün cümləni və ya sonrakı sözü inkar edir")
add("kein", "kein-: not a, no; it negates a noun and takes the endings of ein", "kein-: không có; phủ định danh từ và có đuôi như ein", "kein-: ningún, no hay; niega un sustantivo y toma las terminaciones de ein",
    "kein-: nenhum; nega um substantivo e tem as terminações de ein", "kein-: 〜がない。名詞を否定し、ein と同じ語尾を取ります", "kein-: ~이 없다. 명사를 부정하며 ein과 같은 어미를 씁니다", "kein-:没有;否定名词,词尾与 ein 相同",
    "kein-: tidak ada; menolak kata benda dan memakai akhiran ein", "kein-: heç bir; isimi inkar edir və ein şəkilçilərini alır")
add("es_gibt", "es gibt + Akkusativ: there is, there are (es gibt einen Bus)", "es gibt + Akkusativ: có (es gibt einen Bus)", "es gibt + Akkusativ: hay (es gibt einen Bus)",
    "es gibt + Akkusativ: há, existe (es gibt einen Bus)", "es gibt + Akkusativ: 〜がある(es gibt einen Bus)", "es gibt + Akkusativ: ~이 있다(es gibt einen Bus)", "es gibt + Akkusativ:有……(es gibt einen Bus)",
    "es gibt + Akkusativ: ada (es gibt einen Bus)", "es gibt + Akkusativ: var (es gibt einen Bus)")
add("konditional_irreal", "Unreal condition: wenn + Konjunktiv II, then würde or Konjunktiv II (wenn ich Zeit hätte, würde ich kommen)",
    "Điều kiện không có thật: wenn + Konjunktiv II, rồi würde hoặc Konjunktiv II (wenn ich Zeit hätte, würde ich kommen)",
    "Condición irreal: wenn + Konjunktiv II, luego würde o Konjunktiv II (wenn ich Zeit hätte, würde ich kommen)",
    "Condição irreal: wenn + Konjunktiv II, depois würde ou Konjunktiv II (wenn ich Zeit hätte, würde ich kommen)",
    "非現実の条件: wenn + Konjunktiv II、続いて würde または Konjunktiv II(wenn ich Zeit hätte, würde ich kommen)",
    "비현실 조건: wenn + Konjunktiv II, 이어서 würde 또는 Konjunktiv II(wenn ich Zeit hätte, würde ich kommen)",
    "非真实条件:wenn + Konjunktiv II,接着是 würde 或 Konjunktiv II(wenn ich Zeit hätte, würde ich kommen)",
    "Syarat tidak nyata: wenn + Konjunktiv II, lalu würde atau Konjunktiv II (wenn ich Zeit hätte, würde ich kommen)",
    "Qeyri-real şərt: wenn + Konjunktiv II, sonra würde və ya Konjunktiv II (wenn ich Zeit hätte, würde ich kommen)")

add("adjektiv_prep_akkusativ", "Adjective with a fixed preposition and Akkusativ (stolz auf + Akk.): learn the preposition together with the adjective",
    "Tính từ đi với giới từ cố định và Akkusativ (stolz auf + Akk.): học giới từ cùng với tính từ", "Adjetivo con preposición fija y Akkusativ (stolz auf + Akk.): aprende la preposición junto con el adjetivo",
    "Adjetivo com preposição fixa e Akkusativ (stolz auf + Akk.): aprenda a preposição junto com o adjetivo", "決まった前置詞と Akkusativ を取る形容詞(stolz auf + Akk.): 前置詞も形容詞と一緒に覚えましょう",
    "정해진 전치사와 Akkusativ를 쓰는 형용사(stolz auf + Akk.): 전치사를 형용사와 함께 외우세요", "固定介词加 Akkusativ 的形容词(stolz auf + Akk.):把介词和形容词一起记",
    "Kata sifat dengan preposisi tetap dan Akkusativ (stolz auf + Akk.): pelajari preposisi bersama kata sifatnya", "Sabit ön şəkilçili və Akkusativ ilə sifət (stolz auf + Akk.): ön şəkilçini sifətlə birlikdə öyrənin")
add("adjektiv_prep_dativ", "Adjective with a fixed preposition and Dativ (zufrieden mit + Dat.): learn the preposition together with the adjective",
    "Tính từ đi với giới từ cố định và Dativ (zufrieden mit + Dat.): học giới từ cùng với tính từ", "Adjetivo con preposición fija y Dativ (zufrieden mit + Dat.): aprende la preposición junto con el adjetivo",
    "Adjetivo com preposição fixa e Dativ (zufrieden mit + Dat.): aprenda a preposição junto com o adjetivo", "決まった前置詞と Dativ を取る形容詞(zufrieden mit + Dat.): 前置詞も形容詞と一緒に覚えましょう",
    "정해진 전치사와 Dativ를 쓰는 형용사(zufrieden mit + Dat.): 전치사를 형용사와 함께 외우세요", "固定介词加 Dativ 的形容词(zufrieden mit + Dat.):把介词和形容词一起记",
    "Kata sifat dengan preposisi tetap dan Dativ (zufrieden mit + Dat.): pelajari preposisi bersama kata sifatnya", "Sabit ön şəkilçili və Dativ ilə sifət (zufrieden mit + Dat.): ön şəkilçini sifətlə birlikdə öyrənin")

# Labels of the word card's German details and the tentative marker. kind: (default text, per-language, translatable)
LABELS = {
    "german_word_lemma": {"en": "Dictionary form: %1$s", "vi": "Dạng từ điển: %1$s", "es": "Forma del diccionario: %1$s", "pt": "Forma do dicionário: %1$s", "ja": "辞書形: %1$s", "ko": "사전형: %1$s", "zh": "词典形式:%1$s", "in": "Bentuk kamus: %1$s", "az": "Lüğət forması: %1$s"},
    "german_word_noun": {"en": "Noun, %1$s", "vi": "Danh từ, %1$s", "es": "Sustantivo, %1$s", "pt": "Substantivo, %1$s", "ja": "名詞、%1$s", "ko": "명사, %1$s", "zh": "名词,%1$s", "in": "Kata benda, %1$s", "az": "İsim, %1$s"},
    "german_word_masculine": {"en": "masculine", "vi": "giống đực", "es": "masculino", "pt": "masculino", "ja": "男性", "ko": "남성", "zh": "阳性", "in": "maskulin", "az": "kişi cinsi"},
    "german_word_feminine": {"en": "feminine", "vi": "giống cái", "es": "femenino", "pt": "feminino", "ja": "女性", "ko": "여성", "zh": "阴性", "in": "feminin", "az": "qadın cinsi"},
    "german_word_neuter": {"en": "neuter", "vi": "giống trung", "es": "neutro", "pt": "neutro", "ja": "中性", "ko": "중성", "zh": "中性", "in": "netral", "az": "orta cins"},
    "german_word_plural": {"en": "plural %1$s", "vi": "số nhiều %1$s", "es": "plural %1$s", "pt": "plural %1$s", "ja": "複数形 %1$s", "ko": "복수 %1$s", "zh": "复数 %1$s", "in": "jamak %1$s", "az": "cəm %1$s"},
    "german_word_verb": {"en": "Verb, %1$s", "vi": "Động từ, %1$s", "es": "Verbo, %1$s", "pt": "Verbo, %1$s", "ja": "動詞、%1$s", "ko": "동사, %1$s", "zh": "动词,%1$s", "in": "Kata kerja, %1$s", "az": "Fel, %1$s"},
    "german_word_adjective": {"en": "Adjective, %1$s", "vi": "Tính từ, %1$s", "es": "Adjetivo, %1$s", "pt": "Adjetivo, %1$s", "ja": "形容詞、%1$s", "ko": "형용사, %1$s", "zh": "形容词,%1$s", "in": "Kata sifat, %1$s", "az": "Sifət, %1$s"},
    "german_word_auxiliary": {"en": "perfect with %1$s", "vi": "thì hoàn thành với %1$s", "es": "perfecto con %1$s", "pt": "perfeito com %1$s", "ja": "完了形は %1$s", "ko": "완료형은 %1$s", "zh": "完成时用 %1$s", "in": "perfekt dengan %1$s", "az": "perfekt %1$s ilə"},
    "german_word_separable": {"en": "separable verb (%1$s-)", "vi": "động từ tách được (%1$s-)", "es": "verbo separable (%1$s-)", "pt": "verbo separável (%1$s-)", "ja": "分離動詞(%1$s-)", "ko": "분리동사(%1$s-)", "zh": "可分动词(%1$s-)", "in": "kata kerja terpisah (%1$s-)", "az": "ayrılan fel (%1$s-)"},
    "german_word_reflexive": {"en": "can be reflexive", "vi": "có thể là phản thân", "es": "puede ser reflexivo", "pt": "pode ser reflexivo", "ja": "再帰的にも使えます", "ko": "재귀 용법이 있습니다", "zh": "可作反身动词", "in": "bisa refleksif", "az": "qayıdış ola bilər"},
    "german_word_modal": {"en": "modal verb", "vi": "động từ khuyết thiếu", "es": "verbo modal", "pt": "verbo modal", "ja": "助動詞", "ko": "화법조동사", "zh": "情态动词", "in": "kata kerja modal", "az": "modal fel"},
    "grammar_tentative": {"en": "Likely, a guess from the sentence", "vi": "Có thể, đoán từ câu", "es": "Probable, una suposición según la frase", "pt": "Provável, uma suposição pela frase", "ja": "推定(文からの推測)", "ko": "추정(문장에서의 추측)", "zh": "可能,根据句子推测", "in": "Kemungkinan, dugaan dari kalimat", "az": "Ehtimal, cümlədən təxmin"},
}

# German terms and formats that stay the same in every language.
FIXED = {
    "german_case_nominative": "Nominativ", "german_case_genitive": "Genitiv", "german_case_dative": "Dativ", "german_case_accusative": "Akkusativ",
    "german_tense_present": "Präsens", "german_tense_preterite": "Präteritum", "german_tense_subjunctive_i": "Konjunktiv I", "german_tense_subjunctive_ii": "Konjunktiv II",
    "german_tense_imperative": "Imperativ", "german_tense_participle_i": "Partizip I", "german_tense_participle_ii": "Partizip II", "german_tense_infinitive": "Infinitiv",
    "german_word_person": "%1$s. Person %2$s", "german_word_singular": "Singular", "german_word_plural_number": "Plural",
    "german_word_noun_genitive": "Genitiv: %1$s",
    "german_feature_singular": "%1$s Sg. %2$s", "german_feature_plural": "%1$s Pl.",
    "german_gender_letter_masculine": "m.", "german_gender_letter_feminine": "f.", "german_gender_letter_neuter": "n.",
    "german_degree_comparative": "Komparativ", "german_degree_superlative": "Superlativ",
    "german_article_masculine": "der", "german_article_feminine": "die", "german_article_neuter": "das",
}


def attr(text):
    # Android strings: escape apostrophes and quotes, keep ASCII otherwise.
    s = escape(text)
    return s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("@", "\\@")


def write(lang):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    if lang == "en":
        lines.append("    <!-- German grammar points for learners of German. German terms and examples stay German; only the")
        lines.append("         explanation is translated. Non-English files are machine-drafted and NOT reviewed by a German teacher. -->")
    else:
        lines.append("    <!-- Machine-drafted, not reviewed by a German teacher. German terms and examples stay German. -->")
    for name, texts in T.items():
        lines.append(f'    <string name="grammar_de_{name}">{attr(texts[lang].replace("{END}", END[lang]))}</string>')
    for name, texts in LABELS.items():
        lines.append(f'    <string name="{name}">{attr(texts[lang])}</string>')
    if lang == "en":
        for name, text in FIXED.items():
            lines.append(f'    <string name="{name}" translatable="false">{attr(text)}</string>')
    lines.append("</resources>")
    path = f"{OUT}/res/{FOLDER[lang]}/strings_grammar_de.xml"
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")


for lang in LANGS:
    write(lang)

kt = ["package com.kienhoang.dualsubreplay.ui", "", "import com.kienhoang.dualsubreplay.R", "import com.kienhoang.dualsubreplay.data.GrammarMeaning", "",
      "/** How each German grammar point reads to the learner (strings_grammar_de.xml). */", "internal val GERMAN_GRAMMAR_TEXT: Map<GrammarMeaning, Int> =", "    mapOf("]
for name in T:
    kt.append(f"        GrammarMeaning.DE_{name.upper()} to R.string.grammar_de_{name},")
kt += ["    )", ""]
open(f"{OUT}/java/com/kienhoang/dualsubreplay/ui/GermanGrammarText.kt", "w", encoding="utf-8", newline="\n").write("\n".join(kt))
print(len(T), "points,", len(LABELS), "labels")
