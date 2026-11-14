"""Server-local deployment verification. No credentials, raw documents or security tokens are printed."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

ip = subprocess.check_output(['sudo','-n','docker','inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}','starline-mcp'],text=True).strip()
base = 'http://' + ip + ':9099'
session = None
artist = os.environ.get('CRAWL_SMOKE_ARTIST','薛之谦')
require_direct = os.environ.get('CRAWL_REQUIRE_DIRECT')=='1'

def get_status():
    with urllib.request.urlopen(base+'/internal/event-crawl/status',timeout=5) as r:
        return json.load(r)

def rpc(method, params, identifier):
    global session
    body={'jsonrpc':'2.0','method':method,'params':params}
    if identifier is not None:
        body['id']=identifier
    headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2025-03-26'}
    if session:
        headers['Mcp-Session-Id']=session
    req=urllib.request.Request(base+'/mcp',data=json.dumps(body,ensure_ascii=False).encode(),headers=headers)
    with urllib.request.urlopen(req,timeout=30) as r:
        session=r.headers.get('Mcp-Session-Id',session)
        text=r.read().decode()
    if not text:
        return None
    if text.lstrip().startswith('{'):
        result=json.loads(text)
    else:
        result=next(json.loads(line[5:].strip()) for line in text.splitlines() if line.startswith('data:') and '"result"' in line)
    assert 'error' not in result, 'MCP_RPC_ERROR'
    return result.get('result')

status=None
for _ in range(45):
    try:
        status=get_status()
        if status.get('enabled') and status.get('storageStatus')=='OK':
            break
    except Exception:
        pass
    time.sleep(2)
assert status and status['enabled'] and status['storageStatus']=='OK', 'CRAWL_NOT_READY'
rpc('initialize',{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'event-crawl-deploy-check','version':'1'}},1)
rpc('notifications/initialized',{},None)
tools=rpc('tools/list',{},2)['tools']
assert 'search_artist_events' in [t['name'] for t in tools]
result=rpc('tools/call',{'name':'search_artist_events','arguments':{'artist':artist,'pageSize':50 if require_direct else 2}},3)
assert not result.get('isError'), 'SEARCH_TOOL_FAILED'
data=json.loads(next(c['text'] for c in result['content'] if c['type']=='text'))
assert len(data['coverage']['crawlSources'])==3, 'CRAWL_COVERAGE_MISSING'
assert any('后台直接采集' in note for note in data['notes'])
if require_direct:
    assert data['coverage']['crawledCandidates']>0,'DIRECT_CRAWL_RESULT_MISSING'
    assert any(any(q.startswith('direct-crawl:') for q in item.get('queries',[])) for item in data['items']),'DIRECT_CRAWL_ITEM_MISSING'
for item in data['items']:
    if any(q.startswith('direct-crawl:') for q in item.get('queries',[])):
        assert not item['canBuyTicket']
        if item['dateUnknown']:
            assert item['eventDate'] is None
print('mcp_tools='+json.dumps([t['name'] for t in tools]))
print('search_smoke='+json.dumps({'artist':artist,'returned':len(data['items']),'crawledCandidates':data['coverage']['crawledCandidates'],'crawlSources':data['coverage']['crawlSources'],'completeness':data['coverage']['completeness']},ensure_ascii=False))
for _ in range(30):
    status=get_status()
    ids={s['source'] for s in status['sources']}
    if {'beijing','guangzhou','damai'}<=ids:
        break
    time.sleep(2)
assert {'beijing','guangzhou','damai'}<={s['source'] for s in status['sources']}, 'FIRST_CRAWL_NOT_FINISHED'
assert any(s['source'] in ('beijing','guangzhou') and s['status'] in ('OK','PARTIAL') for s in status['sources']), 'NO_GOV_SOURCE_COMPLETED'
assert status['storageStatus']=='OK'
index=Path('/home/ubuntu/starline-infra/releases/starline-20261022/event-crawl-data/index.json')
saved=json.loads(subprocess.check_output(['sudo','-n','cat',str(index)],text=True))
assert saved['version']==1 and saved['artists'], 'DURABLE_INDEX_MISSING'
print('crawl_status='+json.dumps(status,ensure_ascii=False))
print('persistent_index_verified=true')
