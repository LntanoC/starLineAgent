import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { getSeatPhoto, listSeatPhotos, uploadSeatPhoto, type SeatPhoto } from "@/services/seatViewService";
import { getErrorMessage } from "@/utils/error";
import type { SeatViewDocument, SeatSection } from "@/lib/seatView";

function PhotoImage({ photo }: { photo: SeatPhoto }) {
  const [url, setUrl] = useState("");
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    let objectUrl = "";
    setFailed(false);
    getSeatPhoto(photo.id, controller.signal).then((blob) => {
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(blob);
      setUrl(objectUrl);
    }).catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [photo.id]);
  return failed ? <p role="alert" className="text-xs text-warning">图片加载失败</p> : url ?
    <img src={url} alt={photo.caption || "观众上传的现场照片"} className="aspect-[4/3] w-full rounded-lg object-cover" /> :
    <p className="text-xs text-muted-foreground">图片加载中…</p>;
}

export function SeatPhotos({ document, section }: { document: SeatViewDocument; section?: SeatSection }) {
  const [scope, setScope] = useState<"show" | "section" | "seat">("show");
  const [photos, setPhotos] = useState<SeatPhoto[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [page, setPage] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [caption, setCaption] = useState("");
  const [row, setRow] = useState("");
  const [seat, setSeat] = useState("");
  const [filterRow, setFilterRow] = useState("");
  const [filterSeat, setFilterSeat] = useState("");
  const sectionCode = section?.code;
  const validSeat = Number.isInteger(Number(filterRow)) && Number(filterRow) >= 1 && Number(filterRow) <= 500 &&
    Number.isInteger(Number(filterSeat)) && Number(filterSeat) >= 1 && Number(filterSeat) <= 1000;
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    if (scope !== "show" && !sectionCode || scope === "seat" && !validSeat) {
      setPhotos([]);
      setLoading(false);
      return () => controller.abort();
    }
    listSeatPhotos(document.show.id, scope === "show" ? undefined : sectionCode,
      scope === "seat" ? Number(filterRow) : undefined, scope === "seat" ? Number(filterSeat) : undefined, page, controller.signal)
      .then(setPhotos).catch((failure) => { if (!controller.signal.aborted) setError(getErrorMessage(failure, "照片加载失败")); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [document.show.id, scope, sectionCode, refresh, validSeat, filterRow, filterSeat, page]);

  useEffect(() => { setPage(0); }, [document.show.id, scope, sectionCode, filterRow, filterSeat]);

  async function upload() {
    if (!section || !file) return;
    const data = new FormData();
    data.set("showId", document.show.id);
    data.set("sectionCode", section.code);
    data.set("file", file);
    data.set("caption", caption);
    if (row) data.set("row", row);
    if (seat) data.set("seat", seat);
    setBusy(true);
    try {
      await uploadSeatPhoto(data);
      setFile(null);
      setCaption("");
      setRow("");
      setSeat("");
      setRefresh((value) => value + 1);
      setPage(0);
      toast.success("现场照片已上传");
    } catch (failure) { toast.error(getErrorMessage(failure, "上传失败")); }
    finally { setBusy(false); }
  }

  return <section className="space-y-4 border-t border-rule pt-4" aria-label="现场实拍">
    <h2 className="text-lg font-semibold">现场实拍</h2>
    <p className="text-xs text-muted-foreground">观众填写的位置未经核验，同区或同排也可能有不同视角。</p>
    <div className="flex flex-wrap gap-2">
      <Button size="sm" variant={scope === "show" ? "default" : "outline"} aria-pressed={scope === "show"} onClick={() => setScope("show")}>本场全部</Button>
      <Button size="sm" variant={scope === "section" ? "default" : "outline"} aria-pressed={scope === "section"} disabled={!section} onClick={() => setScope("section")}>当前分区</Button>
      <Button size="sm" variant={scope === "seat" ? "default" : "outline"} aria-pressed={scope === "seat"} disabled={!section} onClick={() => setScope("seat")}>指定排座</Button>
    </div>
    {scope === "seat" && <div className="flex gap-2"><label className="text-sm">筛选排<input className="block w-full rounded-md border border-rule bg-paper p-2" type="number" min="1" max="500" value={filterRow} onChange={(event) => setFilterRow(event.target.value)} /></label>
      <label className="text-sm">筛选座<input className="block w-full rounded-md border border-rule bg-paper p-2" type="number" min="1" max="1000" value={filterSeat} onChange={(event) => setFilterSeat(event.target.value)} /></label></div>}
    {scope === "seat" && !validSeat && <p className="text-xs text-muted-foreground">请输入排号与座号以筛选。</p>}
    {loading ? <p role="status">正在加载照片…</p> : error ? <p role="alert" className="text-warning">{error}</p> : photos.length === 0 ?
      <p className="text-sm text-muted-foreground">暂无照片。</p> : <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{photos.map((photo) =>
        <article key={photo.id} className="space-y-2 rounded-lg border border-rule p-3">
          <PhotoImage photo={photo} />
          <p className="text-sm">{document.show.sections.find((item) => item.code === photo.sectionCode)?.label ?? photo.sectionCode}
            {photo.row != null ? ` · ${photo.row}排` : ""}{photo.seat != null ? ` · ${photo.seat}座` : ""}</p>
          {photo.caption && <p className="break-words text-sm">{photo.caption}</p>}
          <p className="text-xs text-muted-foreground">{photo.owned ? "我的上传 · " : ""}{new Date(photo.createdAt).toLocaleDateString("zh-CN")}</p>
        </article>)}</div>}
    {validSeat || scope !== "seat" ? <nav className="flex items-center gap-3 text-sm" aria-label="照片分页">
      <Button size="sm" variant="outline" disabled={loading || page === 0} onClick={() => setPage((value) => value - 1)}>上一页</Button>
      <span>第 {page + 1} 页</span>
      <Button size="sm" variant="outline" disabled={loading || photos.length < 30} onClick={() => setPage((value) => value + 1)}>下一页</Button>
    </nav> : null}
    <details className="space-y-3"><summary className="cursor-pointer text-sm font-semibold">上传现场照片</summary>
      <p className="text-xs text-muted-foreground">上传至当前场次和分区，支持 5 MB 以内的 JPEG 或 PNG。</p>
      <label className="block text-sm">照片<input key={refresh} className="block w-full py-2" type="file" accept="image/jpeg,image/png" onChange={(event) => setFile(event.target.files?.[0] ?? null)} /></label>
      <label className="block text-sm">说明<input className="block w-full rounded-md border border-rule bg-paper p-2" maxLength={500} value={caption} onChange={(event) => setCaption(event.target.value)} /></label>
      <div className="flex gap-2"><label className="text-sm">排（选填）<input className="block w-full rounded-md border border-rule bg-paper p-2" type="number" min="1" max="500" value={row} onChange={(event) => setRow(event.target.value)} /></label>
        <label className="text-sm">座（选填）<input className="block w-full rounded-md border border-rule bg-paper p-2" type="number" min="1" max="1000" value={seat} onChange={(event) => setSeat(event.target.value)} /></label></div>
      <Button disabled={!file || !section || busy} onClick={() => void upload()}>{busy ? "上传中…" : "上传照片"}</Button>
    </details>
  </section>;
}
