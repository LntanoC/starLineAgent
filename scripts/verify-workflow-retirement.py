#!/usr/bin/env python3
"""Agent-only deployment smoke check; localhost credentials stay in memory."""
import contextlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE=Path(__file__).resolve().parent
def sql(statement):
    return subprocess.check_output(["sudo","-n","docker","exec","starline-postgres","psql","-U","starline","-d","starline","-Atc",statement],text=True).strip()
def main():
    phase=sys.argv[1] if len(sys.argv)>1 else "pre"
    spec=importlib.util.spec_from_file_location("audit_check",HERE/"verify-agent-audit-server.py")
    audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    run_file=HERE/"smoke-run-id.txt"
    sys.argv=[str(HERE/"verify-agent-audit-server.py")]+([run_file.read_text().strip()] if phase=="post" else [])
    output=io.StringIO()
    with contextlib.redirect_stdout(output):audit.main()
    result=json.loads(output.getvalue().splitlines()[-1]);run_id=result["run_id"]
    run_file.write_text(run_id)
    detail=audit.request("/agent/runs/"+run_id)
    message_id=detail["run"]["assistantMessageId"];user_id=detail["run"]["userId"]
    settings=audit.request("/rag/settings")
    if settings["engine"]["type"]!="agent":raise RuntimeError("engine_not_agent")
    rag=settings["rag"]
    if any(k in rag for k in ("memory","rateLimit")) or "trace" in rag["features"]:
        raise RuntimeError("obsolete_settings_still_exposed")
    for path in ["/admin/dashboard/overview?window=7d","/admin/dashboard/performance?window=7d"]:
        if audit.request(path)["engine"]!="agent":raise RuntimeError("dashboard_not_agent")
    old=["/rag/v3/chat?question=retirement-probe","/conversations","/rag/traces/runs",
         "/conversations/messages/0/feedback","/conversations/messages/0/recommended-questions"]
    for path in old:
        method="POST" if path.endswith(("/feedback","/recommended-questions")) else "GET"
        req=urllib.request.Request(audit.BASE+path,headers={"Authorization":audit.token,"Content-Type":"application/json"},
                                   data=b"{}" if method=="POST" else None,method=method)
        try:
            with urllib.request.urlopen(req,timeout=10) as response:body=json.load(response)
            if str(body.get("code"))=="0":raise RuntimeError("retired_route_still_available")
        except urllib.error.HTTPError as error:
            if error.code not in (404,405):raise RuntimeError("unexpected_retired_route_status")
    if not re.fullmatch(r"\d{1,20}",message_id) or not re.fullmatch(r"\d{1,20}",user_id):
        raise RuntimeError("invalid_smoke_message_identity")
    if phase=="pre":
        try:
            audit.request("/agent/v1/messages/"+message_id+"/feedback",
                          {"vote":-1,"reason":"workflow-retirement-smoke","comment":"部署联调验证，非真实用户差评"})
            scan=audit.request("/rag/bad-cases/scan?includeOlder=false",{})
            if scan.get("busy"):raise RuntimeError("bad_case_scan_busy")
            count=sql("SELECT count(*) FROM t_bad_case WHERE message_id='"+message_id+"' AND user_id='"+user_id+"' AND deleted=0;")
            if count!="1":raise RuntimeError("agent_bad_case_was_not_created")
            linked=sql("SELECT trace_match_status FROM t_bad_case WHERE message_id='"+message_id+"' AND user_id='"+user_id+"';")
            if linked!="AGENT_AUDIT":raise RuntimeError("bad_case_audit_link_missing")
        finally:
            # Soft-delete only the explicitly marked smoke feedback and its generated ledger item.
            sql("BEGIN; UPDATE t_bad_case SET deleted=1,update_time=CURRENT_TIMESTAMP WHERE message_id='"+message_id+"' AND user_id='"+user_id+"'; UPDATE t_message_feedback SET deleted=1,update_time=CURRENT_TIMESTAMP WHERE message_id='"+message_id+"' AND user_id='"+user_id+"' AND reason='workflow-retirement-smoke'; COMMIT;")
    else:
        scan=audit.request("/rag/bad-cases/scan?includeOlder=false",{})
        if scan.get("failed",0)>0:raise RuntimeError("bad_case_scan_failed_after_drop")
        remaining=sql("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('t_conversation','t_message','t_conversation_summary','t_rag_trace_run','t_rag_trace_node');")
        if remaining!="0":raise RuntimeError("retired_tables_remain")
    audit.request("/rag/bad-cases/stats")
    print(json.dumps({"ok":True,"phase":phase,"run_id":run_id,"agent_tool_audit":True,"settings_agent_only":True,
                      "dashboard_agent_only":True,"retired_routes_unavailable":True,"bad_case_ok":True,
                      "smoke_feedback_soft_deleted":True},ensure_ascii=False))
if __name__=="__main__":
    try:main()
    except Exception as error:
        print(json.dumps({"ok":False,"error_type":type(error).__name__,"check":str(error) if isinstance(error,RuntimeError) else "verification_failed"}))
        sys.exit(1)
