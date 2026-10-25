import {useEffect,useState} from "react";
import {toast} from "sonner";
import {Button} from "@/components/ui/button";
import {getBadCaseSignals,getReviewHistory,promoteBadCase,SOURCES,TRIAGE,type BadCaseSignal} from "@/services/badCaseService";
import {BadCaseEvalPanel} from "./BadCaseEvalPanel";
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
  const [signals,setSignals]=useState<BadCaseSignal[]>([]),[history,setHistory]=useState<unknown[]>([]);
  useEffect(()=>{setSignals([]);setHistory([]);if(!item)return;let active=true;Promise.all([getBadCaseSignals(item.id),getReviewHistory(item.id)]).then(([s,h])=>{if(active){setSignals(s);setHistory(h);}}).catch(e=>toast.error((e as Error).message));return()=>{active=false;};},[item?.id,item?.revision]);
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="max-h-[92vh] max-w-6xl overflow-y-auto">
    <DialogHeader><DialogTitle>Bad case 详情</DialogTitle><DialogDescription>{item ? `候选 ${item.id} · ${item.candidate.subject_type} · ${item.candidate.subject_id}` : "加载中…"}</DialogDescription></DialogHeader>
    {item && r && <div className="space-y-5">
      <section className="rounded-xl border p-4 text-sm"><p>{item.candidate.confidence==="HIGH"?"高置信信号":"低置信候选"} · {item.candidate.severity} · {TRIAGE[item.candidate.triage_status]} · 快照 {item.candidate.snapshot_status}</p><p className="mt-2 text-xs text-slate-500">高置信不等于人工已确认；机器分诊建议不是人工根因。</p>{item.reviewTime&&new Date(item.candidate.last_detected_at)>new Date(item.reviewTime)&&<p className="mt-2 text-amber-700">审核后有新证据；原人工结论保留，请核查信号时间线。</p>}{item.reviewStatus==="PENDING"&&item.candidate.triage_status!=="READY_FOR_HUMAN"&&<Button className="mt-3" size="sm" onClick={async()=>{try{await promoteBadCase(item.id,item.revision);await onSaved();}catch(e){toast.error((e as Error).message);}}}>人工提升到待办</Button>}</section>
      <section className="space-y-2"><h3 className="font-semibold">信号时间线</h3>{signals.map(signal=><details key={signal.id} className={`rounded-lg border p-3 text-xs ${signal.invalidated_at?"opacity-50":""}`}><summary className="cursor-pointer">{new Date(signal.occurred_at).toLocaleString("zh-CN")} · {SOURCES[signal.source]||signal.source} · {signal.rule_code} · {signal.confidence} / {signal.severity}{signal.invalidated_at?" · 已失效":""}</summary><p className="my-2">版本 {signal.detector_version} · {signal.invalid_reason||signal.occurrence_key}</p><pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words">{JSON.stringify(signal.evidence,null,2)}</pre></details>)}</section>
      {item.candidate.auto_review_result!=null&&<section className="rounded-xl border p-4"><h3 className="font-semibold">自动评审 · 机器分诊建议</h3><p className="text-xs">模型 {item.candidate.auto_review_model}</p><pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap text-xs">{JSON.stringify(item.candidate.auto_review_result,null,2)}</pre></section>}
      <div className="rounded-xl bg-amber-50 p-4 text-sm text-amber-900"><p className="font-medium">{MATCH_LABELS[r.traceMatchStatus] || r.traceMatchStatus}</p>
        <p className="mt-1 break-all text-xs">用户 {r.userId} · 会话 {r.conversationId} · 运行 ID {r.runId || "未关联"} · 消息状态 {r.messageStatus}</p>
        <ul className="mt-2 list-inside list-disc space-y-1 text-xs">{(r.evidenceNotes || []).map((note, index) => <li key={index}>{note}</li>)}</ul>
      </div>
      {r.messageId&&<div className="grid gap-4 md:grid-cols-2">{[["用户问题", r.userQuestion], ["助手回答", r.assistantAnswer]].map(([label, value]) => <section key={label} className="rounded-xl border p-4"><h3 className="mb-2 text-sm font-semibold">{label}</h3><p className="max-h-72 overflow-auto whitespace-pre-wrap break-words text-sm leading-7">{value || "未记录"}</p></section>)}</div>}
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
      {r.messageType === "AGENT" && r.messageId && <AgentAuditByMessage messageId={r.messageId} />}
      <section className="space-y-3"><h3 className="font-semibold">分析时的节点快照</h3>{!(r.traceNodes || []).length && <p className="text-sm text-slate-500">没有可用节点，无法据此判定执行路径。</p>}
        {(r.traceNodes || []).map((node, index) => <details key={node.nodeId || index} className={`rounded-lg border p-3 ${node.status === "ERROR" ? "border-red-200 bg-red-50 text-red-800" : "border-slate-200"}`} style={{ marginLeft: Math.min(node.depth || 0, 8) * 14 }}>
          <summary className="cursor-pointer text-sm"><span className="mr-2 font-mono text-xs">{node.nodeType}</span>{node.nodeName} <span className="ml-2 text-xs">{node.status} · {node.durationMs == null ? "耗时未知" : `${node.durationMs} ms`}</span></summary>
          <p className="mt-2 break-all font-mono text-xs">nodeId: {node.nodeId} · parent: {node.parentNodeId || "root"}</p>
          {node.errorMessage && <pre className="mt-2 whitespace-pre-wrap break-words text-xs">{node.errorMessage}</pre>}
          {node.extraData && <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words text-xs">{node.extraData}</pre>}
        </details>)}
      </section>
      <BadCaseReviewForm item={item} onSaved={onSaved} />
      <BadCaseEvalPanel item={item}/>
      {!!history.length&&<details className="rounded-xl border p-4"><summary>人工审核 / 重开历史（{history.length}）</summary><pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap text-xs">{JSON.stringify(history,null,2)}</pre></details>}
    </div>}
  </DialogContent></Dialog>;
}
