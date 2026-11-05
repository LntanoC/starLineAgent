import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Copy, ExternalLink, MapPin, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { AgentLayout } from "@/components/agent/AgentLayout";
import { SeatMap, priceColor } from "@/components/agent/SeatMap";
import { Button } from "@/components/ui/button";
import { useAuthStore } from "@/stores/authStore";
import { listSeatViews } from "@/services/seatViewService";
import { percentage, resolveDocument, seatSearch, type SeatViewDocument } from "@/lib/seatView";
import { getErrorMessage } from "@/utils/error";

export function SeatViewPage() {
  const [params, setParams] = useSearchParams();
  const [catalog, setCatalog] = useState<SeatViewDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const isAdmin = useAuthStore((state) => state.user?.role === "admin");
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    listSeatViews(false, controller.signal).then(setCatalog).catch((failure) => {
      if (!controller.signal.aborted) setError(getErrorMessage(failure, "分区图加载失败"));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [reload]);
  const document = resolveDocument(catalog, params);
  const sectionCode = params.get("section");
  const selected = sectionCode ? document?.show.sections.find((section) => section.code === sectionCode) : document?.show.sections[0];
  const depth = percentage(params.get("depth"));
  const lateral = percentage(params.get("lateral"));
  const venues = [...new Map(catalog.map((entry) => [entry.venue.id, entry.venue])).values()];

  async function share() {
    if (!document || !selected) return;
    setParams(seatSearch(document, selected.code, depth, lateral), { replace: true });
    const url = new URL(window.location.href);
    url.search = seatSearch(document, selected.code, depth, lateral).toString();
    try {
      if (navigator.clipboard?.writeText && window.isSecureContext) {
        await navigator.clipboard.writeText(url.href);
      } else {
        // 沿用日历订阅页的复制兼容路径，支持当前 HTTP 部署。
        const input = window.document.createElement("textarea");
        input.value = url.href;
        input.setAttribute("readonly", "");
        input.style.position = "fixed";
        input.style.opacity = "0";
        window.document.body.appendChild(input);
        try {
          input.select();
          if (!window.document.execCommand("copy")) throw new Error("copy failed");
        } finally { input.remove(); }
      }
      toast.success("已复制位置链接");
    } catch { toast.error("复制失败，请复制浏览器地址栏中的链接"); }
  }

  return <AgentLayout><div className="mx-auto w-full max-w-6xl space-y-6 overflow-y-auto p-4 md:p-8">
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div><h1 className="flex items-center gap-2 text-2xl font-semibold text-ink"><MapPin className="h-6 w-6" />座位方位</h1>
        <p className="mt-2 text-sm text-muted-foreground">选一个分区，看看你和舞台的大致位置。</p></div>
      {isAdmin && <Link className="text-sm text-accent underline" to="/admin/seat-views">管理分区图</Link>}
    </header>
    {loading ? <p role="status">正在加载分区图…</p> : error ? <div role="alert" className="space-y-3 text-error">
      <p>{error}</p><Button variant="outline" onClick={() => setReload((value) => value + 1)}><RefreshCw className="mr-2 h-4 w-4" />重试</Button>
    </div> : catalog.length === 0 ? <p className="text-muted-foreground">还没有已发布的分区图，校对完成后会在这里展示。</p> : <>
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="space-y-2 text-sm">场馆<select className="block w-full rounded-md border border-rule bg-paper p-3"
          value={document?.venue.id ?? ""} onChange={(event) => {
            const next = catalog.find((entry) => entry.venue.id === event.target.value)!;
            setParams(seatSearch(next, next.show.sections[0].code, 50, 50));
          }}><option value="" disabled>请选择场馆</option>{venues.map((venue) => <option key={venue.id} value={venue.id}>{venue.city} / {venue.name}</option>)}</select></label>
        <label className="space-y-2 text-sm">演出场次<select className="block w-full rounded-md border border-rule bg-paper p-3"
          value={document?.show.id ?? ""} onChange={(event) => {
            const next = catalog.find((entry) => entry.show.id === event.target.value)!;
            setParams(seatSearch(next, next.show.sections[0].code, 50, 50));
          }}><option value="" disabled>请选择场次</option>{catalog.filter((entry) => !document || entry.venue.id === document.venue.id).map((entry) =>
            <option key={entry.show.id} value={entry.show.id}>{entry.show.name}{entry.show.startsAt ? " / " + new Date(entry.show.startsAt).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) : ""}</option>)}</select></label>
      </div>
      {!document ? <p role="alert" className="rounded-lg border border-warning-rule bg-warning-wash p-4 text-warning">链接中的场馆或演出尚未收录，或已下架。请选择一场已发布的演出。</p> : <>
        <div className="rounded-lg border border-warning-rule bg-warning-wash p-3 text-sm leading-6 text-warning">
          {document.show.provenance === "DEMO" ? "演示数据：分区、票价和舞台位置均为示意，不对应真实演出。" : "已人工校对的分区示意图，以主办方票务图和现场安排为准。"}
          <span className="block">仅供判断方位；票档不代表余票、售罄状态或实际视线。</span>
        </div>
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
          <div className="rounded-xl border border-rule bg-paper p-2 md:p-5">
            <SeatMap document={document} selected={selected} depth={depth} lateral={lateral}
              onSelect={(section) => setParams(seatSearch(document, section.code, depth, lateral))} />
            <div className="flex flex-wrap justify-center gap-x-4 gap-y-2 py-3 text-xs text-muted-foreground">
              {[...new Set(document.show.sections.map((section) => section.priceTier))].sort((a, b) => (b ?? -1) - (a ?? -1)).map((price) => {
                const section = document.show.sections.find((entry) => entry.priceTier === price)!;
                return <span key={price ?? "unknown"} className="flex items-center gap-1.5"><span className="h-3 w-3 rounded-sm" style={{ background: priceColor(document, section) }} />{price == null ? "票档待确认" : "¥" + price}</span>;
              })}
            </div>
          </div>
          <aside className="space-y-6">
            <label className="block space-y-2 text-sm">选择分区<select className="block w-full rounded-md border border-rule bg-paper p-3"
              value={selected?.code ?? ""} onChange={(event) => setParams(seatSearch(document, event.target.value, depth, lateral))}>
              <option value="" disabled>请选择分区</option>{document.show.sections.map((section) => <option key={section.code} value={section.code}>{section.label}</option>)}
            </select></label>
            {!selected ? <p role="alert" className="text-sm text-warning">链接中的分区不存在，请重新选择。</p> : <>
              <div><h2 className="text-lg font-semibold">{selected.label}</h2><p className="mt-2 text-2xl font-semibold text-accent">{selected.priceTier == null ? "票档待确认" : "¥" + selected.priceTier}</p>
                <p className="mt-1 text-xs text-muted-foreground">{["", "内场", "下层看台", "上层看台"][selected.level]}{document.show.provenance === "DEMO" ? " / 演示票档" : " / 票档参考"}</p></div>
              <label className="block space-y-2 text-sm">分区前后位置 <output>{depth}%</output><input className="block w-full accent-accent" type="range" min="0" max="100" value={depth}
                onChange={(event) => setParams(seatSearch(document, selected.code, Number(event.target.value), lateral), { replace: true })} />
                <span className="flex justify-between text-xs text-muted-foreground"><span>朝舞台前沿</span><span>分区后沿</span></span></label>
              <label className="block space-y-2 text-sm">分区横向位置 <output>{lateral}%</output><input className="block w-full accent-accent" type="range" min="0" max="100" value={lateral}
                onChange={(event) => setParams(seatSearch(document, selected.code, depth, Number(event.target.value)), { replace: true })} />
                <span className="flex justify-between text-xs text-muted-foreground"><span>前沿起点一侧</span><span>前沿终点一侧</span></span></label>
              <Button className="w-full" onClick={() => void share()}><Copy className="mr-2 h-4 w-4" />分享这个位置</Button>
            </>}
            <p className="text-xs leading-6 text-muted-foreground">滑块表示分区内的比例，不对应具体排号和座号。虚线只表示舞台方向。</p>
          </aside>
        </div>
        <footer className="space-y-1 border-t border-rule pt-4 text-xs leading-6 text-muted-foreground">
          <p>{document.show.sourceNote}</p><a className="inline-flex items-center gap-1 text-accent underline" href={document.show.sourceUrl} target="_blank" rel="noreferrer">查看数据来源<ExternalLink className="h-3 w-3" /></a>
          {document.show.provenance === "DEMO" && <a className="ml-4 text-accent underline" href="/third-party/venue-seats-LICENSE.txt" target="_blank" rel="noreferrer">MIT 许可</a>}
        </footer>
      </>}
    </>}
  </div></AgentLayout>;
}
