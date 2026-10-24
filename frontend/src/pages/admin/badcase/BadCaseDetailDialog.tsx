import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { AgentAuditByMessage } from "@/pages/admin/agent-runs/AgentRunDetailPage";
import { BadCaseReviewForm } from "./BadCaseReviewForm";
import type { BadCaseRecord } from "@/services/badCaseService";

const MATCH_LABELS: Record<string, string> = { AGENT_AUDIT: "Agent 运行审计精确关联", DIRECT: "消息与历史链路直接关联", TIME_CANDIDATE: "时间候选（可能错配）",
  AMBIGUOUS: "多个候选，未选择", MISSING: "链路缺失", AGENT_BLOCKS: "本条 Agent 消息的事件快照",
};
const safeUrl = (value?: string | null) => value && /^https?:\/\//i.test(value) ? value : undefined;
export function BadCaseDetailDialog({ item, open, onOpenChange, onSaved }: {
  item: BadCaseRecord | null; open: boolean; onOpenChange: (open: boolean) => void; onSaved: () => Promise<void>;
}) {
  const r = item?.report;
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="max-h-[92vh] max-w-6xl overflow-y-auto">
    <DialogHeader><DialogTitle>Bad case 详情</DialogTitle><DialogDescription>{item ? `工单 ${item.id} · ${r?.messageType} · 消息 ${r?.messageId}` : "加载中…"}</DialogDescription></DialogHeader>
    {item && r && <div className="space-y-5">
      <div className="rounded-xl bg-amber-50 p-4 text-sm text-amber-900"><p className="font-medium">{MATCH_LABELS[r.traceMatchStatus] || r.traceMatchStatus}</p>
        <p className="mt-1 break-all text-xs">用户 {r.userId} · 会话 {r.conversationId} · 运行 ID {r.runId || "未关联"} · 消息状态 {r.messageStatus}</p>
        <ul className="mt-2 list-inside list-disc space-y-1 text-xs">{(r.evidenceNotes || []).map((note, index) => <li key={index}>{note}</li>)}</ul>
      </div>
      <div className="grid gap-4 md:grid-cols-2">{[["用户问题", r.userQuestion], ["助手回答", r.assistantAnswer]].map(([label, value]) => <section key={label} className="rounded-xl border p-4"><h3 className="mb-2 text-sm font-semibold">{label}</h3><p className="max-h-72 overflow-auto whitespace-pre-wrap break-words text-sm leading-7">{value || "未记录"}</p></section>)}</div>
      <div className="rounded-xl border p-4 text-sm"><p>反馈原因：{r.reason || "未填写"}</p>{r.comment && <p className="mt-2 whitespace-pre-wrap">补充说明：{r.comment}</p>}</div>
      <section className="rounded-xl border border-indigo-100 bg-indigo-50/50 p-4"><h3 className="font-semibold">自动初步线索</h3><ul className="mt-2 list-inside list-disc space-y-2 text-sm">{(r.suspicions || []).map((text, index) => <li key={index}>{text}</li>)}</ul></section>
      {r.thinkingContent && <details className="rounded-xl border p-4"><summary className="cursor-pointer text-sm font-medium">模型 reasoning 记录</summary><pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-words text-xs leading-6">{r.thinkingContent}</pre></details>}
      <section className="space-y-3"><h3 className="font-semibold">来源与消息侧 grounding</h3>
        {(r.sources || []).length === 0 && <p className="text-sm text-slate-500">没有消息侧文档来源快照；Agent 的工具结果请在下方节点中展开查看。</p>}
        {(r.sources || []).map((s, index) => <div key={index} className="rounded-xl border p-3 text-sm">
          {s.docId ? <a href={`/preview/doc/${encodeURIComponent(s.docId)}`} target="_blank" rel="noreferrer" className="font-medium text-indigo-600 underline">{s.docName || s.docId}</a> : <span>{s.docName || "来源"}</span>}
          {safeUrl(s.url) && <a href={safeUrl(s.url)} target="_blank" rel="noreferrer" className="ml-3 text-xs underline">原始链接</a>}
          <p className="mt-2 whitespace-pre-wrap">{s.excerpt}</p>
        </div>)}
        {(r.retrievedChunks || []).map((chunk, index) => <details key={index} className="rounded-xl border p-3 text-sm"><summary className="cursor-pointer">Grounding · {chunk.docName || index + 1}</summary><p className="mt-2 whitespace-pre-wrap">{chunk.text}</p></details>)}
      </section>
      {r.messageType === "AGENT" && <AgentAuditByMessage messageId={r.messageId} />}
      <section className="space-y-3"><h3 className="font-semibold">分析时的节点快照</h3>{!(r.traceNodes || []).length && <p className="text-sm text-slate-500">没有可用节点，无法据此判定执行路径。</p>}
        {(r.traceNodes || []).map((node, index) => <details key={node.nodeId || index} className={`rounded-lg border p-3 ${node.status === "ERROR" ? "border-red-200 bg-red-50 text-red-800" : "border-slate-200"}`} style={{ marginLeft: Math.min(node.depth || 0, 8) * 14 }}>
          <summary className="cursor-pointer text-sm"><span className="mr-2 font-mono text-xs">{node.nodeType}</span>{node.nodeName} <span className="ml-2 text-xs">{node.status} · {node.durationMs == null ? "耗时未知" : `${node.durationMs} ms`}</span></summary>
          <p className="mt-2 break-all font-mono text-xs">nodeId: {node.nodeId} · parent: {node.parentNodeId || "root"}</p>
          {node.errorMessage && <pre className="mt-2 whitespace-pre-wrap break-words text-xs">{node.errorMessage}</pre>}
          {node.extraData && <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words text-xs">{node.extraData}</pre>}
        </details>)}
      </section>
      <BadCaseReviewForm item={item} onSaved={onSaved} />
    </div>}
  </DialogContent></Dialog>;
}
