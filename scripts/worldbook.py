#!/usr/bin/env python3
"""Validate the versioned original story atlas shared by the app and release writers."""
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
    for edge in world['edges']:
        if edge['from'] not in places or edge['to'] not in places:raise ValueError('Unknown world edge endpoint')
        text(edge['label'])
    for chapter in world['chronicles']:text(chapter['text'])
    return world

if __name__=='__main__':
    data=validate(Path(__file__).resolve().parents[1])
    print(f"World atlas valid: {len(data['characters'])} characters, {len(data['nodes'])} places")
