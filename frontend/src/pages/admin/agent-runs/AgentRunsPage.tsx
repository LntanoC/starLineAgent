import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { RefreshCw, Search, Activity } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatDuration } from "@/lib/agentTimeline";
import { getAgentRuns, RUN_LABELS, CAPTURE_LABELS, type AgentAuditPage, type AgentAuditFilters } from "@/services/agentAuditService";
import { getErrorMessage } from "@/utils/error";

const SELECT = "h-10 rounded-xl border border-slate-200 bg-white px-3 text-sm";
export function AgentRunsPage() {
  const [search] = useSearchParams();
  const [filters, setFilters] = useState<AgentAuditFilters>({ runId: search.get("runId") || "", conversationId: search.get("conversationId") || "", messageId: search.get("messageId") || "" });
  const [query, setQuery] = useState<AgentAuditFilters>(filters);
  const [page, setPage] = useState(1);
  const [data, setData] = useState<AgentAuditPage>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const seq = useRef(0);
  const load = useCallback(async () => {
    const id = ++seq.current; setLoading(true); setError("");
    try { const result = await getAgentRuns({ ...query, current: page, size: 20 }); if (seq.current === id) setData(result); }
    catch (e) { if (seq.current === id) setError(getErrorMessage(e, "加载运行记录失败")); }
    finally { if (seq.current === id) setLoading(false); }
  }, [query, page]);
  useEffect(() => { void load(); return () => { seq.current++; }; }, [load]);
  useEffect(() => { if (!data?.records.some(r => r.runStatus === "RUNNING")) return; const id = setInterval(() => void load(), 5000); return () => clearInterval(id); }, [data, load]);
  const update = (key: keyof AgentAuditFilters, value: unknown) => setFilters(prev => ({ ...prev, [key]: value || undefined }));
  return <div className="space-y-6">
    <div className="flex flex-wrap items-center justify-between gap-4"><div><h1 className="flex items-center gap-2 text-2xl font-bold"><Activity className="h-6 w-6 text-indigo-600" />Agent 运行审计</h1><p className="mt-2 text-sm text-slate-500">查看每次对话的模型轮次、工具调用、Prompt、Reasoning 与 Token。</p></div><Button variant="outline" onClick={() => void load()} disabled={loading}><RefreshCw className="mr-2 h-4 w-4" />刷新</Button></div>
    <form className="space-y-3 rounded-2xl border bg-white p-4" onSubmit={e => { e.preventDefault(); setPage(1); setQuery({ ...filters }); }}>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"><Input placeholder="运行 ID / taskId" value={filters.runId || ""} onChange={e => update("runId", e.target.value)} /><Input placeholder="会话 ID" value={filters.conversationId || ""} onChange={e => update("conversationId", e.target.value)} />
        <select className={SELECT} value={filters.runStatus || ""} onChange={e => update("runStatus", e.target.value)}><option value="">全部状态</option>{Object.entries(RUN_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
        <Input type="number" min={0} placeholder="最少输入 Token" value={filters.minInputTokens ?? ""} onChange={e => update("minInputTokens", e.target.value ? Number(e.target.value) : undefined)} /></div>
      <div className="flex flex-wrap items-end gap-3"><label className="text-xs text-slate-500">开始时间<Input type="datetime-local" value={filters.beginTime || ""} onChange={e => update("beginTime", e.target.value)} /></label><label className="text-xs text-slate-500">结束时间<Input type="datetime-local" value={filters.endTime || ""} onChange={e => update("endTime", e.target.value)} /></label>
        <label className="flex h-10 items-center gap-2 text-sm"><input type="checkbox" checked={!!filters.slowOnly} onChange={e => update("slowOnly", e.target.checked)} />仅看慢运行（≥30秒）</label><Button type="submit"><Search className="mr-2 h-4 w-4" />查询</Button>
        {filters.messageId && <span className="text-xs text-slate-500">按消息 {filters.messageId} 查找</span>}</div>
    </form>
    {error && <p role="alert" className="rounded-xl bg-rose-50 p-4 text-sm text-rose-700">{error}</p>}
    <div className="overflow-x-auto rounded-2xl border bg-white"><table className="w-full min-w-[1000px] text-left text-sm"><thead className="border-b bg-slate-50 text-xs text-slate-500"><tr>{["时间 / 运行 ID", "问题 / 会话", "状态", "轮次 / 模型 / 工具", "耗时", "输入 / 输出 Token"].map(s => <th key={s} className="px-4 py-3 font-medium">{s}</th>)}</tr></thead>
      <tbody>{data?.records.map(r => <tr key={r.runId} className="border-b last:border-0 hover:bg-indigo-50/40"><td className="px-4 py-4"><Link to={`/admin/agent-runs/${encodeURIComponent(r.runId)}`} className="font-medium text-indigo-600 hover:underline">{new Date(r.createTime).toLocaleString()}</Link><p className="mt-1 font-mono text-[11px] text-slate-400">{r.runId}</p></td><td className="max-w-sm px-4 py-4"><Link className="line-clamp-2 hover:text-indigo-600" to={`/admin/agent-runs/${encodeURIComponent(r.runId)}`}>{r.question || "确认后续跑"}</Link><p className="mt-1 truncate font-mono text-[11px] text-slate-400">{r.conversationId}</p></td><td className="px-4 py-4"><span className={`rounded-full px-2 py-1 text-xs ${r.runStatus === "FAILED" ? "bg-rose-50 text-rose-700" : r.runStatus === "RUNNING" ? "bg-indigo-50 text-indigo-700" : "bg-slate-100 text-slate-700"}`}>{RUN_LABELS[r.runStatus] || r.runStatus}</span><p className={`mt-2 text-xs ${r.captureStatus === "PARTIAL" ? "text-amber-600" : "text-slate-400"}`}>{CAPTURE_LABELS[r.captureStatus] || r.captureStatus}</p></td><td className="px-4 py-4 font-mono">{r.roundCount} / {r.modelCallCount} / {r.toolCallCount}</td><td className="px-4 py-4 font-mono">{formatDuration(r.durationMs) || "—"}</td><td className="px-4 py-4 font-mono">{r.inputTokens ?? "未知"} / {r.outputTokens ?? "未知"}</td></tr>)}</tbody></table>
      {!data?.records.length && <p className="p-12 text-center text-sm text-slate-500">{loading ? "加载中…" : "暂无 Agent 运行记录。部署后发起新对话即可采集，历史 prompt 无法补回。"}</p>}
      <div className="flex items-center justify-between border-t px-4 py-3 text-sm text-slate-500"><span>共 {data?.total ?? 0} 条 · 第 {page} 页</span><div className="flex gap-2"><Button variant="outline" size="sm" disabled={page <= 1 || loading} onClick={() => setPage(n => n - 1)}>上一页</Button><Button variant="outline" size="sm" disabled={!data || page >= data.pages || loading} onClick={() => setPage(n => n + 1)}>下一页</Button></div></div>
    </div>
  </div>;
}
