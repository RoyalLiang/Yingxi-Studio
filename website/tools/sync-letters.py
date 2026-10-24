"""Export existing atlas diary text and the accessible no-script reading fallback."""
from pathlib import Path
import json,re,html
root=Path(__file__).resolve().parents[1]
world=json.loads((root.parent/'app/src/main/res/raw/world_atlas.json').read_text())
letters={}
for person in world['characters']:
    diary=next(line for line in reversed(world['dailyLines']) if line['author']==person['id'])
    letters[person['id']]={'name':person['name']['zh'],'role':person['role']['zh'],'letter':diary['text']['zh'],'english':diary['text']['en'],'record':diary['id']}
(root/'public/assets/letters.json').write_text(json.dumps(letters,ensure_ascii=False,indent=2)+'\n')
articles=''.join('<article><h3>'+html.escape(d['name'])+'的短笺</h3><p>'+html.escape(d['letter'])+'</p></article>' for d in letters.values())
p=root/'public/index.html';s=p.read_text()
s,count=re.subn(r'(<div id="all-letter-texts">).*?(</div></details>)',lambda m:m[1]+articles+m[2],s,flags=re.S)
if count!=1:raise SystemExit('Expected exactly one no-script letter fallback')
p.write_text(s);print('Exported '+', '.join(d['record'] for d in letters.values()))
