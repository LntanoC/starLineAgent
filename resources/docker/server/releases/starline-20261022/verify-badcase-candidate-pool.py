#!/usr/bin/env python3
"""Server-local smoke check; synthetic records are removed, credentials never printed."""
import json,re,subprocess,time,urllib.request,urllib.error,urllib.parse,secrets
BASE='http://127.0.0.1/api/starlineagent'
def sql(query):
    return subprocess.check_output(['sudo','-n','docker','exec','starline-postgres','psql','-U','starline','-d','starline','-v','ON_ERROR_STOP=1','-Atc',query],text=True).strip()
def main():
    token=None
    def request(path,data=None,auth=True):
        headers={'Content-Type':'application/json'}
        if auth and token:headers['Authorization']=token
        body=None if data is None else json.dumps(data).encode()
        with urllib.request.urlopen(urllib.request.Request(BASE+path,data=body,headers=headers),timeout=30)as response:obj=json.load(response)
        if str(obj.get('code'))!='0':raise RuntimeError('business_response_rejected')
        return obj.get('data')
    anonymous_denied=False
    try:request('/rag/bad-cases?size=1',auth=False)
    except (RuntimeError,urllib.error.HTTPError):anonymous_denied=True
    if not anonymous_denied:raise RuntimeError('anonymous_access_allowed')
    account=json.loads(sql("SELECT json_build_object('username',username,'password',password,'userId',id) FROM t_user WHERE username='admin' AND role='admin' AND deleted=0 LIMIT 1"))
    user=account.pop('userId');token=request('/auth/login',account,False)['token'];account.clear()
    if not re.fullmatch(r'\d{1,20}',user):raise RuntimeError('invalid_admin_identity')
    salt=str(int(time.time()))+str(secrets.randbelow(1000000)).zfill(6)
    conv='91'+salt;um='92'+salt;am='93'+salt;run='candidate-smoke-'+salt
    candidate=None
    try:
        sql("BEGIN; INSERT INTO t_agent_conversation(id,conversation_id,user_id,title) VALUES('"+conv+"','"+conv+"','"+user+"','候选池部署合成校验'); INSERT INTO t_agent_message(id,conversation_id,user_id,role,content) VALUES('"+um+"','"+conv+"','"+user+"','user','候选池部署合成校验'); INSERT INTO t_agent_message(id,conversation_id,user_id,role,content,reply_to_message_id,blocks) VALUES('"+am+"','"+conv+"','"+user+"','assistant','这是合成校验数据，不是真实用户回答','"+um+"','[]'::jsonb); INSERT INTO t_agent_run(id,run_id,conversation_id,user_id,user_message_id,assistant_message_id,run_status,capture_status) VALUES('"+am+"','"+run+"','"+conv+"','"+user+"','"+um+"','"+am+"','NORMAL','PARTIAL'); COMMIT;")
        request('/agent/v1/messages/'+am+'/feedback',{'vote':-1,'reason':'candidate-pool-deployment-check','comment':'合成校验，结束后删除'})
        first=request('/rag/bad-cases/scan?detector=user-feedback&mode=apply',{})
        if first.get('failed',0):raise RuntimeError('feedback_scan_failed')
        candidate=sql("SELECT id FROM t_bad_case WHERE dedup_key='MSG:"+am+"'")
        if not re.fullmatch(r'\d{1,20}',candidate):raise RuntimeError('candidate_not_created')
        detail=request('/rag/bad-cases/'+candidate)
        if detail['candidate']['confidence']!='HIGH' or detail['candidate']['triage_status']!='READY_FOR_HUMAN':raise RuntimeError('triage_mismatch')
        signals=request('/rag/bad-cases/'+candidate+'/signals')
        request('/rag/bad-cases/scan?detector=user-feedback&mode=apply',{})
        if len(signals)!=1 or len(request('/rag/bad-cases/'+candidate+'/signals'))!=1:raise RuntimeError('signal_not_idempotent')
        request('/rag/bad-cases?reviewStatus=PENDING&triageStatus=READY_FOR_HUMAN')
        request('/rag/bad-cases?confidence=LOW')
        request('/rag/bad-cases?measurement=true')
        request('/rag/bad-cases/eval-samples')
        request('/rag/bad-cases/eval-samples/export?datasetName=deployment-check')
        request('/rag/bad-cases/eval-samples/manifest?datasetName=deployment-check')
        request('/rag/bad-cases/'+candidate+'/review-history')
        before=int(sql('SELECT count(*) FROM t_bad_case_signal'))
        shadow=request('/rag/bad-cases/scan?mode=dryRun&includeOlder=false',{})
        modes=shadow.get('detectors',{})
        if 'behavior' in modes or 'sampling' in modes:raise RuntimeError('disabled_detector_was_scanned')
        if any(v.get('mode') not in ('ON','SHADOW') for v in modes.values()):raise RuntimeError('mode_was_not_normalized')
        after=int(sql('SELECT count(*) FROM t_bad_case_signal'))
        if before!=after:raise RuntimeError('dry_run_wrote_signals')
        stats=request('/rag/bad-cases/stats')
        for key in ['highPending','lowPending','todaySignals','bySource','byTriage','metrics']:
            if key not in stats:raise RuntimeError('statistics_missing')
        try:
            request('/rag/bad-cases/eval-samples/import-result',{})
            raise RuntimeError('public_import_not_blocked')
        except urllib.error.HTTPError as e:
            if e.code!=403:raise
        with urllib.request.urlopen('http://127.0.0.1/admin/bad-cases')as response:html=response.read().decode()
        assets=re.findall(r'src="(/assets/[^\"]+\.js)"',html)
        deployed=False
        for asset in assets:
            with urllib.request.urlopen('http://127.0.0.1'+asset)as response:code=response.read().decode()
            if '统一候选池' in code and '低置信候选' in code and '测量抽查' in code:deployed=True
        if not deployed:raise RuntimeError('frontend_not_deployed')
        print(json.dumps({'ok':True,'anonymousDenied':True,'feedbackAndSignalIdempotent':True,'filtersAndSamples':True,'dryRunReadOnly':True,'publicImportBlocked':True,'frontendUpdated':True,'detectors':shadow.get('detectors')},ensure_ascii=False))
    finally:
        # Delete only this script's exact synthetic identities; no user conversation or feedback is modified.
        sql("BEGIN; DELETE FROM t_bad_case_review_log WHERE bad_case_id IN (SELECT id FROM t_bad_case WHERE message_id='"+am+"'); DELETE FROM t_bad_case_signal WHERE bad_case_id IN (SELECT id FROM t_bad_case WHERE message_id='"+am+"'); DELETE FROM t_bad_case WHERE message_id='"+am+"' AND user_id='"+user+"'; DELETE FROM t_message_feedback WHERE message_id='"+am+"' AND reason='candidate-pool-deployment-check'; DELETE FROM t_agent_run WHERE run_id='"+run+"'; DELETE FROM t_agent_message WHERE conversation_id='"+conv+"' AND user_id='"+user+"'; DELETE FROM t_agent_conversation WHERE conversation_id='"+conv+"' AND user_id='"+user+"'; COMMIT;")
if __name__=='__main__':
    try:main()
    except Exception as e:print(json.dumps({'ok':False,'errorType':type(e).__name__,'check':str(e)if isinstance(e,RuntimeError)else 'verification_failed'}));raise SystemExit(1)
