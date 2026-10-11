#!/usr/bin/env python3
"""Read-only foreground/background liveness sampling. Never records credentials."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--output',required=True)
p.add_argument('--seconds',type=int,default=90)
p.add_argument('--interval',type=int,default=10)
o=p.parse_args()
def adb(*args): return subprocess.check_output(['adb','-s',o.serial,*args])
adb('forward','tcp:14177','tcp:4177')
token=ET.fromstring(adb('shell','run-as','dev.deploy','cat','shared_prefs/deploy.xml')).find("string[@name='token']").text
body=json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/call',
    'params':{'name':'deploy_status','arguments':{}}}).encode()
samples=[]
started=time.monotonic()
while True:
    now=time.monotonic()
    row={'elapsed':round(now-started,1)}
    try:
        req=urllib.request.Request('http://127.0.0.1:14177/mcp',data=body,
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=4) as r: value=json.load(r)
        result=value.get('result',{})
        status=json.loads(result.get('content',[{}])[0].get('text','{}'))
        row.update(ok=not result.get('isError',False) and status.get('apiVersion')==2,
            latencyMs=round((time.monotonic()-now)*1000))
    except Exception as e: row.update(ok=False,error=type(e).__name__)
    power=adb('shell','dumpsys','power').decode()
    wake=re.search(r'mWakefulness=(\w+)',power)
    row['wakefulness']=wake.group(1) if wake else 'unknown'
    pid=subprocess.run(['adb','-s',o.serial,'shell','pidof','dev.deploy'],capture_output=True)
    row['pid']=pid.stdout.decode().strip()
    samples.append(row)
    Path(o.output).write_text(json.dumps(samples,indent=2),encoding='utf-8')
    print(row,flush=True)
    if time.monotonic()-started>=o.seconds:break
    time.sleep(min(o.interval,max(0,o.seconds-(time.monotonic()-started))))
raise SystemExit(0 if all(r['ok'] for r in samples) else 1)
