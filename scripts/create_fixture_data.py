#!/usr/bin/env python3
"""Create synthetic sparse files outside the repository; contains no real media."""
import argparse
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--root",type=Path,required=True);args=parser.parse_args()
    root=args.root.resolve();project=Path(__file__).resolve().parents[1]
    if root==project or project in root.parents:raise SystemExit("Fixture data must remain outside the repository")
    if root.exists() and any(root.iterdir()):raise SystemExit("Fixture root must be empty; existing files are never overwritten")
    (root/"影片/短片").mkdir(parents=True,exist_ok=True)
    size=(1<<32)+8192;marker=b"large-offset-marker"
    # Deterministic nonzero bytes exercise random seeks without a media encoder.
    length=4*1024*1024+8192
    (root/"read-window.bin").write_bytes((bytes(range(251))*((length+250)//251))[:length])
    with (root/"影片/large.mp4").open("xb") as file:
        file.truncate(size);file.seek(size-len(marker));file.write(marker)
    print("Synthetic large-offset fixture ready")

if __name__=="__main__":main()
