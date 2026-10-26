"""Derive bounded world and repository counts without hand-maintained totals."""
import hashlib
import json
import re
from pathlib import Path


def summarize(world):
    people = world['characters']
    return {
        'places': len(world['nodes']),
        'surveyedPlaces': sum(place['known'] for place in world['nodes']),
        'unverifiedPlaces': sum(not place['known'] for place in world['nodes']),
        'routes': len(world['edges']),
        'surveyedRoutes': sum(edge['known'] for edge in world['edges']),
        'unverifiedRoutes': sum(not edge['known'] for edge in world['edges']),
        'characters': len(people),
        'personalityProfiles': sum('personality' in person for person in people),
        'quotes': sum(len(person['quotes']) for person in people),
        'quotesByPerson': {person['id']: len(person['quotes']) for person in people},
        'terms': len(world['lexicon']),
        'adoptedTerms': sum(term['canonStatus'] == 'confirmed' for term in world['lexicon']),
        'stationHearsayTerms': sum(term['canonStatus'] == 'confirmed' and term['status'] == 'rumor' for term in world['lexicon']),
        'proposalExtensions': sum('proposal' in term for term in world['lexicon']),
        'dailyNotes': len(world['dailyLines']),
        'surfaceNotes': len(world['surfaceNotes']),
        'chronicles': len(world['chronicles']),
        'chapters': len(world['chapters']),
        'letters': sum(len(chapter['letters']) for chapter in world['chapters']),
        'applicationNames': len(world['appNames']),
    }


def repository_snapshot(root, world):
    root = Path(root)
    notes = sorted((root / 'releases').glob('v*.md'))
    bodies = [path.read_text() for path in notes]
    return {
        'version': json.loads((root / 'version.json').read_text()),
        'atlasSha256': hashlib.sha256((root / 'app/src/main/res/raw/world_atlas.json').read_bytes()).hexdigest(),
        'counts': summarize(world),
        'repository': {
            'releaseNoteFiles': len(notes),
            'scavengerLetterFiles': sum('拾荒者信笺' in body or '拾荒者短笺' in body for body in bodies),
            'attributedLetterFiles': sum(bool(re.search(r'^>.*(?:阿汐|迟灯|弥途|Axi|Chideng|Mitu)', body, re.M)) for body in bodies),
        },
    }


def render_statistics(snapshot):
    c = snapshot['counts']
    rows = [
        ('地理节点', c['places'], f"{c['surveyedPlaces']} 处路标已核，{c['unverifiedPlaces']} 处位置或路线待核"),
        ('路线联系', c['routes'], f"{c['surveyedRoutes']} 条实线，{c['unverifiedRoutes']} 条虚线"),
        ('角色与性格', c['characters'], f"{c['personalityProfiles']} 份双语性格侧写"),
        ('角色语录', c['quotes'], '；'.join(f'{person} {count}' for person, count in c['quotesByPerson'].items())),
        ('风物词条', c['terms'], f"{c['adoptedTerms']} 条正式采用，{c['terms'] - c['adoptedTerms']} 条物种创作；正式设定中 {c['stationHearsayTerms']} 条仍属站内传闻"),
        ('提案扩展', c['proposalExtensions'], '只统计词条附带的未定细节，不把整条正式设定降为提案'),
        ('双语值班札记', c['dailyNotes'], '每则含稳定 ID 和中英文本；按日期轮换'),
        ('双语场景寄语', c['surfaceNotes'], '本机档案库告示、空观看记录值夜寄语；不计入角色语录'),
        ('版本足迹', c['chronicles'], '版本选录，不是完整世界年表'),
        ('章节与记录', c['chapters'], f"共 {c['letters']} 封，保留第一、二章及分章阅读位置"),
        ('应用称谓', c['applicationNames'], '应用隐喻与真实功能分别解释'),
    ]
    version = snapshot['version']
    lines = ['# 废土世界统计', '',
             f"基于随包数据与版本 `{version['versionName']}` / 安装序号 `{version['versionCode']}` 自动生成。", '',
             '生成：`python3 -B scripts/worldbook.py --write-statistics`。统计数字不从旧报告复制。', '',
             '| 维度 | 数量 | 口径 |', '| --- | ---: | --- |']
    lines += [f'| {name} | {count} | {meaning} |' for name, count, meaning in rows]
    lines += ['', '## 仓库文件另行统计', '',
              f"仓库中有 {snapshot['repository']['releaseNoteFiles']} 份版本说明文件，其中 {snapshot['repository']['scavengerLetterFiles']} 份标有拾荒者信笺或短笺、{snapshot['repository']['attributedLetterFiles']} 份含角色署名引语。这是源码文件统计，不等同于 GitHub 已发布版本数量。", '',
              '## 正史与调查是两个维度', '',
              '用户于 2026-10-22 明确采用地下放映站、核爆引发长潮、共生生态、觉醒感知与理想城贸易。地图的已核标志仍只表示站内调查程度。理想城的存在与贸易为正式设定，位置和行路证据仍待核实；人物台词不替代实地记录。', '',
              f"数据摘要：`{snapshot['atlasSha256']}`。", '']
    return '\n'.join(lines)
