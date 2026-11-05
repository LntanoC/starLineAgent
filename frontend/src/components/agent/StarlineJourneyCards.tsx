import { useState } from "react";
import { AlertTriangle, ArrowRight, Clock3 } from "lucide-react";
import { moneyRange, selectJourney, type JourneyPlan, type JourneyOption } from "@/lib/journey";

const feasible = { FEASIBLE: "来得及（仍需预留误差）", TIGHT: "时间偏紧", INFEASIBLE: "赶不上 / 来不及", UNKNOWN: "无法确认是否赶得上" };
const status = { QUOTED: "已报价", REFERENCE: "参考", ESTIMATED: "估算", UNKNOWN: "待核实" };
const modes: Record<string, string> = { rail: "铁路", metro: "地铁", bus: "公交", transit: "公共交通", driving: "自驾", taxi: "打车", walk: "步行", station_buffer: "进站缓冲", transfer: "换乘", wait: "候车" };
const when = (value: string | null) => value ? value.replace("T", " ").slice(0, 16) : "待核实";
function Source({ value }: { value: string | null }) {
  if (!value) return <span>来源待核实</span>;
  try {
    const url = new URL(value);
    if (["https:", "http:"].includes(url.protocol) && !url.username && !url.password)
      return <a className="text-accent underline" href={url.href} target="_blank" rel="noreferrer">查看来源</a>;
  } catch { /* 用户和公式来源以文本展示，不作为链接。 */ }
  return <span className="break-all">{value}</span>;
}
function Timeline({ segments }: { segments: JourneyOption["segments"] }) {
  return <ol className="space-y-3 border-l border-rule pl-4">{segments.map((segment, index) => <li key={index} className="space-y-1 text-sm">
    <p className="text-xs text-muted-foreground">{when(segment.departAt)} <ArrowRight className="inline h-3 w-3" /> {when(segment.arriveAt)}（北京时间）</p>
    <p>{modes[segment.mode] ?? segment.mode}{segment.serviceNo ? " / " + segment.serviceNo : ""}：{segment.from.name} → {segment.to.name}</p>
    <p className="text-xs text-muted-foreground">{segment.durationMinutes} 分钟 / {segment.scheduled ? "来源班次时刻" : "路线估算或已标明缓冲"}</p>
  </li>)}</ol>;
}

export function StarlineJourneyCards({ plan }: { plan: JourneyPlan }) {
  const [id, setId] = useState<string | null>(plan.recommendedOptionId);
  const option = plan.options.find((item) => item.id === id) ?? plan.options.find((item) => item.id === plan.recommendedOptionId) ?? plan.options[0];
  const [notice, setNotice] = useState("");
  const choose = (kind: "fastest" | "cheapest" | "fewest") => {
    const result = selectJourney(plan.options, kind);
    if (result) { setId(result.id); setNotice(""); }
    else setNotice(kind === "cheapest" ? "费用或可行性尚有缺口，无法判断最省方案。" : "当前没有已确认可行的方案，请先核实待确认项。");
  };
  return <section aria-label="出行规划" className="space-y-4 rounded-xl border border-rule bg-paper p-4">
    <header className="space-y-1"><h3 className="font-semibold">{plan.event ? plan.event.tour || plan.event.artist + " / " + plan.event.city + "场观演出行" : "出行方案"}</h3>
      {plan.event && <p className="text-sm text-muted-foreground">{plan.event.venue?.name ?? "场馆待核实"} / 开场 {when(plan.event.showStart)}（北京时间）</p>}
      {plan.event && <div className="flex flex-wrap gap-2 text-xs text-muted-foreground">{plan.event.evidence.map((item, index) => <span key={index} className="rounded border border-rule px-2 py-1">
        {item.provider === "user" ? "用户提供" : "官方来源摘录"}{item.url && <> / <Source value={item.url} /></>}</span>)}</div>}
      <p className="text-xs text-muted-foreground">{plan.request?.partySize ?? 1} 人同行 / {plan.status === "PARTIAL" ? "部分信息待核实" : plan.status === "NEEDS_CLARIFICATION" ? "请补充必要信息" : plan.status === "FAILED" ? "本次未生成可用路线" : "查询完成"}</p>
    </header>
    {plan.questions.map((question) => <div key={question.field} className="space-y-1 border-l-2 border-accent pl-3 text-sm"><p>{question.question}</p>
      <ul className="list-inside list-disc text-xs leading-6 text-muted-foreground">{question.options.map((item, index) => <li key={index}>{item}</li>)}</ul></div>)}
    {plan.warnings.length > 0 && <ul className="space-y-1 text-sm text-warning">{plan.warnings.map((warning, index) => <li key={index}><AlertTriangle className="mr-1 inline h-3.5 w-3.5" />{warning.message}</li>)}</ul>}
    {option && <>
      <nav aria-label="方案偏好" className="flex flex-wrap gap-2">{[
        ["推荐", () => { setId(plan.recommendedOptionId); setNotice(plan.recommendedOptionId ? "" : "尚无全程可行性已确认的推荐，请核实费用和返程。"); }],
        ["最快", () => choose("fastest")], ["最省", () => choose("cheapest")], ["少换乘", () => choose("fewest")]
      ].map(([label, action]) => <button key={label as string} className="rounded-md border border-rule px-3 py-1.5 text-xs hover:bg-accent-wash" type="button" onClick={action as () => void}>{label as string}</button>)}</nav>
      {notice && <p role="status" className="text-sm text-warning">{notice}</p>}
      <div className="flex flex-wrap gap-2" role="group" aria-label="可选方案">{plan.options.map((item) => <button type="button" key={item.id} aria-pressed={item.id === option.id}
        className={"rounded-lg border px-3 py-2 text-sm " + (item.id === option.id ? "border-accent bg-accent-wash text-accent" : "border-rule")}
        onClick={() => { setId(item.id); setNotice(""); }}>{item.label} / {item.travelMinutes} 分钟</button>)}</div>
      <div className="grid gap-3 border-y border-rule py-3 sm:grid-cols-2">
        <div><p className={"text-sm font-semibold " + (option.feasibility === "INFEASIBLE" ? "text-error" : "text-warning")}>{feasible[option.feasibility]}</p>
          <p className="mt-1 text-sm"><Clock3 className="mr-1 inline h-4 w-4" />路上 {option.travelMinutes} 分钟{plan.event ? option.totalMinutes < 0 ? " / 计划出发已晚于开场" : " / 出发至开场 " + option.totalMinutes + " 分钟" : ""}</p>
          <p className="text-xs text-muted-foreground">出发 {when(option.departOriginAt)} / 到达 {when(option.arriveVenueAt)}（北京时间）</p></div>
        <div><p className="text-sm font-semibold">{option.cost.unknownCategories.length ? "已知部分" : "费用参考合计"}：{moneyRange(option.cost.minTotalMinor, option.cost.maxTotalMinor)}</p>
          <p className="mt-1 text-sm">人均 {moneyRange(option.cost.perPersonMinMinor, option.cost.perPersonMaxMinor)}</p>
          <p className="text-xs text-muted-foreground">{option.cost.unknownCategories.length ? "另有 " + option.cost.unknownCategories.length + " 类费用待核实" : "按费用项范围计，参考与估算不代表实付"}</p></div>
      </div>
      <Timeline segments={option.segments} />
      <div className="space-y-2"><h4 className="text-sm font-semibold">返程：{option.returnJourney.status === "NOT_REQUESTED" ? "未计算" : feasible[option.returnJourney.feasibility]}</h4>
        {option.returnJourney.segments.length > 0 ? <Timeline segments={option.returnJourney.segments} />
          : <p className="text-xs text-muted-foreground">{option.returnJourney.warnings.map((w) => w.message).join("；") || "返程时间与末班车待核实"}</p>}</div>
      <div className="overflow-x-auto"><table className="w-full text-left text-xs"><caption className="pb-2 text-left text-sm font-semibold">费用明细</caption>
        <thead><tr className="border-b border-rule"><th className="py-2">项目 / 范围</th><th>金额</th><th>状态 / 来源</th></tr></thead><tbody>{option.cost.items.map((item, index) => <tr key={index} className="border-b border-rule align-top">
          <td className="py-2 pr-3"><p>{item.label}</p><p className="mt-1 text-muted-foreground">{item.scope === "PER_PERSON" ? "每人" : item.scope === "PER_VEHICLE" ? "每车（按一车共乘）" : "同行合计"}{item.note ? " / " + item.note : ""}</p></td>
          <td className="whitespace-nowrap py-2 pr-3">{moneyRange(item.minMinor, item.maxMinor)}</td><td className="py-2"><p>{status[item.status]}</p><Source value={item.source} /></td>
        </tr>)}</tbody></table></div>
      {option.warnings.length > 0 && <ul className="space-y-1 text-xs leading-6 text-warning">{option.warnings.map((warning, index) => <li key={index}>{warning.message}</li>)}</ul>}
      <details className="text-xs text-muted-foreground"><summary className="cursor-pointer">假设与来源（可信度 {option.confidence}）</summary>
        <ul className="mt-2 list-inside list-disc space-y-1">{option.assumptions.map((assumption, index) => <li key={index}>{assumption}</li>)}</ul>
        {option.evidence.map((evidence, index) => <p key={index} className="mt-2">{evidence.note} / <Source value={evidence.url} /></p>)}</details>
    </>}
  </section>;
}
