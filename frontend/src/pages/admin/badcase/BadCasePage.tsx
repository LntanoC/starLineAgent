import { useCallback, useEffect, useRef, useState } from "react";
import { RefreshCw, ScanSearch } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { getBadCase, getBadCases, getBadCaseStats, scanDetector, ROOT_CAUSES, REVIEW_STATUSES, SOURCES, TRIAGE, type BadCasePageData, type BadCaseRecord, type BadCaseStats } from "@/services/badCaseService";
import { BadCaseDetailDialog } from "./BadCaseDetailDialog";
import { BadCaseEvalPanel } from "./BadCaseEvalPanel";
const views:Record<string,string>={human:"人工待办",low:"低置信候选",sample:"抽样评审",measurement:"测量抽查",history:"全部记录",eval:"评测样本"};
const selectClass="rounded-lg border bg-white px-3 py-2 text-sm";
export function BadCasePage(){
 const [view,setView]=useState("human"),[status,setStatus]=useState("PENDING"),[cause,setCause]=useState(""),[source,setSource]=useState(""),[severity,setSeverity]=useState(""),[triage,setTriage]=useState("");
 const [detector,setDetector]=useState(""),[page,setPage]=useState(1),[data,setData]=useState<BadCasePageData|null>(null),[stats,setStats]=useState<BadCaseStats|null>(null);
 const [loading,setLoading]=useState(false),[scanning,setScanning]=useState(false),[selected,setSelected]=useState<BadCaseRecord|null>(null),[open,setOpen]=useState(false);
 const generation=useRef(0),detailGeneration=useRef(0);
 const load=useCallback(async()=>{const request=++generation.current;setLoading(true);try{
  const [rows,overview]=await Promise.all([getBadCases(page,status,cause,{confidence:view==="low"?"LOW":undefined,triageStatus:triage||(view==="human"?"READY_FOR_HUMAN":undefined),source:source||(view==="sample"?"UNVOTED_SAMPLE":undefined),severity:severity||undefined,measurement:view==="measurement"}),getBadCaseStats()]);
  if(request!==generation.current)return;setData(rows);setStats(overview);
 }catch(e){if(request===generation.current)toast.error((e as Error).message);}finally{if(request===generation.current)setLoading(false);}},[page,status,cause,view,source,severity,triage]);
 useEffect(()=>{void load();},[load]);
 const show=async(id:string)=>{const request=++detailGeneration.current;setSelected(null);setOpen(true);try{const item=await getBadCase(id);if(request===detailGeneration.current)setSelected(item);}catch(e){if(request===detailGeneration.current){setOpen(false);toast.error((e as Error).message);}}};
 const saved=async()=>{if(selected)setSelected(await getBadCase(selected.id));await load();};
 const scan=async(mode:string)=>{setScanning(true);try{const result=await scanDetector(detector,mode);toast.success(JSON.stringify(result));await load();}catch(e){toast.error((e as Error).message);}finally{setScanning(false);}};
 return <div className="space-y-5">
  <div className="flex flex-wrap justify-between gap-4"><div><h1 className="text-2xl font-semibold">Bad Case 统一候选池</h1><p className="mt-2 text-sm text-slate-500">信号检测 → 机器分诊 → 人工核查 → 修复与回归。高置信信号仍需人工确认根因。</p></div><Button variant="outline" disabled={loading} onClick={()=>void load()}><RefreshCw className="mr-2 h-4 w-4"/>刷新</Button></div>
  <div className="grid grid-cols-2 gap-3 md:grid-cols-4">{[["全部候选",stats?.total||0],["高置信人工待办",stats?.highPending||0],["低置信观察 / 待评审",stats?.lowPending||0],["今日有效信号",stats?.todaySignals||0]].map(([label,count])=><div key={String(label)} className="rounded-xl border bg-white p-4"><p className="text-xs text-slate-500">{label}</p><p className="mt-2 text-2xl font-semibold">{count}</p></div>)}</div>
  <div className="flex flex-wrap gap-2">{Object.entries(views).map(([key,label])=><Button key={key} variant={view===key?"default":"outline"} onClick={()=>{setView(key);setPage(1);setStatus(key==="history"||key==="measurement"?"":"PENDING");setTriage("");setSource("");}}>{label}</Button>)}</div>
  {view==="eval"?<BadCaseEvalPanel/>:<>
   {view==="measurement"&&<p className="rounded-xl bg-blue-50 p-3 text-sm text-blue-900">测量样本需逐条人工检查，包括机器判正常或已抑制的样本；此队列独立于默认待办。</p>}
   <div className="flex flex-wrap gap-3">
    <select aria-label="判定状态" className={selectClass} value={status} onChange={e=>{setStatus(e.target.value);setPage(1);}}><option value="">全部人工状态</option>{Object.entries(REVIEW_STATUSES).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select>
    <select aria-label="根因筛选" className={selectClass} value={cause} onChange={e=>{setCause(e.target.value);setPage(1);}}><option value="">全部根因</option>{Object.entries(ROOT_CAUSES).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select>
    <select aria-label="来源筛选" className={selectClass} value={source} onChange={e=>{setSource(e.target.value);setPage(1);}}><option value="">{view==="sample"?"抽样来源":"全部来源"}</option>{Object.entries(SOURCES).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select>
    <select aria-label="严重度" className={selectClass} value={severity} onChange={e=>{setSeverity(e.target.value);setPage(1);}}><option value="">全部严重度</option>{["S1","S2","S3","S4"].map(x=><option key={x}>{x}</option>)}</select>
    <select aria-label="分诊状态" className={selectClass} value={triage} onChange={e=>{setTriage(e.target.value);setPage(1);}}><option value="">{view==="human"?"待人工核查":"全部分诊状态"}</option>{Object.entries(TRIAGE).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select>
   </div>
   <div className="flex flex-wrap items-center gap-2 rounded-xl border bg-white p-3"><select aria-label="扫描入口" className={selectClass} value={detector} onChange={e=>setDetector(e.target.value)}><option value="">所有已启用入口</option>{[["user-feedback","点踩"],["agent-runtime","执行异常"],["user-correction","用户纠正"],["behavior","行为"],["sampling","抽样"]].map(([k,v])=><option key={k} value={k}>{v}</option>)}</select><Button variant="outline" disabled={scanning} onClick={()=>void scan("dryRun")}><ScanSearch className="mr-2 h-4 w-4"/>试扫</Button><Button disabled={scanning} onClick={()=>void scan("apply")}>执行扫描</Button><span className="text-xs text-slate-500">扫描遵循服务器 OFF / SHADOW / ON 配置；SHADOW 不写候选。</span></div>
   <div className="overflow-hidden rounded-xl border bg-white"><Table><TableHeader><TableRow>{["最近发生 / 主体","候选内容","来源 / 信号数","置信度 / 严重度","分诊 / 人工状态","操作"].map(x=><TableHead key={x}>{x}</TableHead>)}</TableRow></TableHeader><TableBody>{data?.records.map(item=><TableRow key={item.id}>
    <TableCell className="text-xs">{new Date(item.candidate.last_detected_at||item.createTime).toLocaleString("zh-CN")}<p className="mt-1 text-slate-400">{item.candidate.subject_type}</p></TableCell>
    <TableCell className="max-w-xs"><p className="line-clamp-2 text-sm">{item.report.userQuestion||item.candidate.title}</p>{item.report.assistantAnswer&&<p className="mt-1 line-clamp-2 text-xs text-slate-500">{item.report.assistantAnswer}</p>}</TableCell>
    <TableCell className="text-xs">{SOURCES[item.candidate.primary_source]||item.candidate.primary_source}<p className="mt-1">{item.candidate.signal_count} 条有效信号</p></TableCell>
    <TableCell className="text-xs"><span className={item.candidate.confidence==="HIGH"?"text-amber-700":"text-slate-500"}>{item.candidate.confidence==="HIGH"?"高置信":"低置信"}</span> · {item.candidate.severity}</TableCell>
    <TableCell className="text-xs">{TRIAGE[item.candidate.triage_status]}<p className="mt-1 text-indigo-600">{REVIEW_STATUSES[item.reviewStatus]}</p>{item.candidate.confidence==="LOW"&&item.candidate.triage_status==="READY_FOR_HUMAN"&&<p>低置信已提升</p>}</TableCell>
    <TableCell><Button size="sm" variant="outline" onClick={()=>void show(item.id)}>查看 / 核查</Button></TableCell></TableRow>)}{!data?.records.length&&<TableRow><TableCell colSpan={6} className="py-16 text-center text-slate-400">{loading?"加载中…":"暂无符合条件的候选"}</TableCell></TableRow>}</TableBody></Table></div>
   <div className="flex justify-between text-sm text-slate-500"><span>共 {data?.total||0} 条</span><div className="flex items-center gap-3"><Button variant="outline" disabled={loading||page<=1} onClick={()=>setPage(p=>p-1)}>上一页</Button>{page} / {Math.max(1,data?.pages||0)}<Button variant="outline" disabled={loading||!data||page>=data.pages} onClick={()=>setPage(p=>p+1)}>下一页</Button></div></div>
  </>}
  <BadCaseDetailDialog item={selected} open={open} onOpenChange={v=>{setOpen(v);if(!v)detailGeneration.current++;}} onSaved={saved}/>
 </div>;
}
