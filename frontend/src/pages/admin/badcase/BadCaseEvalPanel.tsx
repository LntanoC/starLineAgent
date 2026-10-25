import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { getEvalSamples,saveEvalSample,approveEvalSample,exportEvalSamples,getEvalManifest,downloadJson,type BadCaseRecord,type EvalSample } from "@/services/badCaseService";
export function BadCaseEvalPanel({item}:{item?:BadCaseRecord}){
 const [samples,setSamples]=useState<EvalSample[]>([]),[draft,setDraft]=useState(""),[dataset,setDataset]=useState("bad-case-regression"),[lane,setLane]=useState("CONVERSATION"),[busy,setBusy]=useState(false);
 const load=async()=>setSamples(await getEvalSamples(item?.id));
 useEffect(()=>{let active=true;getEvalSamples(item?.id).then(v=>{if(active)setSamples(v);}).catch(e=>toast.error((e as Error).message));return()=>{active=false;};},[item?.id]);
 const execute=async(fn:()=>Promise<unknown>)=>{setBusy(true);try{await fn();await load();}catch(e){toast.error((e as Error).message);}finally{setBusy(false);}};
 const edit=(s:EvalSample)=>setDraft(JSON.stringify({id:s.id,caseId:s.case_id,datasetName:s.dataset_name,tags:s.tags,description:s.description,turns:s.turns,testLane:s.test_lane,revision:s.revision},null,2));
 return <section className="space-y-3 rounded-xl border p-4"><h3 className="font-semibold">评测与回归</h3><p className="text-xs text-slate-500">样本须先人工确认根因、编辑断言并批准。会话 JSON 与业务测试分通道；只有全部 scored repetition 通过才算通过。</p>
  {samples.map(s=><div key={s.id} className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-slate-50 p-3 text-xs"><div><b>{s.case_id}</b> · {s.test_lane} · {s.status}<p className="mt-1">最近回归：{s.last_result||"未运行"} · 复发 {s.recurrence_count} 次 · {s.last_run_id||""}</p></div><div className="flex gap-2">{item&&<Button size="sm" variant="outline" onClick={()=>edit(s)}>编辑草稿</Button>}{s.status==="DRAFT"&&<Button size="sm" disabled={busy} onClick={()=>void execute(()=>approveEvalSample(s.id,s.revision))}>人工批准</Button>}</div></div>)}
  {!samples.length&&<p className="text-sm text-slate-400">暂无评测样本。系统不会自动把信号认定为已确认样本。</p>}
  {item&&["REVIEWED","FIXED"].includes(item.reviewStatus)&&<><Button variant="outline" onClick={()=>setDraft(JSON.stringify({caseId:`bc-${item.id}`,datasetName:dataset,tags:["bad-case"],description:"请填写人工确认的稳定预期",testLane:lane,turns:[{id:"t01",question:"请填写脱敏、可复现的问题",phase:"measure",expectAll:[]}]},null,2))}>新建样本草稿</Button>{draft&&<><textarea aria-label="评测样本草稿 JSON" className="min-h-64 w-full rounded-lg border p-3 font-mono text-xs" value={draft} onChange={e=>setDraft(e.target.value)}/><Button disabled={busy} onClick={()=>void execute(async()=>{await saveEvalSample(item.id,JSON.parse(draft));setDraft("");toast.success("草稿已保存，需另行人工批准");})}>保存草稿</Button></>}</>}
  <div className="flex flex-wrap items-center gap-2"><input aria-label="数据集名称" className="rounded-lg border p-2 text-sm" value={dataset} onChange={e=>setDataset(e.target.value)}/><select aria-label="回归通道" className="rounded-lg border p-2 text-sm" value={lane} onChange={e=>setLane(e.target.value)}>{["CONVERSATION","SERVICE_TEST","INTEGRATION_TEST"].map(v=><option key={v}>{v}</option>)}</select><Button variant="outline" disabled={busy} onClick={()=>void execute(async()=>{const rows=await exportEvalSamples(dataset,lane);downloadJson(rows,`${dataset}-${lane}.json`);downloadJson(await getEvalManifest(dataset),`${dataset}-manifest.json`);})}>导出已批准样本与指纹</Button></div>
  <p className="text-xs text-slate-500">结果通过服务器本机或 SSH 隧道的 API 导入，使用项目提供的 prepare_bad_case_import.py 转换运行产物。公网页面不接收结果写入。</p>
 </section>;
}
