#!/usr/bin/env python3
"""Validate developer-owned public cloud registration metadata; never authorize an account."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlsplit


def validate(value):
    if not isinstance(value, dict) or set(value) != {'schemaVersion', 'providers'} or value['schemaVersion'] != 1:
        raise ValueError('Expected schemaVersion 1 and providers only')
    providers=value['providers']
    if not isinstance(providers,dict) or set(providers)!={'aliyunpan','baidu'}:
        raise ValueError('Expected aliyunpan and baidu provider configurations')
    result={}
    for name, data in providers.items():
        if not isinstance(data,dict) or set(data)!={'clientId','redirectUri'}:
            raise ValueError('Only public clientId and redirectUri are supported; do not store secrets')
        client=data['clientId'];redirect=data['redirectUri']
        if not isinstance(client,str) or not isinstance(redirect,str):raise ValueError('Public registration fields must be strings')
        if client and not re.fullmatch(r'[A-Za-z0-9._-]{1,160}',client):raise ValueError('Invalid public client ID')
        if redirect:
            url=urlsplit(redirect)
            if len(redirect)>2048 or url.scheme!='https' or not url.hostname or url.username or url.password or url.query or url.fragment:
                raise ValueError('Redirect record must be a plain registered HTTPS URL')
        result[name]={'registrationPresent':bool(client),'redirectRecorded':bool(redirect),'accountAuthorizationEnabled':False}
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',type=Path,required=True);p.add_argument('--require-registration',action='store_true');p.add_argument('--provider',choices=('aliyunpan','baidu'));a=p.parse_args()
    result=validate(json.loads(a.config.read_text(encoding='utf-8')))
    if a.provider:result={a.provider:result[a.provider]}
    print(json.dumps(result,ensure_ascii=False,indent=2))
    if a.require_registration and not all(v['registrationPresent'] for v in result.values()):raise SystemExit('Developer platform registration is incomplete')

if __name__=='__main__':main()
