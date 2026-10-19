#!/usr/bin/env python3
"""Loopback-only SMB2 fixture. Install impacket in an isolated PYTHONPATH first."""
import argparse,logging,os
from pathlib import Path
from impacket import smbserver,ntlm

p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--port',type=int,default=1445);args=p.parse_args()
root=Path(args.root).resolve();root.mkdir(parents=True,exist_ok=True)
logging.basicConfig(level=logging.WARNING)
server=smbserver.SimpleSMBServer(listenAddress='127.0.0.1',listenPort=args.port)
server.addShare('media',str(root),'Local test fixtures only',readOnly='yes')
server.setSMB2Support(True)
secret=os.environ['PLAYER_TEST_PASSWORD']
server.addCredential(os.environ.get('PLAYER_TEST_USERNAME','test-user'),0,ntlm.compute_lmhash(secret),ntlm.compute_nthash(secret))
print('SMB fixture ready on loopback port '+str(args.port),flush=True)
server.start()
