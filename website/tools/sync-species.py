"""Render the illustrated species collection from its published content."""
from pathlib import Path
import html
import json
import re
import sys

root = Path(__file__).resolve().parents[1]
esc = html.escape


def render(collection, origin):
    species = collection['species']
    ids = [item['id'] for item in species]
    if len(ids) != len(set(ids)) or any(not re.fullmatch(r'[a-z][a-z0-9_]*', key) for key in ids):
        raise ValueError('Species identifiers must be unique stable identifiers')
    links = ''.join(f'<a href="#{item["id"]}">{esc(item["name"])}</a>' for item in species)
    frames = []
    for item in species:
        key = item['id']
        frames.append(f'''<article class="specimen" id="{key}">
<a class="specimen-art" href="../../assets/species/{key}.webp" target="_blank" rel="noopener" aria-label="查看{esc(item['name'])}完整立绘（新标签页）"><img src="../../assets/species/{key}.webp" width="1254" height="1254" loading="lazy" decoding="async" alt="{esc(item['name'])}：{esc(item['shape'])}"></a>
<div class="specimen-copy"><h2>{esc(item['name'])}</h2><p class="species-english" lang="en">{esc(item['english'])}</p><p class="species-habitat">{esc(item['habitat'])}</p><p>{esc(item['shape'])}</p><p>{esc(item['feeding'])}</p><p>{esc(item['impact'])}</p><details><summary>习性与边界</summary><p>{esc(item['limits'])}</p></details><a class="species-back" href="#species-index">回到物种目录</a></div></article>''')
    return f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#111113"><title>映汐物种胶卷 · 映汐放映站</title><meta name="description" content="二十种废土生命的独立立绘与生态正文。沿着盐原、旧渠和潮水，翻阅映汐物种胶卷。"><link rel="canonical" href="{esc(origin)}/world/species/"><meta property="og:title" content="映汐物种胶卷"><meta property="og:description" content="核爆长潮之后，生命在废墟里长出新的形状。"><meta property="og:type" content="website"><meta property="og:image" content="{esc(origin)}/assets/species/tide_barge.webp"><link rel="icon" href="../../assets/icon.webp" type="image/webp"><link rel="stylesheet" href="../../style.css"><link rel="stylesheet" href="../../species.css"><script src="../../site.js" defer></script></head>
<body class="world-book species-film"><a class="skip-link" href="#main">跳到正文</a><header class="local-nav"><div class="nav-inner"><a class="brand" href="../../"><img src="../../assets/icon.webp" width="30" height="30" alt=""><span>映汐放映站</span></a><nav aria-label="主导航"><a href="../">世界树</a><a href="#species-index">物种目录</a><a href="../../#download">播放器</a></nav></div></header>
<main id="main"><section class="species-hero wrap"><div><h1>映汐物种胶卷</h1><p class="species-lead">{esc(collection['description'])}</p><p>会移动的浅滩，管道深处的须影，旧铁门上反复响起的敲击。沿着它们留下的痕迹，重新认识废土。</p><a class="species-action" href="#species-index">翻阅二十种生命</a></div><figure><img src="../../assets/species/tide_barge.webp" width="1254" height="1254" fetchpriority="high" alt="潮舟兽背负泥沙与芦草，缓慢穿过淹没的旧街"><figcaption>潮舟兽经过时，岸边也会移动。</figcaption></figure></section>
<section class="species-directory wrap" id="species-index"><h2>从一种生命开始</h2><p>选择名字进入对应画幅，点击立绘可查看完整图像。</p><nav aria-label="二十种废土物种">{links}</nav></section>
<div class="species-frames wrap">{''.join(frames)}</div>
<section class="species-web wrap" id="ecology"><h2>它们生活在同一片废土</h2><div><p>盐唇花与返潮芦支撑着虫群和食草兽。硬背鼠寻找粮食与暖处，铜耳狐追着鼠迹走近据点。赶走了狐，仓库里的啃咬声可能更多。</p><p>盐镜蛛捕食纸翅螟，也会在精细机构上结网。清掉全部蛛网，纸签和装订胶便少了一层保护。一次清理，可能换来另一种损耗。</p><p>潮水推动走壳蟹，商队带着鞍缝虱迁移。潮舟兽背上的附生生命，则随一整片会移动的栖息地启程。贸易、筑墙、照明与改渠，都在改变这张相互牵连的网。</p></div><a class="species-action" href="../">继续阅读世界树</a></section></main>
<footer class="site-footer wrap"><div class="footer-bottom"><a class="brand" href="../../"><img src="../../assets/icon.webp" width="26" height="26" alt=""><span>映汐放映站</span></a><nav aria-label="页脚导航"><a href="../">世界树</a><a href="../../support/">支持</a><a href="../../privacy/">隐私说明</a></nav><p>© 2026 Yingxi Station</p></div></footer></body></html>
'''


def main():
    collection = json.loads((root / 'public/assets/species.json').read_text())
    atlas = json.loads((root.parent / 'app/src/main/res/raw/world_atlas.json').read_text())
    terms = {item['id']: item for item in atlas['lexicon']}
    for item in collection['species']:
        if item['id'] not in terms or item['name'] != terms[item['id']]['name']['zh']:
            raise ValueError('Species differs from the world tree: ' + item['id'])
        if not (root / 'public/assets/species' / (item['id'] + '.webp')).is_file():
            raise ValueError('Missing illustration: ' + item['id'])
    canonical = re.search(r'<link rel="canonical" href="(https://[^/]+)', (root / 'public/index.html').read_text())
    if not canonical:
        raise ValueError('Missing site origin')
    content = render(collection, canonical[1])
    target = root / 'public/world/species/index.html'
    if '--check' in sys.argv:
        if not target.exists() or target.read_text() != content:
            raise SystemExit('Species page is stale; run website/tools/sync-species.py')
        print('Species film matches all 20 content entries and illustrations')
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)


if __name__ == '__main__':
    main()
