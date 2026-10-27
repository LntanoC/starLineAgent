import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { getEvalSamples, applyBadCaseMapping, reviewBadCase, ROOT_CAUSES, FIX_ACTIONS, REVIEW_STATUSES, type BadCaseRecord } from "@/services/badCaseService";

const fieldClass = "w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm";
export function BadCaseReviewForm({ item, onSaved }: { item: BadCaseRecord; onSaved: () => Promise<void> }) {
  const [form, setForm] = useState({ rootCause: "", rootCauseDetail: "", fixAction: "NONE", fixRef: "", fixNote: "", reviewStatus: "REVIEWED" });
  const [mapping, setMapping] = useState({ sourceTerm: "", targetTerm: "" });
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setForm({ rootCause: item.rootCause || "", rootCauseDetail: item.rootCauseDetail || "", fixAction: item.fixAction || "NONE",
      fixRef: item.fixRef || "", fixNote: item.fixNote || "", reviewStatus: item.reviewStatus === "PENDING" ? "REVIEWED" : item.reviewStatus });
    setMapping({ sourceTerm: "", targetTerm: "" });
  }, [item.id, item.revision, item]);
  const change = (key: keyof typeof form, value: string) => setForm((prev) => ({ ...prev, [key]: value }));
  const save = async (applyMapping = false) => {
    if (busy) return;
    if (["REVIEWED", "FIXED"].includes(form.reviewStatus) && !form.rootCause) { toast.error("请先选择根因"); return; }
    if (applyMapping && (!form.rootCause || !mapping.sourceTerm.trim() || !mapping.targetTerm.trim())) { toast.error("请选择根因并填写原始词和目标词"); return; }
    setBusy(true);
    try {
      if (!applyMapping && form.reviewStatus === "FIXED") {
        const samples = await getEvalSamples(item.id);
        if (samples.some(s => s.status !== "RETIRED" && s.last_result !== "PASSED")) { toast.error("关联评测尚未全部通过，请先完成回归"); return; }
      }
      if (applyMapping) {
        await applyBadCaseMapping(item.id, { ...mapping, mappingId: form.fixRef || undefined, rootCause: form.rootCause,
          rootCauseDetail: form.rootCauseDetail, fixNote: form.fixNote, revision: item.revision });
        toast.success("映射已应用并记录到台账，请回归验证后标记已修复");
      } else {
        await reviewBadCase(item.id, { ...form, rootCause: form.rootCause || undefined, revision: item.revision });
        toast.success("人工判定已保存");
      }
      await onSaved();
    } catch (error) { toast.error((error as Error).message || "保存失败"); }
    finally { setBusy(false); }
  };
  const fixLink = ({ MAPPING: "/admin/mappings", KNOWLEDGE_BASE: "/admin/knowledge",
    PROMPT: "/admin/agents", RERANK: "/admin/settings", TOOL: "/admin/mcp-tools" } as Record<string, string>)[form.fixAction];
  return (
    <section className="space-y-4 rounded-2xl border border-slate-200 bg-slate-50 p-5">
      <div><h3 className="font-semibold">人工判定与修复记录</h3><p className="mt-1 text-xs text-slate-500">自动线索供核查。保存判定后，可记录修复对象及回归结论。</p></div>
      <div className="grid gap-4 sm:grid-cols-3">
        <label className="space-y-1 text-sm">根因<select aria-label="根因" className={fieldClass} value={form.rootCause} onChange={(e) => change("rootCause", e.target.value)}><option value="">请选择</option>{Object.entries(ROOT_CAUSES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <label className="space-y-1 text-sm">修复动作<select aria-label="修复动作" className={fieldClass} value={form.fixAction} onChange={(e) => change("fixAction", e.target.value)}>{Object.entries(FIX_ACTIONS).map(([key, label]) => <option key={key} value={key} disabled={key === "INTENT_NODE"}>{label}</option>)}</select></label>
        <label className="space-y-1 text-sm">判定状态<select aria-label="判定状态" className={fieldClass} value={form.reviewStatus} onChange={(e) => change("reviewStatus", e.target.value)}>{Object.entries(REVIEW_STATUSES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      </div>
      <label className="block space-y-1 text-sm">根因详情<textarea aria-label="根因详情" className={fieldClass} rows={3} maxLength={1024} value={form.rootCauseDetail} onChange={(e) => change("rootCauseDetail", e.target.value)} /></label>
      <label className="block space-y-1 text-sm">修复对象 ID<Input maxLength={128} value={form.fixRef} onChange={(e) => change("fixRef", e.target.value)} placeholder="映射 ID / MCP 工具 ID / 文档 ID 等" /></label>
      <label className="block space-y-1 text-sm">修复及回归备注<textarea aria-label="修复备注" className={fieldClass} rows={3} maxLength={1024} value={form.fixNote} onChange={(e) => change("fixNote", e.target.value)} /></label>
      {form.fixAction === "MAPPING" && <div className="space-y-3 rounded-xl border border-indigo-100 bg-white p-4">
        <p className="text-sm font-medium">应用关键词映射</p><p className="text-xs text-slate-500">修复对象 ID 留空时创建精确匹配；填写 ID 时更新该规则的词项并启用。应用后保留为“已判定”，完成回归再标记“已修复”。</p>
        <div className="grid gap-3 sm:grid-cols-2"><Input aria-label="原始词" maxLength={128} placeholder="原始词" value={mapping.sourceTerm} onChange={(e) => setMapping((v) => ({ ...v, sourceTerm: e.target.value }))} /><Input aria-label="目标词" maxLength={128} placeholder="目标词" value={mapping.targetTerm} onChange={(e) => setMapping((v) => ({ ...v, targetTerm: e.target.value }))} /></div>
        <Button variant="outline" disabled={busy} onClick={() => save(true)}>{form.fixRef ? "更新并应用映射" : "创建并应用映射"}</Button>
      </div>}
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-slate-500">{item.reviewer ? `最近判定人：${item.reviewer}` : "尚未人工判定"}{fixLink && <> · <Link className="text-indigo-600 underline" to={fixLink} target="_blank">打开对应配置</Link></>}</span>
        <Button disabled={busy} onClick={() => save()}>{busy ? "保存中…" : "保存判定"}</Button>
      </div>
    </section>
  );
}
