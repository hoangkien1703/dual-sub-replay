#!/usr/bin/env python3
"""Builds the word card's Chinese character data files.

Writes, into app/src/main/learner-data/:
  zh-hanzi.txt          one line per character: pinyin, strokes, HSK level, other forms, radical,
                        meanings, parts (meaning and sound part marked), example words
  zh-hanzi-strokes.txt  one line per character: each stroke's outline and centre line, in stroke order
  zh-hanviet.txt        one line per character: its Hán Việt readings

The characters are the 3,000 of the HSK 3.0 syllabus, their traditional forms, and the parts they
are built from (parts get facts but no strokes).

Usage:
  python3 tools/hanzi/build_hanzi_data.py --makemeahanzi makemeahanzi/ --hsk hsk30-chars.csv \\
      --unihan Unihan.zip --kanjidic kanjidic2.xml.gz

Sources (each output file keeps the licence of what it is made from; the app's code stays MIT):
  Make Me a Hanzi  https://github.com/skishore/makemeahanzi commit MAKEMEAHANZI_COMMIT
                   dictionary.txt (LGPL 3 or later) -> zh-hanzi.txt
                   graphics.txt (Arphic Public License) -> zh-hanzi-strokes.txt
  HSK 3.0 list     https://github.com/ivankra/hsk30 commit HSK30_COMMIT, hsk30-chars.csv (MIT)
  Unihan           https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip (Unicode licence)
  KANJIDIC2        https://www.edrdg.org/kanjidic/kanjidic2.xml.gz (EDRDG, CC BY-SA 4.0) -> zh-hanviet.txt
"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

MAKEMEAHANZI_COMMIT = "bddc96d41bef78427ed0e034e9f7e31d71fd1b92"
HSK30_COMMIT = "4ff9e3915ce87baaecd7ebe263085573a4ea3192"
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "app" / "src" / "main" / "learner-data"
RADICALS = OUTPUT / "ja-radicals.txt"
EXAMPLE_WORDS = 4
IDS_BINARY = set("⿰⿱⿴⿵⿶⿷⿸⿹⿺⿻")
IDS_TERNARY = set("⿲⿳")

# Hán Việt for HSK characters where KANJIDIC2's first reading (through the traditional form) is a
# rarer reading or a chữ Nôm one, or where there is none (checked by hand, 2026-10-24). Each
# replaces the whole list; the first reading is the one shown on rows.
HAN_VIET_FIXES = {
    "干": ["Can", "Cán"],
    "家": ["Gia"],
    "机": ["Cơ"],
    "几": ["Kỷ"],
    "姐": ["Thư"],
    "了": ["Liễu"],
    "妈": ["Ma"],
    "哪": ["Na"],
    "您": ["Nâm"],
    "钱": ["Tiền"],
    "山": ["Sơn"],
    "生": ["Sinh"],
    "时": ["Thời", "Thì"],
    "说": ["Thuyết"],
    "岁": ["Tuế"],
    "她": ["Tha"],
    "校": ["Hiệu", "Giáo"],
    "找": ["Trảo"],
    "正": ["Chính"],
    "板": ["Bản"],
    "办": ["Biện"],
    "份": ["Phần"],
    "复": ["Phục", "Phức"],
    "够": ["Cấu"],
    "号": ["Hiệu", "Hào"],
    "卡": ["Tạp"],
    "乐": ["Lạc", "Nhạc"],
    "示": ["Thị"],
    "数": ["Số"],
    "司": ["Tư"],
    "向": ["Hướng"],
    "椅": ["Ỷ"],
    "于": ["Vu", "Ư"],
    "脏": ["Tạng", "Tang"],
    "占": ["Chiếm"],
    "只": ["Chỉ", "Chích"],
    "产": ["Sản"],
    "朝": ["Triều", "Triêu"],
    "传": ["Truyền", "Truyện"],
    "创": ["Sáng"],
    "访": ["Phỏng"],
    "赶": ["Cản"],
    "汇": ["Hối", "Vựng"],
    "伙": ["Hỏa"],
    "圾": ["Sắc"],
    "济": ["Tế"],
    "尽": ["Tận"],
    "绝": ["Tuyệt"],
    "录": ["Lục"],
    "每": ["Mỗi"],
    "内": ["Nội"],
    "啤": ["Bi"],
    "苹": ["Bình"],
    "属": ["Thuộc"],
    "退": ["Thoái"],
    "乡": ["Hương"],
    "幸": ["Hạnh"],
    "值": ["Trị"],
    "担": ["Đảm"],
    "劲": ["Kình"],
    "棵": ["Khỏa"],
    "矿": ["Khoáng"],
    "扩": ["Khoáng"],
    "料": ["Liệu"],
    "摸": ["Mô"],
    "闹": ["Náo"],
    "晒": ["Sái"],
    "刷": ["Loát"],
    "帅": ["Soái"],
    "松": ["Tùng"],
    "梯": ["Thê"],
    "挑": ["Khiêu"],
    "脱": ["Thoát"],
    "县": ["Huyện"],
    "阅": ["Duyệt"],
    "折": ["Chiết"],
    "政": ["Chính"],
    "治": ["Trị"],
    "综": ["Tổng"],
    "本": ["Bản"],
    "差": ["Sai", "Sai"],
    "场": ["Trường"],
    "比": ["Tỉ"],
    "躲": ["Đóa"],
    "仿": ["Phỏng"],
    "幅": ["Bức"],
    "厉": ["Lệ"],
    "怜": ["Lân"],
    "拼": ["Bính"],
    "抢": ["Thương"],
    "傻": ["Xọa"],
    "偷": ["Thâu"],
    "抬": ["Đài"],
    "闲": ["Nhàn"],
    "饮": ["Ẩm"],
    "踩": ["Thải"],
    "档": ["Đương"],
    "滴": ["Trích"],
    "佛": ["Phật"],
    "耗": ["Hao"],
    "搬": ["Ban"],
    "彩": ["Thái", "Thải"],
    "任": ["Nhiệm", "Nhậm"],
    "刺": ["Thích", "Thứ"],
    "柿": ["Thị"],
    "爆": ["Bạo", "Bộc"],
    "刊": ["San"],
    "泡": ["Bào", "Phao"],
    "煎": ["Tiễn", "Tiên"],
    "汰": ["Thải", "Thái"],
    "屯": ["Đồn", "Truân"],
    "圾": ["Ngập"],
    "裹": ["Khỏa"],
    "扩": ["Khuếch"],
    "降": ["Giáng", "Hàng"],
    "弹": ["Đạn", "Đàn"],
    "丑": ["Xú", "Sửu"],
    "储": ["Trữ"],
    "档": ["Đáng"],
    "箭": ["Tiễn"],
    "援": ["Viện", "Viên"],
    "允": ["Doãn", "Duẫn"],
    "盹": [],
    "踹": [],
    "捶": ["Chùy"],
    "锤": ["Chùy"],
    "讥": ["Cơ"],
    "垮": [],
    "剖": ["Phẫu"],
    "歧": ["Kỳ"],
    "刨": ["Bào"],
    "俏": ["Tiếu"],
    "甩": [],
    "捅": ["Thống"],
    "哑": ["Á"],
    "狠": ["Ngận"],
    "啦": ["Lạp"],
    "赖": ["Lại"],
    "懒": ["Lãn"],
    "盼": ["Phán"],
    "税": ["Thuế"],
    "探": ["Thám"],
    "踢": ["Thích"],
    "吞": ["Thôn"],
    "拖": ["Tha"],
    "挖": ["Oạt"],
    "娱": ["Ngu"],
    "氧": ["Dưỡng"],
    "症": ["Chứng"],
    "扒": ["Bát"],
    "霸": ["Bá"],
    "拌": ["Bạn"],
    "掺": ["Sam"],
    "禅": ["Thiền"],
    "橱": ["Trù"],
    "歹": ["Đãi"],
    "缔": ["Đế"],
    "巅": ["Điên"],
    "盯": ["Đinh"],
    "贩": ["Phán"],
    "咐": ["Phó"],
    "溉": ["Khái"],
    "尴": ["Giam"],
    "缸": ["Cang"],
    "杠": ["Giang"],
    "恒": ["Hằng"],
    "呵": ["Ha"],
    "阂": ["Ngại"],
    "唬": ["Hổ"],
    "焊": ["Hàn"],
    "谎": ["Hoảng"],
    "桨": ["Tưởng"],
    "沮": ["Trở"],
    "楷": ["Khải"],
    "砍": ["Khảm"],
    "坑": ["Khanh"],
    "捆": ["Khổn"],
    "蜡": ["Lạp"],
    "溜": ["Lưu"],
    "胧": ["Lung"],
    "铝": ["Lữ"],
    "屡": ["Lũ"],
    "庞": ["Bàng"],
    "撇": ["Phiết"],
    "锐": ["Nhuệ"],
    "伺": ["Tứ"],
    "惕": ["Dịch"],
    "颓": ["Đồi"],
    "秃": ["Thốc"],
    "紊": ["Vấn"],
    "涡": ["Oa"],
    "泄": ["Tiết"],
    "佣": ["Dung"],
    "庸": ["Dung"],
    "御": ["Ngự"],
    "吁": ["Hu"],
    "皂": ["Tạo"],
    "眨": ["Trát"],
    "蘸": ["Trám"],
    "睁": ["Tranh"],
    "桩": ["Trang"],
}


@dataclass
class Hanzi:
    literal: str
    pinyin: list[str]
    level: int  # HSK 3.0 level 1-6, 7 for the 7-9 band, 0 for a part outside the syllabus
    meanings: list[str]
    radical: str
    parts: list[str]
    others: list[str] = field(default_factory=list)  # traditional forms of a simplified one, or the reverse
    words: list[str] = field(default_factory=list)
    strokes: list[str] = field(default_factory=list)
    medians: list[str] = field(default_factory=list)
    stroke_count: int = 0
    han_viet: list[str] = field(default_factory=list)
    hsk: bool = False  # one of the syllabus's own (simplified) characters


def read_json_lines(path: Path) -> dict[str, dict]:
    with path.open(encoding="utf-8") as lines:
        return {entry["character"]: entry for entry in map(json.loads, lines)}


def read_unihan(path: Path) -> dict[str, dict[str, str]]:
    found: dict[str, dict[str, str]] = {}
    with zipfile.ZipFile(path) as archive:
        for name in ("Unihan_Readings.txt", "Unihan_Variants.txt"):
            for line in archive.read(name).decode("utf-8").splitlines():
                if not line.startswith("U+"):
                    continue
                code, key, value = line.split("\t", 2)
                found.setdefault(chr(int(code[2:], 16)), {})[key] = value
    return found


def unihan_forms(unihan: dict[str, dict[str, str]], literal: str, key: str) -> list[str]:
    return [chr(int(code[2:], 16)) for code in unihan.get(literal, {}).get(key, "").split()]


def read_kanjidic_vietnamese(path: Path) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for character in ET.parse(gzip.open(path)).getroot().iter("character"):
        readings = [unicodedata.normalize("NFC", r.text) for r in character.iter("reading") if r.get("r_type") == "vietnam"]
        if readings:
            found[character.findtext("literal")] = readings
    return found


def read_radical_forms(path: Path) -> set[str]:
    forms: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            forms.update(line.split("\t")[1].split(" "))
    return forms


def ids_parts(decomposition: str) -> list[str]:
    """The parts of an ideographic description such as ⿰女马: the first level's parts, with a
    nested description (⿱艹⿰…) opened into its own parts; unknown parts (？) left out."""
    position = 0

    def node() -> list[list[str]]:
        nonlocal position
        if position >= len(decomposition):
            return []
        symbol = decomposition[position]
        position += 1
        if symbol in IDS_BINARY or symbol in IDS_TERNARY:
            children = [node() for _ in range(3 if symbol in IDS_TERNARY else 2)]
            return [[leaf for child in children for group in child for leaf in group]]
        return [[symbol]]

    root = decomposition[0] if decomposition else ""
    if root not in IDS_BINARY and root not in IDS_TERNARY:
        return []
    position = 1
    parts: list[str] = []
    for _ in range(3 if root in IDS_TERNARY else 2):
        for group in node():
            for leaf in group:
                if leaf != "？" and leaf not in parts:
                    parts.append(leaf)
    return parts


def part_flags(entry: dict, part: str) -> str:
    etymology = entry.get("etymology", {})
    flags = ""
    if part == entry.get("radical"):
        flags += "r"
    if part == etymology.get("semantic"):
        flags += "m"
    if part == etymology.get("phonetic"):
        flags += "s"
    return flags


def meanings_of(entry: dict | None, unihan: dict[str, str]) -> list[str]:
    text = (entry or {}).get("definition") or unihan.get("kDefinition", "")
    return [m.strip() for m in text.split(";") if m.strip()]


def make_hanzi(literal: str, level: int, dictionary: dict[str, dict], unihan: dict[str, dict[str, str]]) -> Hanzi:
    entry = dictionary.get(literal, {})
    readings = unihan.get(literal, {})
    parts = ids_parts(entry.get("decomposition", ""))
    return Hanzi(
        literal=literal,
        pinyin=entry.get("pinyin") or readings.get("kMandarin", "").split(),
        level=level,
        meanings=meanings_of(entry, readings),
        radical=entry.get("radical", ""),
        parts=[part + (f":{flags}" if (flags := part_flags(entry, part)) else "") for part in parts],
    )


def read_hsk(path: Path, dictionary: dict[str, dict], unihan: dict[str, dict[str, str]]) -> dict[str, Hanzi]:
    """The HSK characters in syllabus order, each simplified one followed by its traditional forms."""
    found: dict[str, Hanzi] = {}
    with path.open(encoding="utf-8") as file:
        for row in csv.DictReader(file):
            simplified = row["Hanzi"]
            level = int(row["Level"].split("-")[0])
            if row["Traditional"]:
                traditional = [t for t in row["Traditional"].split("/") if t != simplified]
            else:
                traditional = [t for t in unihan_forms(unihan, simplified, "kTraditionalVariant") if t != simplified]
            hanzi = found.get(simplified) or make_hanzi(simplified, level, dictionary, unihan)
            hanzi.level = min(hanzi.level or level, level)
            hanzi.hsk = True
            hanzi.others = traditional
            hanzi.words = [w for w in row["Examples"].split(" ") if len(w) > 1 and simplified in w][:EXAMPLE_WORDS]
            found[simplified] = hanzi
            for form in traditional:
                if form in found:
                    if simplified not in found[form].others:
                        found[form].others.append(simplified)
                    continue
                other = make_hanzi(form, level, dictionary, unihan)
                other.others = [simplified]
                found[form] = other
    return found


def add_parts(hanzi: dict[str, Hanzi], dictionary: dict[str, dict], unihan: dict[str, dict[str, str]]) -> None:
    """Facts for the parts the characters are built from, so a part shows its meaning and sound."""
    for item in list(hanzi.values()):
        for part in item.parts:
            shape = part.split(":")[0]
            if shape not in hanzi and (shape in dictionary or shape in unihan):
                hanzi[shape] = make_hanzi(shape, 0, dictionary, unihan)


def to_box(x: int, y: int) -> tuple[int, int]:
    # Make Me a Hanzi draws in a 1024 box with y pointing up from a baseline 900 units down;
    # the app's box is 512 units with y pointing down.
    return round(x / 2), round((900 - y) / 2)


def compact_outline(path: str) -> str:
    """The outline in the app's box with relative commands after the first point (they are shorter
    and compress better): `M258 46q-25 18-91 40…z`."""
    out: list[str] = []
    current = start = (0, 0)
    for command, arguments in re.findall(r"([MLQCZ])([^MLQCZ]*)", path):
        if command == "Z":
            out.append("z")
            current = start
            continue
        numbers = [int(n) for n in re.findall(r"-?\d+", arguments)]
        points = [to_box(numbers[i], numbers[i + 1]) for i in range(0, len(numbers), 2)]
        if command == "M":
            current = start = points[0]
            out.append(f"M{points[0][0]} {points[0][1]}")
            continue
        relative = " ".join(f"{x - current[0]} {y - current[1]}" for x, y in points)
        out.append(command.lower() + relative.replace(" -", "-"))
        current = points[-1]
    return "".join(out)


def compact_median(points: list[list[int]]) -> str:
    """The centre line's first point, then each step from the point before: `314,60 -34,-8 …`."""
    box = [to_box(x, y) for x, y in points]
    steps = [f"{box[0][0]},{box[0][1]}"] + [f"{b[0] - a[0]},{b[1] - a[1]}" for a, b in zip(box, box[1:])]
    return " ".join(steps)


def read_graphics(path: Path, hanzi: dict[str, Hanzi]) -> None:
    with path.open(encoding="utf-8") as lines:
        for entry in map(json.loads, lines):
            item = hanzi.get(entry["character"])
            if item is None:
                continue
            item.stroke_count = len(entry["strokes"])
            if item.level == 0:
                continue
            item.strokes = [compact_outline(stroke) for stroke in entry["strokes"]]
            item.medians = [compact_median(median) for median in entry["medians"]]


VIETNAMESE_TONES = "\u0300\u0301\u0303\u0309\u0323"


def modern_spelling(reading: str) -> str:
    """Puts the tone mark where current Vietnamese spelling does: Hoá -> Hóa, Thuý -> Thúy (not Quý)."""
    letters = unicodedata.normalize("NFD", reading)
    tone = next((mark for mark in letters if mark in VIETNAMESE_TONES), "")
    base = "".join(mark for mark in letters if mark not in VIETNAMESE_TONES)
    ending = base[-2:].lower()
    if not tone or ending not in ("oa", "oe", "uy") or base[-3:-2].lower() == "q":
        return unicodedata.normalize("NFC", letters)
    return unicodedata.normalize("NFC", base[:-1] + tone + base[-1])


def add_han_viet(hanzi: dict[str, Hanzi], kanjidic: dict[str, list[str]], unihan: dict[str, dict[str, str]]) -> None:
    """Hán Việt from KANJIDIC2 through the traditional form (simplified 叶 reads as 葉, Diệp), then
    Unihan; HSK characters' wrong or missing ones come from HAN_VIET_FIXES."""

    def first_reading(forms: list[str]) -> list[str]:
        readings = next((kanjidic[form] for form in forms if form in kanjidic), None)
        if readings is not None:
            return readings[:1]
        vietnamese = next((unihan[form]["kVietnamese"] for form in forms if unihan.get(form, {}).get("kVietnamese")), "")
        return [unicodedata.normalize("NFC", vietnamese.split(" ")[0].capitalize())] if vietnamese else []

    for item in hanzi.values():
        if item.literal in HAN_VIET_FIXES:
            item.han_viet = list(dict.fromkeys(HAN_VIET_FIXES[item.literal]))
        elif item.hsk:
            item.han_viet = [modern_spelling(r) for r in first_reading([*item.others, item.literal])]
        else:
            item.han_viet = [modern_spelling(r) for r in first_reading([item.literal])]
    # A traditional form reads like its simplified one when KANJIDIC2 lists that reading for it
    # (or lists none).
    for item in hanzi.values():
        if not item.hsk or not item.han_viet:
            continue
        for form in item.others:
            other = hanzi.get(form)
            if other is None or other.hsk:
                continue
            if not other.han_viet or item.han_viet[0] in kanjidic.get(form, []):
                other.han_viet = item.han_viet[:1]


def write(hanzi: dict[str, Hanzi]) -> None:
    ordered = [h for h in hanzi.values() if h.level > 0] + [h for h in hanzi.values() if h.level == 0]
    header = (
        "# Chinese characters for the word card's character pages, made by tools/hanzi/build_hanzi_data.py from\n"
        f"# Make Me a Hanzi's dictionary.txt (commit {MAKEMEAHANZI_COMMIT[:12]}, Shaunak Kishore, from Unihan\n"
        "# and CJKlib), shared under the GNU LGPL 3 or later (https://www.gnu.org/licenses/lgpl-3.0.html).\n"
        "# Changed on 2026-10-24 for DualSub Replay: kept to the characters the app shows, with HSK 3.0 levels\n"
        f"# and words from ivankra/hsk30 (commit {HSK30_COMMIT[:12]}, MIT) and forms from Unihan added.\n"
        "# Format (tab separated): character, pinyin (,), strokes, HSK level (1-6, 7 for 7-9, 0 a part only),\n"
        "# other forms (traditional of a simplified one, or the reverse), radical, meanings (;), parts\n"
        "# (flags r radical, m gives the meaning, s gives the sound), example words, and s for the syllabus's own\n"
        "# (simplified) characters or t for their traditional forms.\n"
    )
    lines = [
        "\t".join(
            [
                h.literal,
                ",".join(h.pinyin),
                str(h.stroke_count),
                str(h.level),
                ",".join(h.others),
                h.radical,
                ";".join(h.meanings),
                " ".join(h.parts),
                " ".join(h.words),
                "s" if h.hsk else ("t" if h.level > 0 else ""),
            ]
        )
        + "\n"
        for h in ordered
    ]
    (OUTPUT / "zh-hanzi.txt").write_text(header + "".join(lines), encoding="utf-8")
    strokes_header = (
        f"# Stroke order from Make Me a Hanzi's graphics.txt (commit {MAKEMEAHANZI_COMMIT[:12]}), derived from\n"
        "# Arphic PL KaitiM GB and Arphic PL UKai, Copyright 1999 Arphic Technology Co., Ltd., shared under the\n"
        "# Arphic Public License (its text is in assets/licenses/hanzi.txt). Changed on 2026-10-24 for DualSub\n"
        "# Replay by tools/hanzi/build_hanzi_data.py: kept to the HSK 3.0 characters and their traditional\n"
        "# forms, scaled to a 512-unit box with y pointing down, and written with relative path commands.\n"
        "# Format (tab separated): character, then per stroke in order its outline (SVG path) and centre line\n"
        "# (the first x,y point, then each step from the point before) joined by |, in a 512 x 512 box with y\n"
        "# pointing down.\n"
    )
    strokes = [
        "\t".join([h.literal, *(f"{o}|{m}" for o, m in zip(h.strokes, h.medians))]) + "\n" for h in ordered if h.strokes
    ]
    (OUTPUT / "zh-hanzi-strokes.txt").write_text(strokes_header + "".join(strokes), encoding="utf-8")
    han_viet_header = (
        "# Hán Việt readings of Chinese characters, made by tools/hanzi/build_hanzi_data.py from KANJIDIC2,\n"
        "# Copyright James William Breen and the Electronic Dictionary Research and Development Group,\n"
        "# CC BY-SA 4.0 (https://www.edrdg.org/edrdg/licence.html), and Unihan, with hand-checked fixes.\n"
        "# This file is shared under CC BY-SA 4.0. Format (tab separated): character, readings (,).\n"
    )
    han_viet = [f"{h.literal}\t{','.join(h.han_viet)}\n" for h in ordered if h.han_viet]
    (OUTPUT / "zh-hanviet.txt").write_text(han_viet_header + "".join(han_viet), encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--makemeahanzi", type=Path, required=True, help="folder with dictionary.txt and graphics.txt")
    parser.add_argument("--hsk", type=Path, required=True, help="hsk30-chars.csv")
    parser.add_argument("--unihan", type=Path, required=True, help="Unihan.zip")
    parser.add_argument("--kanjidic", type=Path, required=True, help="kanjidic2.xml.gz")
    args = parser.parse_args(argv)
    dictionary = read_json_lines(args.makemeahanzi / "dictionary.txt")
    unihan = read_unihan(args.unihan)
    hanzi = read_hsk(args.hsk, dictionary, unihan)
    add_parts(hanzi, dictionary, unihan)
    read_graphics(args.makemeahanzi / "graphics.txt", hanzi)
    add_han_viet(hanzi, read_kanjidic_vietnamese(args.kanjidic), unihan)
    write(hanzi)
    missing = [h.literal for h in hanzi.values() if h.level > 0 and not h.strokes]
    if missing:
        print("No strokes for:", "".join(missing), file=sys.stderr)
    return 1 if any(h.level > 0 and not h.strokes and h.literal in dictionary for h in hanzi.values()) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
