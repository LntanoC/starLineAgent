import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AgentWaterfall } from "@/components/agent/AgentWaterfall";
import { formatDuration } from "@/lib/agentTimeline";
import { getAgentRun, getAgentRunByMessage, RUN_LABELS, CAPTURE_LABELS, type AgentAuditDetail } from "@/services/agentAuditService";
import { getErrorMessage } from "@/utils/error";

export function AgentRunDetailPage() {
  const { runId = "" } = useParams();
  const [detail, setDetail] = useState<AgentAuditDetail>();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const seq = useRef(0);
  const load = useCallback(async () => { const id = ++seq.current; setLoading(true); setError(""); try { const r = await getAgentRun(runId); if (id === seq.current) setDetail(r); } catch (e) { if (id === seq.current) setError(getErrorMessage(e, "加载运行详情失败")); } finally { if (id === seq.current) setLoading(false); } }, [runId]);
  useEffect(() => { void load(); return () => { seq.current++; }; }, [load]);
  useEffect(() => { if (detail?.run.runStatus !== "RUNNING") return; const timer = setInterval(() => void load(), 3000); return () => clearInterval(timer); }, [detail?.run.runStatus, load]);
  const r = detail?.run;
  return <div className="space-y-6"><div className="flex items-center justify-between"><Link to="/admin/agent-runs" className="flex items-center gap-2 text-sm text-slate-500"><ArrowLeft className="h-4 w-4" />运行列表</Link><Button variant="outline" onClick={() => void load()} disabled={loading}><RefreshCw className="mr-2 h-4 w-4" />刷新</Button></div>
    <div><h1 className="text-2xl font-bold">Agent 运行详情</h1><p className="mt-2 break-all font-mono text-xs text-slate-400">{runId}</p></div>
    {error && <p role="alert" className="rounded-xl bg-rose-50 p-4 text-rose-700">{error}</p>}
    {r && <><div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{[
      ["运行状态", RUN_LABELS[r.runStatus] || r.runStatus, CAPTURE_LABELS[r.captureStatus] || r.captureStatus],
      ["总耗时", formatDuration(r.durationMs) || "运行中", `${r.roundCount} 轮推理`],
      ["调用次数", `模型 ${r.modelCallCount} / 工具 ${r.toolCallCount}`, "按真实调用计数"],
      ["Token", `${r.inputTokens ?? "未知"} / ${r.outputTokens ?? "未知"}`, `输入 / 输出 · 缓存 ${r.cachedTokens ?? "未知"}`]
    ].map(([label, value, note]) => <div key={label} className="rounded-2xl border bg-white p-5"><p className="text-xs text-slate-500">{label}</p><p className="mt-2 text-xl font-semibold">{value}</p><p className="mt-2 text-xs text-slate-400">{note}</p></div>)}</div>
      <div className="rounded-xl border bg-white p-4 text-xs text-slate-500"><p className="break-all">会话 {r.conversationId} · 用户 {r.userId} · 助手消息 {r.assistantMessageId || "尚未落库"}</p><p className="mt-2">开始 {new Date(r.createTime).toLocaleString()} · 最近更新 {new Date(r.updateTime).toLocaleString()}</p>{r.parentRunId && <Link className="mt-2 inline-block text-indigo-600 underline" to={`/admin/agent-runs/${encodeURIComponent(r.parentRunId)}`}>查看确认前的运行</Link>}</div>
      {r.captureStatus === "PARTIAL" && <p className="rounded-xl bg-amber-50 p-4 text-sm text-amber-800">此运行存在采集截断或写入丢失，节点可能不完整，请结合消息与服务日志分析。</p>}
      {r.errorMessage && <p className="rounded-xl bg-rose-50 p-4 text-sm text-rose-700">{r.errorMessage}</p>}
      <div className="grid gap-4 md:grid-cols-2">{[["用户问题", r.question], ["最终回答", r.answer]].map(([label, value]) => <details key={label} className="rounded-xl border bg-white p-4"><summary className="cursor-pointer text-sm font-medium">{label}</summary><p className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-words text-sm leading-7">{value || "未记录"}</p></details>)}</div>
      <AgentWaterfall nodes={detail?.nodes || []} loading={loading} /></>}
  </div>;
}

/** Same replay component inside the offline bad-case view. */
export function AgentAuditByMessage({ messageId }: { messageId: string }) {
  const [detail, setDetail] = useState<AgentAuditDetail | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => { let active = true; setLoading(true); setDetail(null); void getAgentRunByMessage(messageId).then(r => { if (active) setDetail(r); }).catch(() => {}).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [messageId]);
  if (!loading && !detail) return <p className="text-sm text-slate-500">这条历史消息没有完整运行审计；下方保留消息侧事件快照。</p>;
  return <section className="space-y-3">{detail && <Link to={`/admin/agent-runs/${encodeURIComponent(detail.run.runId)}`} target="_blank" className="text-sm text-indigo-600 underline">打开完整运行详情</Link>}<AgentWaterfall nodes={detail?.nodes || []} loading={loading} /></section>;
}
