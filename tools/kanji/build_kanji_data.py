#!/usr/bin/env python3
"""Builds the word card's kanji data files from KANJIDIC2 and KanjiVG.

Writes, into app/src/main/learner-data/:
  ja-kanji.txt          one line per kanji: readings, meanings, stroke count, grade, parts
  ja-kanji-strokes.txt  one line per kanji: KanjiVG's stroke paths in stroke order

Usage:
  python3 tools/kanji/build_kanji_data.py --kanjidic kanjidic2.xml.gz --kanjivg kanjivg/kanji

Sources (both CC BY-SA, so the two output files are CC BY-SA too; the app's code stays MIT):
  KANJIDIC2  https://www.edrdg.org/kanjidic/kanjidic2.xml.gz  (EDRDG, CC BY-SA 4.0)
  KanjiVG    https://github.com/KanjiVG/kanjivg tag KANJIVG_TAG  (Ulrich Apel, CC BY-SA 3.0)
"""

from __future__ import annotations

import argparse
import gzip
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

KANJIVG_TAG = "r20260714"
KVG = "{http://kanjivg.tagaini.net}"
SVG = "{http://www.w3.org/2000/svg}"
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "app" / "src" / "main" / "learner-data"
RADICALS = OUTPUT / "ja-radicals.txt"

# School grades 1-6 and 8 are the 2,136 jōyō kanji; 9 and 10 are jinmeiyō (names).
LAST_GRADE = 10

# Hán Việt readings KANJIDIC2 gets wrong or lacks for the Japanese form; each replaces its list (checked 2026-10-24).
HAN_VIET_FIXES = {
    "戦": ["Chiến"],
    "巣": ["Sào"],
    "条": ["Điều"],
    "姫": ["Cơ"],
    "弐": ["Nhị"],
    "払": ["Phất"],
    "択": ["Trạch"],
    "摂": ["Nhiếp"],
    "浜": ["Tân"],
    "犠": ["Hi"],
    "錬": ["Luyện"],
    "鶏": ["Kê"],
    "醸": ["Nhưỡng"],
    "陥": ["Hãm"],
    "𠮟": ["Sất"],
}


@dataclass
class Kanji:
    literal: str
    grade: int
    strokes: int
    jlpt: int
    radical: int
    vietnamese: list[str]
    on: list[str]
    kun: list[str]
    meanings: list[str]
    parts: list[str] = field(default_factory=list)
    paths: list[str] = field(default_factory=list)


def read_kanjidic(path: Path) -> tuple[dict[str, Kanji], str]:
    root = ET.parse(gzip.open(path)).getroot()
    version = root.findtext("header/database_version", "")
    found: dict[str, Kanji] = {}
    for character in root.iter("character"):
        grade = character.findtext("misc/grade")
        if grade is None or int(grade) > LAST_GRADE:
            continue
        literal = character.findtext("literal")
        group = character.find("reading_meaning/rmgroup")
        readings = group.findall("reading") if group is not None else []
        meanings = group.findall("meaning") if group is not None else []
        found[literal] = Kanji(
            literal=literal,
            grade=int(grade),
            strokes=int(character.findtext("misc/stroke_count")),
            jlpt=int(character.findtext("misc/jlpt") or 0),
            radical=int(character.find("radical/rad_value[@rad_type='classical']").text),
            vietnamese=HAN_VIET_FIXES.get(literal, [r.text for r in readings if r.get("r_type") == "vietnam"]),
            on=[r.text for r in readings if r.get("r_type") == "ja_on"],
            kun=[r.text for r in readings if r.get("r_type") == "ja_kun"],
            meanings=[m.text for m in meanings if m.get("m_lang") is None],
        )
    return found, version


def read_radical_forms(path: Path) -> dict[str, int]:
    forms: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        number, shapes, *_ = line.split("\t")
        for shape in shapes.split(" "):
            forms.setdefault(shape, int(number))
    return forms


def part_meaningful(element: str, kanji: dict[str, Kanji], radicals: dict[str, int]) -> bool:
    return element in kanji or element in radicals or element == "阝"


def kanji_parts(group: ET.Element, kanji: dict[str, Kanji], radicals: dict[str, int]) -> list[str]:
    """The kanji's parts that have a meaning to show: the first level KanjiVG names, opening a part
    with no meaning of its own (such as 枼 in 葉) into its own parts. Each part is written as
    `shape[=original][:flags]`, flags r (the kanji's radical) and p (the part that gives the sound)."""
    parts: list[str] = []

    def visit(node: ET.Element, sound: bool) -> None:
        for child in node.findall(f"{SVG}g"):
            element = child.get(f"{KVG}element")
            if element is None:
                visit(child, sound)
                continue
            phon = child.get(f"{KVG}phon") is not None
            original = child.get(f"{KVG}original")
            children = child.findall(f"{SVG}g")
            if not part_meaningful(element, kanji, radicals) and not (original and original in radicals) and children:
                visit(child, phon)
                continue
            flags = ""
            if child.get(f"{KVG}radical") in ("general", "tradit", "nelson"):
                flags += "r"
            if phon:
                flags += "p"
            if element == "阝" and not original:
                original = "阜" if child.get(f"{KVG}position") == "left" else "邑"
            text = element + (f"={original}" if original and original != element else "") + (f":{flags}" if flags else "")
            if not any(p.split("=")[0].split(":")[0] == element for p in parts):
                parts.append(text)

    visit(group, False)
    return parts


def read_kanjivg(directory: Path, kanji: dict[str, Kanji], radicals: dict[str, int]) -> None:
    for item in kanji.values():
        code = f"{ord(item.literal):05x}"
        file = directory / f"{code}.svg"
        if not file.exists():
            continue
        root = ET.parse(file).getroot()
        strokes = root.find(f".//{SVG}g[@id='kvg:StrokePaths_{code}']")
        top = strokes.find(f"{SVG}g")
        item.paths = [compact_path(p.get("d")) for p in top.iter(f"{SVG}path")]
        # KanjiVG draws the 2010 jōyō table's forms (two-dot ⻌, 牙 in 4 strokes), where KANJIDIC2
        # counts another form, so the count follows the strokes the page draws.
        item.strokes = len(item.paths)
        item.parts = kanji_parts(top, kanji, radicals)


def compact_path(d: str) -> str:
    """The same path with fewer characters: no spaces next to commands or before a minus sign."""
    d = re.sub(r"\s*([MmCcSsLlZzHhVvQqTt])\s*", r"\1", d.strip())
    d = re.sub(r"\s*,\s*", ",", d)
    return re.sub(r"\s+-", "-", d)


def write(kanji: dict[str, Kanji], version: str) -> None:
    ordered = sorted(kanji.values(), key=lambda k: (k.grade, ord(k.literal)))
    header = (
        "# Kanji from KANJIDIC2 (database version {v}), Copyright James William Breen and the Electronic\n"
        "# Dictionary Research and Development Group, CC BY-SA 4.0 (https://www.edrdg.org/edrdg/licence.html),\n"
        "# with parts from KanjiVG {tag} (Copyright Ulrich Apel, CC BY-SA 3.0, https://kanjivg.tagaini.net).\n"
        "# This file is shared under CC BY-SA 4.0. Made by tools/kanji/build_kanji_data.py.\n"
    ).format(v=version, tag=KANJIVG_TAG)
    lines = [
        "# Format (tab separated): kanji, Hán Việt readings, strokes, grade (1-6, 8 jōyō; 9-10 jinmeiyō),\n"
        "# old JLPT level (0 none), Kangxi radical number, on readings, kun readings, meanings (;), parts.\n"
    ]
    for k in ordered:
        lines.append(
            "\t".join(
                [
                    k.literal,
                    ",".join(k.vietnamese),
                    str(k.strokes),
                    str(k.grade),
                    str(k.jlpt),
                    str(k.radical),
                    ",".join(k.on),
                    ",".join(k.kun),
                    ";".join(m.replace(";", ",") for m in k.meanings),
                    " ".join(k.parts),
                ]
            )
            + "\n"
        )
    (OUTPUT / "ja-kanji.txt").write_text(header + "".join(lines), encoding="utf-8")
    strokes_header = (
        "# Stroke order from KanjiVG {tag}, Copyright Ulrich Apel and contributors, CC BY-SA 3.0\n"
        "# (https://kanjivg.tagaini.net). Shared under the same licence. Format: kanji, then each stroke's\n"
        "# SVG path in a 109 x 109 box, in stroke order, separated by tabs. Made by tools/kanji/build_kanji_data.py.\n"
    ).format(tag=KANJIVG_TAG)
    strokes = ["\t".join([k.literal, *k.paths]) + "\n" for k in ordered if k.paths]
    (OUTPUT / "ja-kanji-strokes.txt").write_text(strokes_header + "".join(strokes), encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--kanjidic", type=Path, required=True)
    parser.add_argument("--kanjivg", type=Path, required=True, help="KanjiVG's kanji/ folder")
    args = parser.parse_args(argv)
    kanji, version = read_kanjidic(args.kanjidic)
    read_kanjivg(args.kanjivg, kanji, read_radical_forms(RADICALS))
    write(kanji, version)
    missing = [k.literal for k in kanji.values() if not k.paths and k.grade <= 8]
    if missing:
        print("No KanjiVG strokes for jōyō kanji:", "".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
