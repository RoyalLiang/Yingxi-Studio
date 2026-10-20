#!/usr/bin/env python3
"""Require reviewed Chinese and English notes for each released version."""
import argparse
import re
from pathlib import Path
from versioning import load


def load_notes(root, version):
    path = root / "releases" / f'{version["tag"]}.md'
    text = path.read_text(encoding="utf-8").strip()
    if not any(text.startswith(f'# {name} {version["versionName"]} / Yingxi {version["versionName"]}\n') for name in ('映汐', '映汐播放器')):
        raise ValueError("Release notes title must match version.json")
    headings = list(re.finditer(r"^## (.+)$", text, re.MULTILINE))
    if [heading[1] for heading in headings] != ["中文", "English"]:
        raise ValueError("Release notes require exactly ## 中文 and ## English, in that order")
    chinese = text[headings[0].end():headings[1].start()]
    english = text[headings[1].end():]
    for body, required in ((chinese, ("更新内容", "验证与限制")),
                           (english, ("Changes", "Validation and limitations"))):
        sections = re.split(r"^### (.+)$", body, flags=re.MULTILINE)
        if sections[1::2] != list(required):
            raise ValueError("Each language must describe changes and validation/limitations")
        if any(not re.search(r"^- \S.+", section, re.MULTILINE) for section in sections[2::2]):
            raise ValueError("Every release note section requires substantive bullet points")
    if not re.search(r"[\u4e00-\u9fff]", re.sub(r"^#+.*$", "", chinese, flags=re.MULTILINE)):
        raise ValueError("Chinese notes must contain Chinese text")
    if not re.search(r"[A-Za-z]{3,}", re.sub(r"^#+.*$", "", english, flags=re.MULTILINE)):
        raise ValueError("English notes must contain English text")
    if re.search(r"\b(?:TODO|TBD|FIXME)\b|待补充|待填写", text, re.IGNORECASE):
        raise ValueError("Release notes must not contain unfinished placeholders")
    return text + "\n"


def render_notes(root, version, source_commit):
    return (load_notes(root, version)
            + f'\n---\n\n版本 / Version: `{version["versionName"]}` · '
              f'Android versionCode: `{version["versionCode"]}`\n\n'
            + f'源码提交 / Source commit: `{source_commit}`\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    load_notes(args.root, load(args.root))
    print("Bilingual release notes validated")


if __name__ == "__main__":
    main()
