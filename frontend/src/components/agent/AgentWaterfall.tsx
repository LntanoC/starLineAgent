import { useMemo, useState } from "react";
import { ArrowDownWideNarrow, Braces, Clock3 } from "lucide-react";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { batchWindow, overlapped, formatDuration } from "@/lib/agentTimeline";
import { NODE_LABELS, type AgentAuditNode } from "@/services/agentAuditService";
import type { AgentBlockUI } from "@/types/agent";

const time = (value?: string) => value ? new Date(value).getTime() : undefined;
const numeric = (value: number | undefined) => value != null && Number.isFinite(value) ? value : undefined;
const pretty = (value: unknown) => {
  if (value == null) return "未采集";
  if (typeof value === "string") { try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
  return JSON.stringify(value, null, 2);
};
const color = (node: AgentAuditNode) => node.status === "FAILED" || node.status === "INTERRUPTED" ? "bg-rose-400" : node.nodeType === "LLM" ? "bg-violet-500" : node.nodeType === "TOOL" ? "bg-teal-500" : "bg-slate-400";

export function AgentWaterfall({ nodes, loading = false }: { nodes: AgentAuditNode[]; loading?: boolean }) {
  const [sort, setSort] = useState<"time" | "duration">("time");
  const [selected, setSelected] = useState<AgentAuditNode | null>(null);
  const range = useMemo(() => {
    const starts = nodes.map(n => numeric(time(n.startedAt))).filter((n): n is number => n != null);
    const ends = nodes.map(n => numeric(time(n.endedAt))).filter((n): n is number => n != null);
    const start = starts.length ? Math.min(...starts) : 0;
    return { start, duration: Math.max(1, Math.max(start, ...starts, ...ends) - start) };
  }, [nodes]);
  const ordered = useMemo(() => [...nodes].sort((a, b) => sort === "duration" ? (b.durationMs ?? -1) - (a.durationMs ?? -1) : (time(a.startedAt) ?? Number.MAX_SAFE_INTEGER) - (time(b.startedAt) ?? Number.MAX_SAFE_INTEGER) || a.sequence - b.sequence), [nodes, sort]);
  const batches = useMemo(() => {
    const groups = new Map<string, AgentAuditNode[]>();
    for (const node of nodes) if (node.nodeType === "TOOL" && node.batchId) groups.set(node.batchId, [...(groups.get(node.batchId) ?? []), node]);
    return [...groups].filter(([, group]) => group.length > 1).map(([id, group]) => {
      const blocks: AgentBlockUI[] = group.map((node, index) => ({
        id: index, kind: "tool", at: "", startedAt: numeric(time(node.startedAt)), endedAt: numeric(time(node.endedAt)),
        durationMs: node.durationMs, durationSource: node.durationSource === "tool" ? "tool" : "batch"
      }));
      const precise = group.some(n => n.durationSource === "tool");
      return { id, count: group.length, duration: batchWindow(blocks, precise).durationMs, parallel: precise ? overlapped(blocks) : undefined };
    });
  }, [nodes]);
  return <section className="space-y-3">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="flex items-center gap-2 font-semibold"><Clock3 className="h-4 w-4" />执行瀑布图 <span className="text-xs font-normal text-slate-500">{nodes.length} 个节点</span></h3>
      <Button variant="outline" size="sm" onClick={() => setSort(sort === "time" ? "duration" : "time")}><ArrowDownWideNarrow className="mr-2 h-4 w-4" />{sort === "time" ? "按开始时间" : "按耗时"}</Button></div>
    {batches.length > 0 && <div className="flex flex-wrap gap-2">{batches.map(b => <span key={b.id} className="rounded-full bg-teal-50 px-3 py-1 text-xs text-teal-800">工具批 · {b.count} 次调用 · {b.parallel === true ? "区间重叠" : b.parallel === false ? "依次执行" : "执行区间未知"}{b.duration != null ? ` · ${formatDuration(b.duration)}` : ""}</span>)}</div>}
    {!nodes.length ? <div className="rounded-xl border border-dashed p-10 text-center text-sm text-slate-500">{loading ? "等待采集数据…" : "暂无审计节点；历史对话不会补回未采集的 prompt。"}</div> :
      <div className="overflow-x-auto rounded-xl border bg-white"><div className="min-w-[720px]">
        <div className="grid grid-cols-[250px_minmax(160px,1fr)_170px] gap-4 border-b bg-slate-50 px-4 py-3 text-xs text-slate-500"><span>节点 / 状态</span><span>相对执行时间 · 点击查看输入与输出</span><span className="text-right">耗时 / Token</span></div>
        {ordered.map(n => {
          const start = numeric(time(n.startedAt)); const end = numeric(time(n.endedAt));
          const left = start == null ? 0 : Math.max(0, Math.min(100, (start - range.start) / range.duration * 100));
          const width = start == null ? 0 : Math.max(0.8, Math.min(100 - left, ((end ?? start) - start) / range.duration * 100));
          const indent = n.nodeType === "TOOL" || n.nodeType === "CONFIRM" ? 20 : n.nodeType === "LLM" ? 10 : 0;
          return <button key={n.nodeId} type="button" onClick={() => setSelected(n)} className="grid w-full grid-cols-[250px_minmax(160px,1fr)_170px] items-center gap-4 border-b px-4 py-3 text-left transition last:border-0 hover:bg-indigo-50/50">
            <div style={{ paddingLeft: indent }}><div className="truncate text-sm font-medium" title={n.nodeName}>{n.nodeType === "LLM" ? `第 ${n.roundIndex} 轮 · ${n.nodeName}` : n.nodeName}</div><p className="mt-1 text-xs text-slate-500">{n.nodeType} · {NODE_LABELS[n.status] || n.status}{n.toolCallId ? ` · #${n.sequence}` : ""}</p></div>
            <div className="relative h-6 rounded bg-slate-100">{start != null ? <span style={{ left: `${left}%`, width: `${width}%` }} className={`absolute top-1 h-4 rounded ${color(n)}`} /> : <span className="px-2 text-[11px] leading-6 text-slate-400">未执行或时间未记录</span>}</div>
            <div className="text-right text-xs"><p className="font-mono">{formatDuration(n.durationMs) || "—"}</p><p className="mt-1 text-slate-500">{n.inputTokens == null ? "Token 未返回" : `输入 ${n.inputTokens} / 输出 ${n.outputTokens ?? "—"}`}</p></div>
          </button>;
        })}
      </div></div>}
    <Dialog open={selected != null} onOpenChange={open => !open && setSelected(null)}><DialogContent className="max-h-[90vh] max-w-5xl overflow-y-auto">
      <DialogHeader><DialogTitle className="flex items-center gap-2"><Braces className="h-5 w-5" />{selected?.nodeName}</DialogTitle><DialogDescription>节点 {selected?.nodeId} · {selected?.nodeType} · {selected && (NODE_LABELS[selected.status] || selected.status)}</DialogDescription></DialogHeader>
      {selected && <><div className="grid grid-cols-2 gap-2 rounded-xl bg-slate-50 p-4 text-xs sm:grid-cols-4"><span>耗时 {formatDuration(selected.durationMs) || "未知"}</span><span>首字 {formatDuration(selected.ttftMs) || "未记录"}</span><span>输入 {selected.inputTokens ?? "未知"}</span><span>输出 {selected.outputTokens ?? "未知"} · 缓存 {selected.cachedTokens ?? "未知"}</span></div>
        <p className="break-all text-xs text-slate-500">开始 {selected.startedAt || "未记录"} · 结束 {selected.endedAt || "未记录"} · parent {selected.parentNodeId || "根节点"}{selected.toolCallId ? ` · callId ${selected.toolCallId}` : ""}</p>
        {selected.errorMessage && <p className="rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{selected.errorMessage}</p>}
        {[["输入 / 完整 Prompt", selected.inputPayload], ["输出 / Reasoning / 工具结果", selected.outputPayload]].map(([title, value]) => <section key={String(title)}><h4 className="mb-2 text-sm font-semibold">{String(title)}</h4><pre className="max-h-[45vh] overflow-auto whitespace-pre-wrap break-words rounded-xl bg-slate-950 p-4 font-mono text-xs leading-6 text-slate-100">{pretty(value)}</pre></section>)}</>}
    </DialogContent></Dialog>
  </section>;
}
