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
    markers.append(f'<a href="#place-{key}" aria-label="阅读{esc(item["name"]["zh"])}"><rect class="map-hit" x="{x-110}" y="{y-50}" width="220" height="160"/><circle class="{"known-place" if item["known"] else "unknown-place"}" cx="{x}" cy="{y}" r="11"/><text x="{x}" y="{y+36}" text-anchor="middle">{esc(item["name"]["zh"])}</text></a>')
map_svg = '<svg viewBox="0 0 900 550" role="group" aria-label="放映站与盐环列站为实测往返信路，其余四处保留虚线线索">' + ''.join(paths + markers) + '</svg>'
place_html = ''.join(f'<article class="book-place" id="place-{key}"><p class="book-status">{"站内已知 · 信路已核" if item["known"] else "世界设定已采用 · 站内位置或路线待核"}</p><h3>{esc(item["name"]["zh"])}</h3><p>{esc(item["description"]["zh"])}</p></article>' for key, item in places.items())
resident_html = ''.join(f'<article class="book-resident" id="resident-{p["id"]}"><img src="../assets/{p["id"]}.webp" width="1254" height="1254" alt="{esc(p["name"]["zh"])}的原创肖像" loading="lazy"><div><p class="book-status">{esc(p["role"]["zh"])}</p><h3>{esc(p["name"]["zh"])}</h3><p>{esc(p["description"]["zh"])}</p></div></article>' for p in atlas['characters'])
glossary = ''.join(f'<details id="glossary-{esc(item["id"])}"><summary>{esc(item["name"]["zh"])}<span aria-hidden="true">＋</span></summary><p>{esc(summary(item, 2))}</p></details>' for item in atlas['lexicon'] if item['canonStatus'] == 'confirmed')
chapters = ''.join(f'<li>{esc(chapter["title"]["zh"])}</li>' for chapter in atlas['chapters'])
content = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#111113"><title>映汐世界树 · 映汐放映站</title><meta name="description" content="从核爆长潮到废土纪元，阅读映汐的六处地点、生命谱系、三位住民与废土词典。"><link rel="icon" href="../assets/icon.webp" type="image/webp"><link rel="stylesheet" href="../style.css"><link rel="stylesheet" href="../species.css"><script src="../site.js" defer></script><link rel="canonical" href="{origin}/world/"><meta property="og:type" content="website"><meta property="og:locale" content="zh_CN"><meta property="og:site_name" content="映汐放映站"><meta property="og:title" content="世界档案 · 映汐放映站"><meta property="og:description" content="核爆长潮之后的纪元、聚居、生命谱系与放映站住民。"><meta property="og:url" content="{origin}/world/"></head>
<body class="world-book"><a class="skip-link" href="#main">跳到正文</a><header class="local-nav"><div class="nav-inner"><a class="brand" href="../"><img src="../assets/icon.webp" width="30" height="30" alt=""><span>映汐放映站</span></a><nav aria-label="主导航"><a href="../#highlights">播放器</a><a href="#atlas">世界图谱</a><a href="../support/">支持</a></nav><a class="pill small" href="../#download">下载映汐</a></div></header>
<main id="main"><section class="book-hero wrap"><div class="book-hero-copy"><p class="world-name">映汐的废土世界</p><h1>映汐世界树</h1><p class="book-lead">核爆毁灭了文明纪元（繁荣纪元），长潮淹没旧城。<br>在旧铁路终点，有人在侵袭与故障中保住影像，试造可用的东西，等一封回信。</p><nav class="book-contents" aria-label="世界档案目录"><a href="#eras">纪元与灾变</a><a href="#atlas">地点与信路</a><a href="#society">聚居与势力</a><a href="#technology">探索与建造</a><a href="#life">生命与共生</a><a href="species/">映汐物种胶卷</a><a href="#residents">放映站住民</a><a href="#boundaries">远方的消息</a><a href="#glossary">废土词典</a></nav></div><figure class="book-hero-portrait"><img src="../assets/axi.webp" width="1254" height="1254" alt="阿汐抱着旧片罐，守着放映站收存的影像" fetchpriority="high"><figcaption>阿汐 · 影像拾荒者</figcaption></figure></section>
<section id="eras" class="book-section wrap"><p class="world-name">纪元与灾变</p><h2>一场核火。<br>此后，漫长的潮水。</h2><div class="book-era"><article><h3>文明纪元／繁荣纪元</h3><p>两个名称指同一核爆前时代。服务器、磁盘、胶片和铁路曾属于完整的文明；尖端技术的遗产仍在深层避难所与理想城延续。</p></article><article><h3>核爆与长潮</h3><p>核爆毁灭旧文明，继而引发地质与气候剧变。漫长海侵淹没旧城，重塑海岸；这条灾变链被合称为「核爆长潮」。</p></article><article><h3>废土纪元</h3><p>幸存者在停运列站和盐原高地重新生活。技术体系已经断层，各方仍主动勘探、试造与建造；失败会消耗材料、人力和时间。</p></article></div><p class="book-note">精确年代、长潮持续年数与各地退潮时序仍留白。一次普通涨潮，不代表新的核爆。</p></section>
<section id="atlas" class="book-section wrap"><p class="world-name">地点与信路</p><h2>世界很大。<br>走过的路，慢慢记。</h2><p class="book-lead">深层避难所少与外界往来，映汐则是外围改建的地下据点。<br>行商交换零件与旧影像，高塔凭技术俯视外界；废土客、掠夺者与太阳军团也各有其位置。</p><figure class="book-map">{map_svg}<figcaption><span class="map-key solid-key">实线：放映站—盐环列站往返信路</span><span class="map-key dotted-key">虚线：未核实的联系</span><span>点击地名阅读档案。此图为联系示意，不表示比例或可通行方向。</span></figcaption></figure><div class="book-places">{place_html}</div><p class="book-note">六处地点均属于正式世界设定。放映站掌握两处已知地点；另外四处的位置、到达路线或信号细节仍待核实。理想城的贸易往来，不代表弥途已经亲自走通全程。</p></section>
<section id="society" class="book-section wrap world-tree-addition"><h2>墙内，塔上，<br>与行路的人。</h2><p class="book-lead">有人把门修得更厚，有人沿旧路走得更远。水、技术与武力，决定了聚居之间能交换什么，也决定了谁在承受代价。</p><div class="book-era">{''.join(term_row(k, 'book-community') for k in ['shelters','survivor_outposts','settlements','ideal_city'])}</div><div class="book-life-list">{''.join(term_row(k) for k in ['itinerant_traders','wasteland_roamers','raiders','sun_legion'])}</div><p class="book-note">映汐是由旧避难所外围改建的地下幸存者据点。更深处的避难所自有边界；一扇旧门，并不等于仍能取得门后完整的技术。</p></section>
<section id="technology" class="book-section wrap world-tree-addition"><h2>从残件开始，<br>重新造出明天。</h2><div class="book-life-list">{''.join(term_row(k) for k in ['technology_fracture','independent_exploration','wasteland_threats'])}</div><div class="book-era"><article><h3>取水与通风</h3><p>据点要找到水从哪里来、风往哪里走。旧管道可能被巢穴堵住，外露的进风口也会成为侵入通道。每一处改善，都需要继续观察和维护。</p></article><article><h3>电力与材料</h3><p>旧设备留下可以拆下的零件，也留下接点腐蚀、尺寸不合与无法复现的工艺。能点亮一次的装置，距离撑过一个潮湿夜晚，还有很长的路。</p></article><article><h3>贸易与防守</h3><p>行商带来外界的物资和消息，货具也可能夹带卵、幼体与寄生物。城墙挡住一条路，守夜、清点与邻近聚居之间的关系仍要有人维持。</p></article><article><h3>向外调查</h3><p>废土客沿退潮后的旧街勘察，寻找可回收的结构与材料。掠夺者、变异生物、坏天气和失稳的楼体，会在同一趟路上改变返程的选择。</p></article></div></section>
<section id="life" class="book-section wrap"><p class="world-name">生命与共生</p><h2>废墟里，<br>生命有了新的形状。</h2><div class="book-life-list">{''.join(term_row(k) for k in ['mutated_species','spores','mutants','awakened'])}</div><div class="book-ecology">{''.join(term_row(k, 'book-creature') for k in ['echo_seabirds','spore_moths','salt_pack_beasts'])}</div><p class="book-note">菌膜保护的是已有密封胶片盒。干爽的盒子，不代表整片废墟安全；感知频段，也不会让缺失的数据自动回来。能力的上限、代价与恢复条件仍待个体故事展开。废土也有会侵袭人员、物资和设施的变异物种，保护仍需观察、维护和承担成本。</p></section>
<section id="species-film" class="book-section wrap"><h2>映汐物种胶卷</h2><div class="species-preview"><a href="species/"><img src="../assets/species/tide_barge.webp" width="1254" height="1254" loading="lazy" alt="背负芦草的潮舟兽走过淹没旧街"></a><div><h3>二十种生命，各有去处。</h3><p>生态创作图鉴，尚未正式采用。从鞍布缝里的微小寄生物，到背负一片栖息地的潮舟兽，翻阅它们的取食、隐蔽和迁徙记录。</p><a class="species-action" href="species/">翻阅映汐物种胶卷</a></div></div></section>
<section id="residents" class="book-section wrap"><p class="world-name">放映站住民</p><h2>有人带回影像。<br>有人守住灯光。</h2><div class="book-residents">{resident_html}</div><div class="book-relations"><h3>一份登记，一次检修，一封来信。</h3><p>阿汐整理片罐与登记纸，迟灯修理机器、校准广播，弥途往返盐环列站。变种人搬运工与向导、路过的觉醒者，也与他们在打捞、送信和守夜中相遇。</p><p>他们的职业与日常已经清楚；各自的族群、身世和觉醒状态，仍没有被写定。</p></div></section>
<section id="boundaries" class="book-section wrap"><h2>远方的消息，<br>沿着信路抵达。</h2><div class="book-boundaries"><article><h3>走过的路</h3><p>放映站与盐环列站之间，留下了往返的足迹。商队口信、晴夜微光和广播频段带来别处的线索，尚未走过的路仍画作虚线。</p></article><article><h3>高塔之外</h3><p>理想城的贸易向外延伸，太阳军团也在扩大自己的影响。它们的驻地、装备和彼此间的战事，并不会随着一句传闻就变得清楚。</p></article><article><h3>灯下的住民</h3><p>阿汐整理影像，迟灯维护设备，弥途走过盐环的信路。至于他们从哪里来、经历过什么，有些话仍没有说出口。</p></article><article><h3>未署名的回信</h3><p>有人在别处看过一场放映，也有人把纸条放进了片罐。寄信人的身份与去向，仍留在回信尚未抵达的地方。</p></article></div><div class="book-chapters"><h3>目前展开的来信</h3><ol>{chapters}</ol><p>章节由你主动阅读，旧章可以回看。故事不增加观影等待，也不设阅读解锁播放的门槛。</p></div></section>
<section id="glossary" class="book-section wrap"><p class="world-name">废土词典</p><h2>在这里，<br>读懂一个名字。</h2><p class="book-note">来自放映站图谱的 {sum(item['canonStatus'] == 'confirmed' for item in atlas['lexicon'])} 个风物词条。展开阅读；二十种生命的完整画幅与习性，收录在映汐物种胶卷。</p><div class="book-glossary">{glossary}</div></section><section class="book-ending wrap"><p>信可以慢慢读。<br>想看片子，随时可以先开场。</p><a class="pill" href="../#download">回到映汐，开始放映</a></section></main><footer class="site-footer wrap"><div class="footer-bottom"><a class="brand" href="../"><img src="../assets/icon.webp" width="26" height="26" alt=""><span>映汐放映站</span></a><nav aria-label="页脚导航"><a href="../privacy/">隐私说明</a><a href="../support/">使用支持</a><a href="../">播放器官网</a></nav><p>© 2026 Yingxi Station</p></div></footer></body></html>
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
