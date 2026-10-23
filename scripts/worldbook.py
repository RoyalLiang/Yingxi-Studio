#!/usr/bin/env python3
"""Validate the versioned original story atlas shared by the app and release writers."""
import argparse
import datetime
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
        if 'mapLabel' in p:text(p['mapLabel'])
        if not all(0<=p[k]<=1 for k in ['x','y']):raise ValueError('World node outside map')
    for p in world['characters']:
        for key in ['name','role','description','personality']:text(p[key])
        if p['home'] not in places or p['portrait'] not in ['scavenger','keeper','wayfinder'] or not p['quotes']:raise ValueError('Incomplete character')
        for q in p['quotes']:text(q)
    lexicon=world.get('lexicon')
    if not isinstance(lexicon,list) or not lexicon:raise ValueError('World lexicon must be nonempty')
    term_ids=set()
    for entry in lexicon:
        ident=entry.get('id')
        if not isinstance(ident,str) or not ident.strip() or ident in term_ids:raise ValueError('Invalid or duplicate lexicon identity')
        term_ids.add(ident)
        if entry.get('canonStatus') not in {'confirmed','proposal'}:raise ValueError('Invalid canon adoption status')
        if entry.get('status') not in {'confirmed','rumor'}:raise ValueError('Invalid lexicon status; extensions belong in proposal')
        for key in ['category','name','summary']:text(entry[key])
        if 'proposal' in entry:text(entry['proposal'])
        place=next((p for p in world['nodes'] if p['id']==ident),None)
        if place and (entry['status']=='confirmed')!=place['known']:raise ValueError('Lexicon contradicts map knowledge')
    for edge in world['edges']:
        if edge['from'] not in places or edge['to'] not in places:raise ValueError('Unknown world edge endpoint')
        text(edge['label'])
    versions=set()
    for chapter in world['chronicles']:
        if chapter['version'] in versions:raise ValueError('Duplicate chronicle identity')
        versions.add(chapter['version']);text(chapter['text'])
    adoption=world['canonAdoption']
    if adoption['basis']!='user-confirmed':raise ValueError('Unknown canon adoption source')
    datetime.date.fromisoformat(adoption['date'])
    text(adoption['note'])
    for key in ['dailyLines','surfaceNotes']:
        if not isinstance(world.get(key),list) or not world[key]:raise ValueError('Empty world corpus')
        identities=set()
        for item in world[key]:
            if not item['id'] or item['id'] in identities:raise ValueError('Duplicate corpus identity')
            identities.add(item['id'])
            if item['author'] not in people:raise ValueError('Unknown corpus author')
            for field in (['text'] if key=='dailyLines' else ['eyebrow','title','body']):text(item[field])
    if {item['id'] for item in world['surfaceNotes']}!={'local_archive','history_empty','first_light'}:raise ValueError('Missing surface note')
    if not isinstance(world.get('chapters'),list) or not world['chapters']:raise ValueError('Chapters must be a nonempty list')
    chapter_ids=set();letter_ids=set()
    for chapter in world['chapters']:
        if not chapter['id'] or chapter['id'] in chapter_ids:raise ValueError('Duplicate chapter identity')
        chapter_ids.add(chapter['id'])
        text(chapter['title']);text(chapter['introduction'])
        if not isinstance(chapter.get('letters'),list) or not chapter['letters']:raise ValueError('Empty chapter')
        for letter in chapter['letters']:
            if not letter['id'] or letter['id'] in letter_ids:raise ValueError('Duplicate letter identity')
            letter_ids.add(letter['id'])
            if letter['author'] not in people or letter['place'] not in places:raise ValueError('Unknown letter reference')
            text(letter['title']);text(letter['body'])
    app_ids=set()
    for item in world['appNames']:
        if item['id'] in app_ids:raise ValueError('Duplicate application name identity')
        app_ids.add(item['id']);text(item['name']);text(item['meaning'])

    rounds=world.get('practiceRounds')
    if not isinstance(rounds,list) or len(rounds)!=6:raise ValueError('Six practice rounds required')
    round_ids=set()
    for item in rounds:
        if not isinstance(item.get('id'),str) or not item['id'].strip() or item['id'] in round_ids:raise ValueError('Duplicate or invalid practice round identity')
        round_ids.add(item['id'])
        if type(item.get('revision')) is not int or item['revision']<1:raise ValueError('Invalid practice revision')
        text(item['title']);text(item['note'])
        if not item.get('references') or not set(item['references'])<=term_ids:raise ValueError('Unknown practice reference')
        pairs=item.get('pairs')
        if not isinstance(pairs,list) or len(pairs)!=3:raise ValueError('Three practice pairs required')
        pair_ids=set()
        for pair in pairs:
            if not isinstance(pair.get('id'),str) or not pair['id'].strip() or pair['id'] in pair_ids:raise ValueError('Duplicate or invalid practice pair identity')
            pair_ids.add(pair['id'])
            for key in ['title','clue','label','hint']:text(pair[key])
    signals=world.get('tuningSignals')
    if not isinstance(signals,list) or len(signals)!=6:raise ValueError('Six tuning signals required')
    signal_ids=set();diary_ids={item['id'] for item in world['dailyLines']}
    for item in signals:
        if not isinstance(item.get('id'),str) or not item['id'].strip() or item['id'] in signal_ids:raise ValueError('Duplicate or invalid tuning identity')
        signal_ids.add(item['id']);text(item['title'])
        if type(item.get('revision')) is not int or item['revision']<1:raise ValueError('Invalid tuning revision')
        if item.get('line') not in diary_ids:raise ValueError('Unknown tuning note')
        if not item.get('references') or not set(item['references'])<=term_ids:raise ValueError('Unknown tuning reference')
        if type(item.get('target')) is not int or not 10<=item['target']<=90 or type(item.get('start')) is not int or not 0<=item['start']<=100 or abs(item['start']-item['target'])<=12:raise ValueError('Invalid tuning marks')
    readings=world.get('readingRounds')
    if not isinstance(readings,list) or len(readings)!=6:raise ValueError('Six reading rounds required')
    reading_ids=set();record_ids=set();reading_places=set()
    terms={item['id']:item for item in lexicon}
    for item in readings:
        if not isinstance(item.get('id'),str) or not item['id'].strip() or item['id'] in reading_ids:raise ValueError('Invalid reading round identity')
        reading_ids.add(item['id']);text(item['title'])
        if type(item.get('revision')) is not int or item['revision']<1:raise ValueError('Invalid reading revision')
        if item.get('place') not in places or item['place'] in reading_places:raise ValueError('Invalid reading place')
        reading_places.add(item['place'])
        records=item.get('records')
        if not isinstance(records,list) or len(records)!=2:raise ValueError('Two reading records required')
        for record in records:
            if not isinstance(record.get('id'),str) or not record['id'].strip() or record['id'] in record_ids:raise ValueError('Invalid reading record identity')
            record_ids.add(record['id']);text(record['claim']);text(record['reason'])
            if record.get('verdict') not in {'Confirmed','Pending'}:raise ValueError('Invalid reading verdict')
            term=terms.get(record.get('term'))
            if term is None or record.get('field') not in {'summary','proposal'} or record['field'] not in term:raise ValueError('Unknown reading source')
            if record['field']=='proposal' and record['verdict']!='Pending':raise ValueError('Reading proposal must remain pending')
    return world

def render_glossary(world):
    lines=["# 映汐世界观名词表", "", "本表由随包图谱生成，应用探索页的「废土词典」读取相同数据。修改定义时先维护图谱，再运行 `python3 -B scripts/worldbook.py --write-glossary`。", "", "2026-10-22 用户明确采用核爆长潮、地下放映站、聚落生态、孢子机制、觉醒感知及理想城贸易。正式设定与站内证据分别列示：地点存在不等于角色已走通路线。演进提案仍未成为事实。", "", "人物、地点衔接、矛盾处理及第二章内容见[世界书](worldbook.md)。第一、二章均可手动阅读，分别保留阅读位置；第二章纸条的寄信人和所述设备情况仍未证实。", "", "| 稳定标识 | 名词 | English | 分类 | 正式采用 | 站内证据 | 定义 | 演进提案 |", "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    def cell(value):return value.replace('|',r'\|').replace('\n',' ')
    for item in world['lexicon']:
        values=[item['id'],item['name']['zh'],item['name']['en'],item['category']['zh'],'正式设定' if item['canonStatus']=='confirmed' else '演进提案','站内传闻' if item['status']=='rumor' else '已知记录',item['summary']['zh'],item.get('proposal',{}).get('zh','—')]
        lines.append('| '+' | '.join(cell(value) for value in values)+' |')
    lines+=['','## 应用名称与故事实体分开解释','','应用统一为「映汐放映站」，保留「避难所」「废墟档案库」等废土风格名称。远程媒体通过「幸存者据点」（SMB/SFTP）与「外部信号塔」（网盘/WebDAV）分别登记，「废墟档案库」指本机影像；故事中的避难所指实体聚居据点。「档案室」收录人物、性格、语录与据点大事记，章节来信在「探索」中阅读；大事记按应用版本选录变化，「相伴第 N 天」按本地首次安装日期计天。','','阿汐给旧片罐补写侧签、迟灯照看设备，属于既有据点日常；地下建筑已由用户采用，路线调查仍单独记录。「封存柜」是应用内私密目录与剧集登记，不移动或加密原文件。','', '「今日拾获」是实际浏览目录时新记录的媒体；「信号破译中」保留「正在缓冲」的读屏说明。这些名称不代表物资掉落、文件修复、觉醒能力或章节解锁。','']
    lines.extend(['', '## 应用称谓与功能边界', '', '| 稳定标识 | 中文 | English | 功能含义 | Meaning |', '| --- | --- | --- | --- | --- |'])
    for item in world['appNames']:
        lines.append('| '+' | '.join(cell(value) for value in [item['id'],item['name']['zh'],item['name']['en'],item['meaning']['zh'],item['meaning']['en']])+' |')
    lines.extend(['', '## 采用层级', '', '词条等统计数字由[统计页](world-statistics.md)计算。canonStatus 表示作者采用；status 表示站内证据。地下站、核爆长潮、共生生态与理想城贸易已正式采用；虚线路标和未署名回信仍保留各自未知部分。', ''])
    return '\n'.join(lines)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-glossary',action='store_true')
    parser.add_argument('--write-statistics',action='store_true')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    data=validate(root)
    target=root/'docs/world-glossary.md'
    expected=render_glossary(data)
    if args.write_glossary:target.write_text(expected)
    elif not target.is_file() or target.read_text()!=expected:raise SystemExit('World glossary is stale; run scripts/worldbook.py --write-glossary')
    from world_statistics import repository_snapshot, render_statistics
    snapshot=repository_snapshot(root,data)
    outputs={root/'docs/world-statistics.json':json.dumps(snapshot,ensure_ascii=False,indent=2)+'\n',root/'docs/world-statistics.md':render_statistics(snapshot)}
    for output,content in outputs.items():
        if args.write_statistics:output.write_text(content)
        elif not output.is_file() or output.read_text()!=content:raise SystemExit('World statistics drift; run python3 -B scripts/worldbook.py --write-statistics')
    print(f"World atlas valid: {len(data['characters'])} characters, {len(data['nodes'])} places, {len(data['lexicon'])} terms")
