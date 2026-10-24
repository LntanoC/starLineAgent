import { useCallback, useEffect, useRef, useState } from "react";
import { RefreshCw, ScanSearch } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { getBadCase, getBadCases, getBadCaseStats, scanBadCases, ROOT_CAUSES, REVIEW_STATUSES,
  type BadCasePageData, type BadCaseRecord, type BadCaseStats } from "@/services/badCaseService";
import { BadCaseDetailDialog } from "./BadCaseDetailDialog";

export function BadCasePage() {
  const [status, setStatus] = useState("PENDING");
  const [cause, setCause] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<BadCasePageData | null>(null);
  const [stats, setStats] = useState<BadCaseStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [selected, setSelected] = useState<BadCaseRecord | null>(null);
  const [open, setOpen] = useState(false);
  const generation = useRef(0);
  const detailGeneration = useRef(0);
  const load = useCallback(async () => {
    const request = ++generation.current;
    setLoading(true);
    try {
      const [rows, overview] = await Promise.all([getBadCases(page, status, cause), getBadCaseStats()]);
      if (request !== generation.current) return;
      setData(rows); setStats(overview);
    } catch (error) { if (request === generation.current) toast.error((error as Error).message || "加载失败"); }
    finally { if (request === generation.current) setLoading(false); }
  }, [page, status, cause]);
  useEffect(() => { void load(); }, [load]);
  const showDetail = async (id: string) => {
    const request = ++detailGeneration.current;
    setSelected(null); setOpen(true);
    try { const item = await getBadCase(id); if (request === detailGeneration.current) setSelected(item); }
    catch (error) { if (request === detailGeneration.current) { setOpen(false); toast.error((error as Error).message); } }
  };
  const refreshSelected = async () => {
    if (selected) setSelected(await getBadCase(selected.id));
    await load();
  };
  const scan = async () => {
    setScanning(true);
    try {
      const result = await scanBadCases();
      toast.success(result.busy ? "已有扫描任务正在执行" : `本批扫描 ${result.scanned} 条，新增 ${result.created} 条，失败 ${result.failed} 条`);
      await load();
    } catch (error) { toast.error((error as Error).message || "扫描失败"); }
    finally { setScanning(false); }
  };
  return <div className="space-y-6">
    <div className="flex flex-wrap items-start justify-between gap-4"><div><h1 className="text-2xl font-semibold text-slate-900">Bad case 分析</h1><p className="mt-2 text-sm text-slate-500">点踩 → 现场快照 → 自动线索 → 人工判定 → 修复与回归记录</p></div>
      <div className="flex gap-2"><Button variant="outline" disabled={loading} onClick={() => load()}><RefreshCw className="mr-2 h-4 w-4" />刷新</Button><Button disabled={scanning} onClick={scan}><ScanSearch className="mr-2 h-4 w-4" />{scanning ? "扫描中…" : "补扫历史反馈"}</Button></div>
    </div>
    <div className="grid grid-cols-2 gap-3 md:grid-cols-5">{[["全部", stats?.total ?? 0], ...Object.entries(REVIEW_STATUSES).map(([value, label]) => [label, stats?.byStatus.find((s) => s.value === value)?.count ?? 0])].map(([label, count]) => <div key={String(label)} className="rounded-2xl border bg-white p-4"><p className="text-xs text-slate-500">{label}</p><p className="mt-2 text-2xl font-semibold">{count}</p></div>)}</div>
    <div className="rounded-2xl border bg-white p-4"><p className="mb-3 text-sm font-medium">人工根因分布</p><div className="flex flex-wrap gap-2">{stats?.byRootCause.length ? stats.byRootCause.map((s) => <span key={s.value} className="rounded-full bg-slate-100 px-3 py-1 text-xs">{ROOT_CAUSES[s.value] || "未判定"} · {s.count}</span>) : <span className="text-sm text-slate-400">暂无数据</span>}</div></div>
    <div className="flex flex-wrap gap-3">
      <select aria-label="按判定状态筛选" className="rounded-lg border bg-white px-3 py-2 text-sm" value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}><option value="">全部状态</option>{Object.entries(REVIEW_STATUSES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <select aria-label="按根因筛选" className="rounded-lg border bg-white px-3 py-2 text-sm" value={cause} onChange={(e) => { setCause(e.target.value); setPage(1); }}><option value="">全部根因</option>{Object.entries(ROOT_CAUSES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <p className="self-center text-xs text-slate-500">每 5 分钟自动扫描；补扫每批最多 50 条，未处理的旧反馈可继续补扫。</p>
    </div>
    <div className="overflow-hidden rounded-2xl border bg-white"><Table><TableHeader><TableRow><TableHead>时间 / 类型</TableHead><TableHead>用户问题 / 助手回答</TableHead><TableHead>反馈原因</TableHead><TableHead>自动线索</TableHead><TableHead>人工根因 / 状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
      {(data?.records || []).map((item) => <TableRow key={item.id}><TableCell className="whitespace-nowrap text-xs">{new Date(item.createTime).toLocaleString("zh-CN")}<p className="mt-1 text-slate-400">{item.report.messageType}</p></TableCell>
        <TableCell className="max-w-xs"><p className="line-clamp-2 text-sm">{item.report.userQuestion || "问题缺失"}</p><p className="mt-1 line-clamp-2 text-xs text-slate-500">{item.report.assistantAnswer || "回答缺失"}</p></TableCell>
        <TableCell className="max-w-32 text-xs">{item.report.reason || "未填写"}</TableCell><TableCell className="max-w-xs text-xs"><p className="line-clamp-3">{item.report.suspicions?.join("；")}</p></TableCell>
        <TableCell className="whitespace-nowrap text-xs">{ROOT_CAUSES[item.rootCause || ""] || "未判定"}<p className="mt-1 text-indigo-600">{REVIEW_STATUSES[item.reviewStatus] || item.reviewStatus}</p></TableCell>
        <TableCell><Button size="sm" variant="outline" onClick={() => showDetail(item.id)}>查看 / 判定</Button></TableCell></TableRow>)}
      {!data?.records.length && <TableRow><TableCell colSpan={6} className="py-16 text-center text-slate-400">{loading ? "加载中…" : "暂无符合条件的记录，可调整筛选或补扫历史反馈"}</TableCell></TableRow>}
    </TableBody></Table></div>
    <div className="flex items-center justify-between text-sm text-slate-500"><span>共 {data?.total ?? 0} 条</span><div className="flex items-center gap-3"><Button variant="outline" disabled={loading || page <= 1} onClick={() => setPage((v) => v - 1)}>上一页</Button><span>{page} / {Math.max(data?.pages || 0, 1)}</span><Button variant="outline" disabled={loading || !data || page >= data.pages} onClick={() => setPage((v) => v + 1)}>下一页</Button></div></div>
    <BadCaseDetailDialog item={selected} open={open} onOpenChange={(value) => { setOpen(value); if (!value) detailGeneration.current++; }} onSaved={refreshSelected} />
  </div>;
}
