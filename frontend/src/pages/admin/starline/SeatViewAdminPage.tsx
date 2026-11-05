import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { SeatMap } from "@/components/agent/SeatMap";
import { listSeatViews, saveSeatView } from "@/services/seatViewService";
import type { SeatViewDocument } from "@/lib/seatView";
import { getErrorMessage } from "@/utils/error";

export function SeatViewAdminPage() {
  const [catalog, setCatalog] = useState<SeatViewDocument[]>([]);
  const [editor, setEditor] = useState("");
  const [preview, setPreview] = useState<SeatViewDocument | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    listSeatViews(true, controller.signal).then(setCatalog).catch((failure) => {
      if (!controller.signal.aborted) setError(getErrorMessage(failure, "加载失败"));
    });
    return () => controller.abort();
  }, [reload]);

  async function save(status: SeatViewDocument["show"]["status"]) {
    setError("");
    setBusy(true);
    try {
      const document: SeatViewDocument = JSON.parse(editor);
      if (!document?.venue || !document.show) throw new Error("JSON 须包含 venue 和 show");
      if (status === "PUBLISHED" && !reviewed) throw new Error("请先确认来源、分区、票档和舞台已核对");
      const next = { ...document, show: { ...document.show, status } };
      await saveSeatView(next);
      setPreview(next);
      setEditor(JSON.stringify(next, null, 2));
      setReviewed(false);
      setCatalog(await listSeatViews(true));
      toast.success(status === "PUBLISHED" ? "已发布，用户可查看此图" : status === "ARCHIVED" ? "已下架" : "草稿已校验保存");
    } catch (failure) { setError(getErrorMessage(failure, "保存失败")); }
    finally { setBusy(false); }
  }

  return <main className="mx-auto max-w-6xl space-y-5 p-4 md:p-8">
    <div className="flex flex-wrap justify-between gap-3"><h1 className="text-2xl font-semibold">座位分区图管理</h1><Link className="text-accent underline" to="/seat">打开用户页面</Link></div>
    <p className="text-sm leading-6 text-muted-foreground">导入整场 JSON，先保存草稿查看示意图，再核对并发布。新演出使用新编号，同一演出更新时保留分区编号。</p>
    <label className="block space-y-2 text-sm">载入已有演出<select disabled={busy} className="block w-full rounded-md border border-rule bg-paper p-3" defaultValue=""
      onChange={(event) => {
        const entry = catalog.find((item) => item.show.id === event.target.value);
        if (entry) { setEditor(JSON.stringify(entry, null, 2)); setPreview(entry); setReviewed(false); setError(""); }
      }}><option value="" disabled>选择演出作为编辑起点</option>{catalog.map((entry) => <option key={entry.show.id} value={entry.show.id}>{entry.venue.name} / {entry.show.name} / {entry.show.status}</option>)}</select></label>
    {error && <div role="alert" className="text-sm text-error">{error}<button className="ml-3 underline" onClick={() => setReload((value) => value + 1)}>重新加载列表</button></div>}
    <div className="grid items-start gap-6 lg:grid-cols-2">
      <div className="space-y-4"><label className="block space-y-2 text-sm">场馆与演出 JSON<Textarea disabled={busy} rows={24} value={editor}
        onChange={(event) => { setEditor(event.target.value); setReviewed(false); setPreview(null); }} spellCheck={false} className="font-mono text-xs" /></label>
        <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} disabled={busy} />
          我已核对来源、分区、票档和舞台；演示图将继续标注为演示。</label>
        <div className="flex flex-wrap gap-3"><Button disabled={busy || !editor} variant="outline" onClick={() => void save("DRAFT")}>保存草稿并预览</Button>
          <Button disabled={busy || !editor || !reviewed} onClick={() => void save("PUBLISHED")}>核对完成，发布</Button>
          <Button disabled={busy || !editor} variant="outline" onClick={() => void save("ARCHIVED")}>下架</Button></div>
      </div>
      <div className="space-y-3 rounded-xl border border-rule bg-paper p-4">
        {preview ? <><p className="text-sm font-semibold">{preview.show.name} / 已保存版本</p><p className="text-xs text-warning">{preview.show.provenance === "DEMO" ? "演示数据" : "人工校对的示意图"}</p>
          <SeatMap document={preview} selected={preview.show.sections[0]} depth={50} lateral={50} onSelect={() => undefined} />
          <p className="text-xs leading-6 text-muted-foreground">{preview.show.sourceNote}</p></> : <p className="text-sm text-muted-foreground">保存草稿通过几何校验后，在这里预览。</p>}
      </div>
    </div>
  </main>;
}
