#!/usr/bin/env python3
"""Generate a portable README activity snapshot from an exact source commit."""
import argparse
import datetime as dt
import html
import json
import subprocess
from pathlib import Path
from zoneinfo import ZoneInfo

def git(root,*args):
    return subprocess.check_output(["git","-C",str(root),*args],text=True).strip()

def generate(root,ref):
    commit=git(root,"rev-parse","--verify","--end-of-options",ref+"^{commit}")
    zone=ZoneInfo("Asia/Shanghai")
    end=dt.datetime.fromisoformat(git(root,"show","-s","--format=%cI",commit)).astimezone(zone).date()
    dates=[end-dt.timedelta(days=i) for i in reversed(range(7))]
    counts=dict.fromkeys(dates,0)
    for line in git(root,"log","--format=%cI",commit).splitlines():
        day=dt.datetime.fromisoformat(line).astimezone(zone).date()
        if day in counts:counts[day]+=1
    data={"sourceCommit":commit,"timezone":"Asia/Shanghai","throughDate":end.isoformat(),
          "dailyCommits":[{"date":d.isoformat(),"count":counts[d]} for d in dates],
          "totalCommits":int(git(root,"rev-list","--count",commit))}
    peak=max(1,*counts.values())
    parts=['<svg xmlns="http://www.w3.org/2000/svg" width="960" height="330" viewBox="0 0 960 330" role="img" aria-labelledby="title desc">',
           '<title id="title">映汐源码提交活跃度</title>',
           '<desc id="desc">'+html.escape("最近七天提交数："+json.dumps(data["dailyCommits"],ensure_ascii=False)+"；统计截至"+commit)+'</desc>',
           '<rect width="960" height="330" rx="24" fill="#F5F5F8"/>',
           '<g font-family="system-ui, sans-serif" fill="#22232A">',
           '<text x="32" y="42" font-size="22" font-weight="650">源码提交活跃度 · 最近 7 天</text>',
           '<text x="32" y="68" font-size="13" fill="#686974">截至 '+data["throughDate"]+' · '+commit[:7]+' · 共 '+str(data["totalCommits"])+' 次提交</text>',
           '<line x1="44" y1="262" x2="916" y2="262" stroke="#DCDCE4"/>']
    for i,d in enumerate(dates):
        x=66+i*124;height=counts[d]/peak*145;y=262-height
        parts.extend(['<rect x="'+str(x)+'" y="'+str(round(y,2))+'" width="72" height="'+str(round(height,2))+'" rx="7" fill="#B91E4B"/>',
                      '<text x="'+str(x+36)+'" y="'+str(round(y-9,2))+'" font-size="15" text-anchor="middle">'+str(counts[d])+'</text>',
                      '<text x="'+str(x+36)+'" y="291" font-size="13" text-anchor="middle" fill="#686974">'+d.strftime("%m-%d")+'</text>'])
    parts.append('</g></svg>')
    target=root/"assets/readme";target.mkdir(parents=True,exist_ok=True)
    (target/"activity.svg").write_text("\n".join(parts)+"\n")
    (root/"docs").mkdir(exist_ok=True)
    (root/"docs/project-metrics.json").write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n")
    return data

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument("--ref",default="HEAD")
    args=parser.parse_args()
    print(json.dumps(generate(args.root.resolve(),args.ref),ensure_ascii=False))

if __name__=="__main__":main()
