#!/usr/bin/env python3
"""Verify exact decoded artwork pixels recorded before the PNG to WebP migration."""
import hashlib,json,struct
from pathlib import Path
from PIL import Image
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/"assets/artwork.json").read_text())
assert manifest["format"]=="lossless-webp"
def digest(data):return hashlib.sha256(data).hexdigest()
total_before=total_after=0
for row in manifest["images"]:
    path=root/row["path"]
    assert path.suffix==".webp" and path.resolve().is_relative_to(root.resolve())
    data=path.read_bytes()
    assert digest(data)==row["encodedSha256"]
    assert data[:4]==b"RIFF" and data[8:12]==b"WEBP"
    chunks=[];offset=12
    while offset+8<=len(data):
        kind=data[offset:offset+4];size=struct.unpack_from("<I",data,offset+4)[0]
        chunks.append(kind);offset+=8+size+(size&1)
    assert b"VP8L" in chunks and b"VP8 " not in chunks,"Lossy WebP is forbidden: "+str(path)
    with Image.open(path) as im:
        assert im.size==(row["width"],row["height"])
        assert digest(im.convert("RGBA").tobytes())==row["rgbaSha256"]
        assert digest(im.info.get("icc_profile",b""))==row["iccSha256"]
    total_before+=row["beforeBytes"];total_after+=len(data)
print(json.dumps({"images":len(manifest["images"]),"rgbaPixels":"exact","beforeBytes":total_before,"afterBytes":total_after}))
