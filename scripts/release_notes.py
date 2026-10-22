#!/usr/bin/env python3
"""Validate user-facing bilingual field letters; retain historical note readability."""
import argparse
import re
from pathlib import Path
from versioning import load

def legacy_version(version):
    match = re.fullmatch(r"0\.2\.0-alpha\.(\d+)", version["versionName"])
    return bool(match and int(match[1]) <= 14)

def load_notes(root, version):
    text = (Path(root) / "releases" / f'{version["tag"]}.md').read_text(encoding="utf-8").strip()
    if not any(text.startswith(f'# {name} {version["versionName"]} / Yingxi {version["versionName"]}')
               for name in ("映汐放映站", "映汐", "映汐播放器", "映汐放映室")):
        raise ValueError("Release notes title must match version.json")
    headings = list(re.finditer(r"^## (.+)$", text, re.MULTILINE))
    if [h[1] for h in headings] != ["中文", "English"]:
        raise ValueError("Release notes require exactly ## 中文 and ## English, in order")
    chinese = text[headings[0].end():headings[1].start()]
    english = text[headings[1].end():]
    if not re.search(r"[\u4e00-\u9fff]", re.sub(r"^#+.*$", "", chinese, flags=re.MULTILINE)):
        raise ValueError("Chinese notes must contain Chinese text")
    if not re.search(r"[A-Za-z]{3,}", re.sub(r"^#+.*$", "", english, flags=re.MULTILINE)):
        raise ValueError("English notes must contain English text")
    if re.search(r"\b(?:TODO|TBD|FIXME)\b|待补充|待填写", text, re.IGNORECASE):
        raise ValueError("Release notes must not contain unfinished placeholders")
    if not legacy_version(version):
        for body, allowed in (
            (chinese, {"更新内容", "本次实装", "修缮记录", "放映站手记"}),
            (english, {"Changes", "New arrivals", "Repairs", "Station journal"}),
        ):
            if any(h not in allowed for h in re.findall(r"^### (.+)$", body, re.MULTILINE)):
                raise ValueError("New field letters contain changes only; validation belongs in CI and delivery records")
            if re.search(r"(?im)^\s*(?:#{1,6}\s*|【|\*\*)(?:验证|限制|已知限制|Validation|Limitations|Known limitations)", body):
                raise ValueError("Do not publish validation or limitations sections in field letters")
            prose = re.sub(r"(?m)^#+.*$", "", body).strip()
            prose = re.sub(r"(?im)^\s*(?:【[^】]+】|\[[^]]+]|拾荒者信笺[：:]?|Scavenger letter:?)\s*$", "", prose).strip()
            if len(prose) < 8:
                raise ValueError("Field letters require substantive release content")
        if not re.search(r"拾荒|信笺|放映站", chinese) or not re.search(r"scavenger|letter|projection station", english, re.IGNORECASE):
            raise ValueError("New notes must retain the scavenger field-letter voice; see docs/worldbook.md")
    atlas=Path(root)/"app/src/main/res/raw/world_atlas.json"
    if atlas.is_file():
        from worldbook import validate
        world=validate(root)
        names={p["name"][language] for p in world["characters"] for language in ("zh","en")}
        for line in text.splitlines():
            if line.startswith("> "):
                speaker=re.search(r"(?:——| — )([^\n]+)$",line)
                if speaker is None or speaker[1].strip() not in names:
                    raise ValueError("Character quotations must be attributed to a known atlas character")
    match=re.fullmatch(r"0\.2\.0-alpha\.(\d+)",version["versionName"])
    if not match or int(match[1])>=25:
        validate_character_epilogue(chinese)
        validate_character_epilogue(english)
    return text + "\n"

def validate_character_epilogue(body):
    """Keep character updates out of the change list; preserve published historical letters."""
    lines=[line.strip() for line in body.splitlines() if line.strip()]
    for line in lines:
        if re.match(r"^(?:[-*] |\d+[.、] )",line) and re.search(
            r"人物|角色|肖像|阿汐|迟灯|弥途|\b(?:characters?|Axi|Chideng|Mitu)\b",line,re.IGNORECASE):
            raise ValueError("Character updates belong only in the final attributed epilogue")
    quotes=[i for i,line in enumerate(lines) if line.startswith("> ")]
    if quotes and any(not line.startswith("> ") for line in lines[min(quotes):]):
        raise ValueError("Character dialogue belongs at the end of each language's field letter")


def render_notes(root, version, source_commit):
    text = load_notes(root, version)
    if legacy_version(version) and "### 验证与限制" in text:
        return text + f'\n---\n\n版本 / Version: {version["versionName"]} · Android versionCode: {version["versionCode"]}\n\n源码提交 / Source commit: {source_commit}\n'
    return text.split("\n", 1)[1].lstrip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    load_notes(args.root, load(args.root))
    print("Bilingual field-letter rules validated")

if __name__ == "__main__":
    main()
