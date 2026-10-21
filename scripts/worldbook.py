#!/usr/bin/env python3
"""Validate the versioned original story atlas shared by the app and release writers."""
import argparse
import json
from pathlib import Path

def validate(root):
    world=json.loads((Path(root)/'app/src/main/res/raw/world_atlas.json').read_text())
    if world['schemaVersion']!=1:raise ValueError('Unknown world atlas schema')
    def text(item):
        if set(item)!={'zh','en'} or not all(isinstance(v,str) and v.strip() for v in item.values()):raise ValueError('World text must contain complete Chinese and English')
    for key in ['chapter','premise','boundary']:text(world[key])
    places={p['id'] for p in world['nodes']}
    people={p['id'] for p in world['characters']}
    if len(places)!=len(world['nodes']) or len(people)!=len(world['characters']):raise ValueError('Duplicate world identity')
    for p in world['nodes']:
        for key in ['name','kind','description']:text(p[key])
        if not all(0<=p[k]<=1 for k in ['x','y']):raise ValueError('World node outside map')
    for p in world['characters']:
        for key in ['name','role','description']:text(p[key])
        if p['home'] not in places or p['portrait'] not in ['scavenger','keeper','wayfinder'] or not p['quotes']:raise ValueError('Incomplete character')
        for q in p['quotes']:text(q)
    lexicon=world.get('lexicon')
    if not isinstance(lexicon,list) or not lexicon:raise ValueError('World lexicon must be nonempty')
    term_ids=set()
    for entry in lexicon:
        ident=entry.get('id')
        if not isinstance(ident,str) or not ident.strip() or ident in term_ids:raise ValueError('Invalid or duplicate lexicon identity')
        term_ids.add(ident)
        if entry.get('status') not in {'confirmed','rumor'}:raise ValueError('Invalid lexicon status; extensions belong in proposal')
        for key in ['category','name','summary']:text(entry[key])
        if 'proposal' in entry:text(entry['proposal'])
        place=next((p for p in world['nodes'] if p['id']==ident),None)
        if place and (entry['status']=='confirmed')!=place['known']:raise ValueError('Lexicon contradicts map knowledge')
    for edge in world['edges']:
        if edge['from'] not in places or edge['to'] not in places:raise ValueError('Unknown world edge endpoint')
        text(edge['label'])
    for chapter in world['chronicles']:text(chapter['text'])
    return world

def render_glossary(world):
    lines=["# 映汐世界观名词表", "", "本表由随包图谱生成，应用探索页的「废土词典」读取相同数据。修改定义时先维护图谱，再运行 `python3 -B scripts/worldbook.py --write-glossary`。", "", "确认层级只约束词条正文；「演进提案」单独列示，尚未成为既定事实。核爆起源及新增聚居、生命概念来自 2026-10-21 用户确认。具体制度、机制、年代与路线仍按各条标注处理。", "", "人物、地点衔接、矛盾处理及第二章方案见[世界书](worldbook.md)。第二章当前没有剧情数据或解锁入口。", "", "| 名词 | English | 分类 | 确认层级 | 定义 | 演进提案 |", "| --- | --- | --- | --- | --- | --- |"]
    def cell(value):return value.replace('|',r'\|').replace('\n',' ')
    for item in world['lexicon']:
        values=[item['name']['zh'],item['name']['en'],item['category']['zh'],'传闻' if item['status']=='rumor' else '已确认设定',item['summary']['zh'],item.get('proposal',{}).get('zh','—')]
        lines.append('| '+' | '.join(cell(value) for value in values)+' |')
    lines+=['','## 应用名称与故事实体分开解释','','应用保留「避难所」「外部避难所」「废墟档案库」等废土风格名称。前两者指远程媒体连接，后者指本机影像；故事中的避难所指实体聚居据点。「档案室」指人物与来信，「据点大事记」按应用版本选录变化，「相伴第 N 天」按本地首次安装日期计天。','','「今日拾获」是实际浏览目录时新记录的媒体；「信号破译中」保留「正在缓冲」的读屏说明。这些名称不代表物资掉落、文件修复、觉醒能力或章节解锁。','']
    return '\n'.join(lines)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-glossary',action='store_true')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    data=validate(root)
    target=root/'docs/world-glossary.md'
    expected=render_glossary(data)
    if args.write_glossary:target.write_text(expected)
    elif not target.is_file() or target.read_text()!=expected:raise SystemExit('World glossary is stale; run scripts/worldbook.py --write-glossary')
    print(f"World atlas valid: {len(data['characters'])} characters, {len(data['nodes'])} places, {len(data['lexicon'])} terms")
