import { useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";

export function DislikeReasonDialog({ open, onOpenChange, onSubmit }: {
  open: boolean; onOpenChange: (open: boolean) => void; onSubmit: (reason?: string, comment?: string) => Promise<void>;
}) {
  const [reason, setReason] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const close = () => {
    if (busy) return;
    onOpenChange(false);
    setReason("");
    setComment("");
  };
  const submit = async () => {
    if (busy) return;
    setBusy(true);
    try { await onSubmit(reason || undefined, comment.trim() || undefined); onOpenChange(false); setReason(""); setComment(""); }
    catch { /* 调用方展示错误，保留已输入内容以便重试。 */ }
    finally { setBusy(false); }
  };
  return <Dialog open={open} onOpenChange={(value) => { if (value) onOpenChange(true); else close(); }}><DialogContent>
    <DialogHeader><DialogTitle>这次回答哪里需要改进？</DialogTitle><DialogDescription>原因和说明均可选，帮助我们人工定位问题。</DialogDescription></DialogHeader>
    <div className="flex flex-wrap gap-2">{["答非所问", "信息过时", "编造内容", "格式错误", "其他"].map((value) => <button type="button" aria-pressed={reason === value} key={value} disabled={busy} onClick={() => setReason(reason === value ? "" : value)} className={`rounded-full border px-3 py-2 text-sm ${reason === value ? "border-indigo-500 bg-indigo-50 text-indigo-700" : "border-slate-200"}`}>{value}</button>)}</div>
    <textarea aria-label="反馈补充说明" maxLength={1024} rows={4} disabled={busy} className="rounded-xl border p-3 text-sm" placeholder="补充说明（可选，最多 1024 字）" value={comment} onChange={(e) => setComment(e.target.value)} />
    <DialogFooter><Button variant="outline" disabled={busy} onClick={close}>取消</Button><Button disabled={busy} onClick={submit}>{busy ? "提交中…" : "提交点踩"}</Button></DialogFooter>
  </DialogContent></Dialog>;
}
