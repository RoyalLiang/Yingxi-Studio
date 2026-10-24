"""Render the public world archive from the canonical bundled atlas."""
from pathlib import Path
import json, html, re, sys
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
atlas = json.loads((root.parent / 'app/src/main/res/raw/world_atlas.json').read_text())
terms = {item['id']: item for item in atlas['lexicon']}
places = {item['id']: item for item in atlas['nodes']}
esc = html.escape
canonical = re.search(r'<link rel="canonical" href="([^"]+)"', (root / 'public/index.html').read_text())
if not canonical: raise SystemExit('Set the actual site canonical URL before exporting world archive')
parsed = urlsplit(canonical[1]); origin = parsed.scheme + '://' + parsed.netloc

def summary(item, sentences=None):
    text = item['summary']['zh']
    return text if sentences is None else '。'.join(text.split('。')[:sentences]).rstrip('。') + '。'

def term_row(key, cls='book-life'):
    item = terms[key]
    return f'<article class="{cls}" id="term-{key}"><h3>{esc(item["name"]["zh"])}</h3><p>{esc(summary(item))}</p></article>'

points = {key: (round(value['x'] * 900), round(value['y'] * 500)) for key, value in places.items()}
paths = []
for edge in atlas['edges']:
    x1, y1 = points[edge['from']]; x2, y2 = points[edge['to']]
    bend = {'archive': -120, 'sea': -170, 'ideal': 150, 'beacon': 150}.get(edge['to'], 0)
    d = f'M{x1},{y1} L{x2},{y2}' if edge['known'] else f'M{x1},{y1} C{x1+bend},{y1+80} {x2+bend},{y2-70} {x2},{y2}'
    paths.append(f'<path class="{"walked" if edge["known"] else "unverified"}" d="{d}"/>')
markers = []
for key, item in places.items():
    x, y = points[key]
    markers.append(f'<a href="#place-{key}" aria-label="阅读{esc(item["name"]["zh"])}"><rect class="map-hit" x="{x-110}" y="{y-30}" width="220" height="125"/><circle class="{"known-place" if item["known"] else "unknown-place"}" cx="{x}" cy="{y}" r="11"/><text x="{x}" y="{y+36}" text-anchor="middle">{esc(item["name"]["zh"])}</text></a>')
map_svg = '<svg viewBox="0 0 900 500" role="group" aria-label="放映站与盐环列站为实测往返信路，其余四处保留虚线线索">' + ''.join(paths + markers) + '</svg>'
place_html = ''.join(f'<article class="book-place" id="place-{key}"><p class="book-status">{"站内已知 · 信路已核" if item["known"] else "世界设定已采用 · 站内位置或路线待核"}</p><h3>{esc(item["name"]["zh"])}</h3><p>{esc(item["description"]["zh"])}</p></article>' for key, item in places.items())
resident_html = ''.join(f'<article class="book-resident" id="resident-{p["id"]}"><img src="../assets/{p["id"]}.webp" width="1254" height="1254" alt="{esc(p["name"]["zh"])}的原创肖像" loading="lazy"><div><p class="book-status">{esc(p["role"]["zh"])}</p><h3>{esc(p["name"]["zh"])}</h3><p>{esc(p["description"]["zh"])}</p></div></article>' for p in atlas['characters'])
glossary = ''.join(f'<details id="glossary-{item["id"]}"><summary>{esc(item["name"]["zh"])}<span aria-hidden="true">＋</span></summary><p>{esc(summary(item, 2))}</p></details>' for item in atlas['lexicon'])
chapters = ''.join(f'<li>{esc(chapter["title"]["zh"])}</li>' for chapter in atlas['chapters'])
content = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#111113"><title>世界档案 · 映汐放映站</title><meta name="description" content="从核爆长潮到废土纪元，阅读映汐的六处地点、生命谱系、三位住民与废土词典。"><link rel="icon" href="../assets/icon.webp" type="image/webp"><link rel="stylesheet" href="../style.css"><script src="../site.js" defer></script><link rel="canonical" href="{origin}/world/"><meta property="og:type" content="website"><meta property="og:locale" content="zh_CN"><meta property="og:site_name" content="映汐放映站"><meta property="og:title" content="世界档案 · 映汐放映站"><meta property="og:description" content="核爆长潮之后的纪元、聚居、生命谱系与放映站住民。"><meta property="og:url" content="{origin}/world/"></head>
<body class="world-book"><a class="skip-link" href="#main">跳到正文</a><header class="local-nav"><div class="nav-inner"><a class="brand" href="../"><img src="../assets/icon.webp" width="30" height="30" alt=""><span>映汐放映站</span></a><nav aria-label="主导航"><a href="../#highlights">播放器</a><a href="#atlas">世界图谱</a><a href="../support/">支持</a></nav><a class="pill small" href="../#download">下载映汐</a></div></header>
<main id="main"><section class="book-hero wrap"><div class="book-hero-copy"><p class="world-name">映汐的废土世界</p><h1>长潮之后，<br>仍有人留灯。</h1><p class="book-lead">核爆毁灭了文明纪元，长潮淹没旧城。<br>在旧铁路终点，有人捡回影像，修好机器，等一封回信。</p><nav class="book-contents" aria-label="世界档案目录"><a href="#eras">纪元与灾变</a><a href="#atlas">地点与信路</a><a href="#life">生命与共生</a><a href="#residents">放映站住民</a><a href="#boundaries">尚未写下的事</a><a href="#glossary">废土词典</a></nav></div><figure class="book-hero-portrait"><img src="../assets/axi.webp" width="1254" height="1254" alt="阿汐抱着旧片罐，守着放映站收存的影像" fetchpriority="high"><figcaption>阿汐 · 影像拾荒者</figcaption></figure></section>
<section id="eras" class="book-section wrap"><p class="world-name">纪元与灾变</p><h2>一场核火。<br>此后，漫长的潮水。</h2><div class="book-era"><article><h3>文明纪元</h3><p>服务器、磁盘、胶片和旧铁路，曾属于一个完整的文明。如今的能源塔、净水工程与地下掩体，仍留着它的轮廓。</p></article><article><h3>核爆与长潮</h3><p>核爆毁灭旧文明，继而引发地质与气候剧变。漫长海侵淹没旧城，重塑海岸；这条灾变链被合称为「核爆长潮」。</p></article><article><h3>废土纪元</h3><p>幸存者在避难所、停运列站和盐原高地重新生活。旧世界的影像被捡回、修补，也在新的放映室里继续被看见。</p></article></div><p class="book-note">精确年代、长潮持续年数与各地退潮时序仍留白。一次普通涨潮，不代表新的核爆。</p></section>
<section id="atlas" class="book-section wrap"><p class="world-name">地点与信路</p><h2>世界很大。<br>走过的路，慢慢记。</h2><p class="book-lead">地下避难所、幸存者聚落与理想城互相依存。<br>商队交换修配零件和旧纪元纪录片；来信带来消息，鞋底留下路标。</p><figure class="book-map">{map_svg}<figcaption><span class="map-key solid-key">实线：放映站—盐环列站往返信路</span><span class="map-key dotted-key">虚线：未核实的联系</span><span>点击地名阅读档案。此图为联系示意，不表示比例或可通行方向。</span></figcaption></figure><div class="book-places">{place_html}</div><p class="book-note">六处地点均属于正式世界设定。放映站掌握两处已知据点；另外四处的位置、到达路线或信号细节仍待核实。理想城的贸易往来，不代表弥途已经亲自走通全程。</p></section>
<section id="life" class="book-section wrap"><p class="world-name">生命与共生</p><h2>废墟里，<br>生命有了新的形状。</h2><div class="book-life-list">{''.join(term_row(k) for k in ['mutated_species','spores','mutants','awakened'])}</div><div class="book-ecology">{''.join(term_row(k, 'book-creature') for k in ['echo_seabirds','spore_moths','salt_pack_beasts'])}</div><p class="book-note">菌膜保护的是已有密封胶片盒。干爽的盒子，不代表整片废墟安全；感知频段，也不会让缺失的数据自动回来。能力的上限、代价与恢复条件仍待个体故事展开。</p></section>
<section id="residents" class="book-section wrap"><p class="world-name">放映站住民</p><h2>有人带回影像。<br>有人守住灯光。</h2><div class="book-residents">{resident_html}</div><div class="book-relations"><h3>一份登记，一次检修，一封来信。</h3><p>阿汐整理片罐与登记纸，迟灯修理机器、校准广播，弥途往返盐环列站。变种人搬运工与向导、路过的觉醒者，也与他们在打捞、送信和守夜中相遇。</p><p>他们的职业与日常已经清楚；各自的族群、身世和觉醒状态，仍没有被写定。</p></div></section>
<section id="boundaries" class="book-section wrap"><p class="world-name">故事的留白</p><h2>已知，写在纸上。<br>未知，留给回信。</h2><div class="book-boundaries"><article><h3>已经采用的世界</h3><p>纪元、核爆长潮、聚居形态、生命谱系，以及这里列出的地点，构成故事的共同背景。</p></article><article><h3>放映站知道的事</h3><p>走通的信路才画实线。商队口信、晴夜微光和广播频段可以登记，转述保留来源，尚未走过的路继续画虚线。</p></article><article><h3>尚待采用的提案</h3><p>共同历法、更多自治制度、能力的具体代价，仍是演进提案，尚未成为正式设定。</p></article><article><h3>仍然未知的答案</h3><p>三位住民的身世与觉醒状态、第二章寄信人的身份，以及纸条所述设备的实际情况，继续留白。</p></article></div><div class="book-chapters"><h3>目前展开的来信</h3><ol>{chapters}</ol><p>章节由你主动阅读，旧章可以回看。故事不增加观影等待，也不设阅读解锁播放的门槛。</p></div></section>
<section id="glossary" class="book-section wrap"><p class="world-name">废土词典</p><h2>在这里，<br>读懂一个名字。</h2><p class="book-note">来自放映站图谱的 {len(atlas['lexicon'])} 个词条节录。展开词条阅读；地点存在与路线已核，是两件分别记录的事。</p><div class="book-glossary">{glossary}</div></section><section class="book-ending wrap"><p>信可以慢慢读。<br>想看片子，随时可以先开场。</p><a class="pill" href="../#download">回到映汐，开始放映</a></section></main><footer class="site-footer wrap"><div class="footer-bottom"><a class="brand" href="../"><img src="../assets/icon.webp" width="26" height="26" alt=""><span>映汐放映站</span></a><nav aria-label="页脚导航"><a href="../privacy/">隐私说明</a><a href="../support/">使用支持</a><a href="../">播放器官网</a></nav><p>© 2026 Yingxi Station</p></div></footer></body></html>
'''
target = root / 'public/world/index.html'
if '--check' in sys.argv:
    if not target.exists() or target.read_text() != content:
        raise SystemExit('World archive differs from atlas; run python3 -B website/tools/sync-world.py')
    print('World archive matches atlas: six places, three residents, two chapters and all glossary terms.')
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
    print('Rendered website world archive from canonical atlas.')
