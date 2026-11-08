#!/usr/bin/env python3
"""Regenerate android/src/main/assets/emoji/<lang>.tsv from Unicode CLDR.

Run it from anywhere, when a newer CLDR or emoji version is wanted (update the versions below):

    python scripts/generate-emoji-tables.py

Each output line is "<emoji>\\t<spoken name>". Variation selectors (U+FE0F) are removed from the
keys, because the app removes them from the text before matching. CLDR's annotations also name
plain symbols such as + and %, so only what emoji-test.txt lists as an emoji is kept.
"""
import json
import pathlib
import urllib.request

CLDR_TAG = "49.0.0"
UNICODE_VERSION = "18.0.0"
LANGUAGES = ["pt", "en", "es", "fr", "de"]

CLDR = f"https://raw.githubusercontent.com/unicode-org/cldr-json/{CLDR_TAG}/cldr-json"
EMOJI_TEST = f"https://www.unicode.org/Public/{UNICODE_VERSION}/emoji/emoji-test.txt"
OUT = pathlib.Path(__file__).resolve().parent.parent / "android/src/main/assets/emoji"


def fetch(url):
    with urllib.request.urlopen(url) as response:
        return response.read().decode("utf-8")


def strip(key):
    return key.replace("️", "")


def emoji_keys():
    keys = set()
    for line in fetch(EMOJI_TEST).splitlines():
        code = line.split(";")[0].strip()
        if code and not line.startswith("#"):
            keys.add(strip("".join(chr(int(c, 16)) for c in code.split())))
    return keys


def annotations(url, group):
    return json.loads(fetch(url))[group]["annotations"]


def main():
    wanted = emoji_keys()
    OUT.mkdir(parents=True, exist_ok=True)
    for lang in LANGUAGES:
        names = {}
        derived = annotations(f"{CLDR}/cldr-annotations-derived-full/annotationsDerived/{lang}/annotations.json", "annotationsDerived")
        main_table = annotations(f"{CLDR}/cldr-annotations-full/annotations/{lang}/annotations.json", "annotations")
        for table in (derived, main_table):  # The main table wins.
            for key, value in table.items():
                spoken = value.get("tts")
                if spoken and strip(key) in wanted:
                    names[strip(key)] = spoken[0] if isinstance(spoken, list) else spoken
        rows = "".join(f"{k}\t{v}\n" for k, v in sorted(names.items()))
        (OUT / f"{lang}.tsv").write_text(rows, encoding="utf-8", newline="\n")
        print(f"{lang}: {len(names)} of {len(wanted)} emoji")


if __name__ == "__main__":
    main()
