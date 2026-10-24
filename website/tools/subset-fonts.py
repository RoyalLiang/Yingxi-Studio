"""Subset locally supplied OFL Noto fonts. This tool makes no network requests."""
from pathlib import Path
import argparse,json,re
from fontTools import subset
from fontTools.ttLib import TTFont
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sans-source',required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
public=root/'public'
text=''.join(p.read_text() for p in public.rglob('*.html'))
text+=json.dumps(json.loads((public/'assets/letters.json').read_text()),ensure_ascii=False)
text+='的短笺'
text=re.sub('<[^>]*>','',text)
font=TTFont(args.sans_source)
options=subset.Options();options.flavor='woff2'
processor=subset.Subsetter(options=options);processor.populate(text=text);processor.subset(font)
font.flavor='woff2';output=public/'assets/fonts/sans.woff2';font.save(output)
print(output.name,output.stat().st_size,'bytes; variable weight axis retained')
