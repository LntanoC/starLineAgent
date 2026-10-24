#!/usr/bin/env python3
"""Server-local integration check. Existing admin credentials stay in memory and are never printed."""
import re
import json
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import urllib.error

BASE = "http://127.0.0.1/api/starlineagent"
token = None
def request(path, data=None, authenticated=True):
    headers = {"Content-Type": "application/json"}
    if authenticated and token:
        headers["Authorization"] = token
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        result = json.load(response)
    if str(result.get("code")) != "0":
        raise RuntimeError("business_request_rejected")
    return result.get("data")

def main():
    global token
    origin = "http://127.0.0.1"
    with urllib.request.urlopen(origin + "/admin/agent-runs", timeout=10) as response:
        html = response.read().decode("utf-8")
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+\.(?:js|css))"', html)
    if not assets: raise RuntimeError("frontend_assets_not_found")
    code = ""
    for asset in assets:
        with urllib.request.urlopen(origin + asset, timeout=10) as response:
            body = response.read().decode("utf-8")
        if asset.endswith(".js"): code += body
    if "/agent/runs" not in code or "Agent 运行审计" not in code:
        raise RuntimeError("frontend_audit_code_not_deployed")
    unauthorized = False
    try:
        request("/agent/runs?size=1", authenticated=False)
    except (RuntimeError, urllib.error.HTTPError):
        unauthorized = True
    if not unauthorized:
        raise RuntimeError("anonymous_access_was_not_denied")
    sql = "SELECT json_build_object('username',username,'password',password) FROM public.t_user WHERE username='admin' AND role='admin' AND deleted=0 LIMIT 1;"
    raw = subprocess.check_output(["sudo", "-n", "docker", "exec", "starline-postgres",
                                   "psql", "-U", "starline", "-d", "starline", "-Atc", sql], text=True)
    account = json.loads(raw)
    login = request("/auth/login", account, authenticated=False)
    account.clear()
    token = login["token"]
    if len(sys.argv) > 1:
        run_id = sys.argv[1]
        existing = request("/agent/runs/" + urllib.parse.quote(run_id))
        message_id = existing["run"]["assistantMessageId"]
    else:
        question = "[运行审计联调] 请先调用 search_knowledge 查询星序 MVP 的提醒提前量规则，再用一句话总结；没有依据就明确说未查到。"
        url = BASE + "/agent/v1/chat?" + urllib.parse.urlencode({"question": question})
        req = urllib.request.Request(url, headers={"Authorization": token, "Accept": "text/event-stream"})
        event, data, run_id, message_id = "", [], None, None
        with urllib.request.urlopen(req, timeout=180) as response:
            for raw_line in response:
                line = raw_line.decode("utf-8").rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].strip())
                elif not line:
                    payload = "\n".join(data)
                    if payload and payload != "[DONE]":
                        try:
                            obj = json.loads(payload)
                        except json.JSONDecodeError:
                            obj = {}
                        if isinstance(obj, dict):
                            if event == "meta": run_id = obj.get("taskId")
                            if event in ("finish", "cancel", "confirm"): message_id = obj.get("messageId")
                    if event == "done" or payload == "[DONE]": break
                    event, data = "", []
    if not run_id: raise RuntimeError("no_run_id_from_sse")
    detail = None
    for _ in range(30):
        try:
            candidate = request("/agent/runs/" + urllib.parse.quote(run_id))
            if candidate["run"]["runStatus"] != "RUNNING":
                detail = candidate
                break
        except (RuntimeError, urllib.error.HTTPError):
            pass
        time.sleep(0.3)
    if not detail: raise RuntimeError("audit_did_not_settle")
    run, nodes = detail["run"], detail["nodes"]
    llms = [n for n in nodes if n["nodeType"] == "LLM"]
    tools = [n for n in nodes if n["nodeType"] == "TOOL"]
    roles, has_tool_result = set(), False
    for node in llms:
        payload = node.get("inputPayload") or {}
        if isinstance(payload, str): payload = json.loads(payload)
        for msg in (payload.get("data") or {}).get("messages", []):
            roles.add(msg.get("role"))
            has_tool_result |= any(b.get("type") == "tool_result" for b in msg.get("blocks", []))
    if run["runStatus"] != "NORMAL": raise RuntimeError("model_run_not_normal")
    if not llms or not tools: raise RuntimeError("expected_model_and_tool_nodes")
    if "system" not in roles or "user" not in roles or not has_tool_result:
        raise RuntimeError("actual_model_context_incomplete")
    if run.get("inputTokens") is None: raise RuntimeError("model_usage_missing")
    if run.get("assistantMessageId") != message_id: raise RuntimeError("message_run_association_mismatch")
    linked = request("/agent/runs/by-message/" + urllib.parse.quote(message_id))
    if linked["run"]["runId"] != run_id: raise RuntimeError("message_reverse_lookup_mismatch")
    page = request("/agent/runs?" + urllib.parse.urlencode({"runId": run_id, "messageId": message_id, "size": 20}))
    if page["total"] != 1 or page["records"][0]["runId"] != run_id:
        raise RuntimeError("frontend_list_filter_mismatch")
    print(json.dumps({"frontend_assets_ok": True, "api_via_nginx": True, "list_filter_ok": True, "ok": True, "anonymous_denied": unauthorized, "run_id": run_id,
                      "assistant_message_id": message_id, "status": run["runStatus"],
                      "capture_status": run["captureStatus"], "model_calls": len(llms),
                      "tool_calls": len(tools), "node_count": len(nodes),
                      "input_tokens": run.get("inputTokens"), "output_tokens": run.get("outputTokens"),
                      "has_system_user_tool_context": True}, ensure_ascii=False))
    # Do not log out: Sa-Token may share the account token with an existing user browser session.

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"ok": False, "error_type": type(error).__name__,
                          "check": str(error) if isinstance(error, RuntimeError) else "server_local_verification_failed"}))
        sys.exit(1)
