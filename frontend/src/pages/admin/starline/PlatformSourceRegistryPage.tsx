import { useCallback, useEffect, useMemo, useState } from "react";
import { Check, CircleAlert, ClipboardCheck, Globe2, LoaderCircle, Pencil, Plus, RefreshCw, Save, X } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  createStarlinePlatformSource, listStarlinePlatformSources, reviewStarlinePlatformSource,
  updateStarlinePlatformSource, type PlatformFieldStability, type SavePlatformSourceRequest,
  type SourceReviewerType, type StarlinePlatformSource
} from "@/services/starlinePlatformSourceService";
import { getErrorMessage } from "@/utils/error";

type FormState = {
  platformName: string; officialDomain: string; publicAccessible: boolean | null;
  activityFieldsAvailable: boolean | null; ticketTimeAvailable: boolean | null;
  ticketStatusAvailable: boolean | null; fieldStability: PlatformFieldStability | "";
  officialIdentityEvidence: string; updateFrequency: string; accessBoundary: string;
};
type ReviewDraft = { reviewerName: string; reviewNote: string };
type Drafts = Record<string, Partial<Record<SourceReviewerType, ReviewDraft>>>;
const EMPTY: FormState = {
  platformName: "", officialDomain: "", publicAccessible: null, activityFieldsAvailable: null,
  ticketTimeAvailable: null, ticketStatusAvailable: null, fieldStability: "",
  officialIdentityEvidence: "", updateFrequency: "", accessBoundary: ""
};
const CHECK_FIELDS = [
  { key: "publicAccessible", label: "页面可公开访问" },
  { key: "activityFieldsAvailable", label: "活动 / 场次字段" },
  { key: "ticketTimeAvailable", label: "开票时间字段" },
  { key: "ticketStatusAvailable", label: "开票状态字段" }
] as const;
const STATUS = { PENDING: "待双评审", APPROVED: "已纳入清单", REJECTED: "未通过" };
const STATUS_CLASS = {
  PENDING: "border-amber-200 bg-amber-50 text-amber-700",
  APPROVED: "border-emerald-200 bg-emerald-50 text-emerald-700",
  REJECTED: "border-rose-200 bg-rose-50 text-rose-700"
};
const STABILITY: Record<PlatformFieldStability, string> = { STABLE: "稳定", PARTIAL: "部分稳定", UNSTABLE: "不稳定" };

function FieldLabel({ children }: { children: React.ReactNode }) {
  return <label className="mb-1.5 block text-sm font-medium text-slate-700">{children}</label>;
}
function formFrom(source: StarlinePlatformSource): FormState {
  return {
    platformName: source.platformName, officialDomain: source.officialDomain,
    publicAccessible: source.publicAccessible, activityFieldsAvailable: source.activityFieldsAvailable,
    ticketTimeAvailable: source.ticketTimeAvailable, ticketStatusAvailable: source.ticketStatusAvailable,
    fieldStability: source.fieldStability, officialIdentityEvidence: source.officialIdentityEvidence,
    updateFrequency: source.updateFrequency, accessBoundary: source.accessBoundary
  };
}

export function PlatformSourceRegistryPage() {
  const [items, setItems] = useState<StarlinePlatformSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busyReview, setBusyReview] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY);
  const [drafts, setDrafts] = useState<Drafts>({});

  const refresh = useCallback(async () => {
    setLoading(true);
    try { setItems(await listStarlinePlatformSources()); }
    catch (error) { toast.error(getErrorMessage(error, "读取平台来源失败")); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);

  const counts = useMemo(() => ({
    total: items.length,
    pending: items.filter((item) => item.status === "PENDING").length,
    approved: items.filter((item) => item.status === "APPROVED").length
  }), [items]);
  const setValue = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((current) => ({ ...current, [key]: value }));
  const valid = Boolean(
    form.platformName.trim() && form.officialDomain.trim() && form.publicAccessible !== null &&
    form.activityFieldsAvailable !== null && form.ticketTimeAvailable !== null &&
    form.ticketStatusAvailable !== null && form.fieldStability &&
    form.officialIdentityEvidence.trim() && form.updateFrequency.trim() && form.accessBoundary.trim()
  );

  const save = async () => {
    if (!valid || !form.fieldStability) return;
    const body: SavePlatformSourceRequest = {
      platformName: form.platformName.trim(), officialDomain: form.officialDomain.trim(),
      publicAccessible: form.publicAccessible === true,
      activityFieldsAvailable: form.activityFieldsAvailable === true,
      ticketTimeAvailable: form.ticketTimeAvailable === true,
      ticketStatusAvailable: form.ticketStatusAvailable === true,
      fieldStability: form.fieldStability, officialIdentityEvidence: form.officialIdentityEvidence.trim(),
      updateFrequency: form.updateFrequency.trim(), accessBoundary: form.accessBoundary.trim()
    };
    setSaving(true);
    try {
      if (editingId) {
        await updateStarlinePlatformSource(editingId, body);
        toast.success("调研信息已更新，需重新完成双评审");
      } else {
        await createStarlinePlatformSource(body);
        toast.success("候选平台已登记");
      }
      setFormOpen(false); setForm(EMPTY); setEditingId(null);
      await refresh();
    } catch (error) { toast.error(getErrorMessage(error, "保存平台来源失败")); }
    finally { setSaving(false); }
  };

  const changeDraft = (id: string, role: SourceReviewerType, field: keyof ReviewDraft, value: string) => {
    setDrafts((prev) => ({
      ...prev,
      [id]: {
        ...prev[id],
        [role]: {
          reviewerName: prev[id]?.[role]?.reviewerName ?? "",
          reviewNote: prev[id]?.[role]?.reviewNote ?? "",
          [field]: value
        }
      }
    }));
  };
  const submitReview = async (item: StarlinePlatformSource, role: SourceReviewerType, passed: boolean) => {
    const prior = role === "PRODUCT" ? item.productReview : item.engineeringReview;
    const local = drafts[item.id]?.[role];
    const reviewerName = (local?.reviewerName ?? prior?.reviewerName ?? "").trim();
    const reviewNote = (local?.reviewNote ?? prior?.reviewNote ?? "").trim();
    const key = item.id + ":" + role;
    if (!reviewerName) { toast.error("请填写评审人姓名"); return; }
    setBusyReview(key);
    try {
      await reviewStarlinePlatformSource(item.id, { reviewerType: role, reviewerName, passed, reviewNote });
      toast.success(passed ? "评审已通过" : "已记录未通过结论");
      await refresh();
    } catch (error) { toast.error(getErrorMessage(error, "提交评审失败")); }
    finally { setBusyReview(""); }
  };

  const renderReview = (item: StarlinePlatformSource, role: SourceReviewerType, label: string) => {
    const review = role === "PRODUCT" ? item.productReview : item.engineeringReview;
    const local = drafts[item.id]?.[role];
    const key = item.id + ":" + role;
    return <div key={role} className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
      <div className="mb-3 flex items-center justify-between">
        <h4 className="font-semibold">{label}评审</h4>
        {review ? <Badge variant="outline" className={review.passed ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-rose-200 bg-rose-50 text-rose-700"}>{review.passed ? "已通过" : "未通过"}</Badge> : <span className="text-xs text-slate-500">尚未评审</span>}
      </div>
      <div className="space-y-3">
        <Input value={local?.reviewerName ?? review?.reviewerName ?? ""} onChange={(e) => changeDraft(item.id, role, "reviewerName", e.target.value)} placeholder="评审人姓名" />
        <Textarea value={local?.reviewNote ?? review?.reviewNote ?? ""} onChange={(e) => changeDraft(item.id, role, "reviewNote", e.target.value)} placeholder="评审意见或纳入 / 拒绝依据" className="min-h-[72px] rounded-xl" />
        <div className="flex gap-2">
          <Button size="sm" disabled={busyReview === key} onClick={() => void submitReview(item, role, true)}>{busyReview === key ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />}通过</Button>
          <Button size="sm" variant="outline" className="text-rose-700" disabled={busyReview === key} onClick={() => void submitReview(item, role, false)}><X className="h-4 w-4" />不通过</Button>
        </div>
      </div>
    </div>;
  };

  return <div className="space-y-6 pb-8">
    <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div>
        <p className="mb-2 flex items-center gap-2 text-sm font-medium text-indigo-600"><Globe2 className="h-4 w-4" />星序 · 信息来源治理</p>
        <h1 className="text-2xl font-semibold text-slate-950">平台级来源清单</h1>
        <p className="mt-2 text-sm text-slate-600">依据 D-13 记录调研结论；产品与研发分别评审通过后，来源才可进入后续查询适配器的可用清单。</p>
      </div>
      <div className="flex gap-2">
        <Button variant="outline" disabled={loading} onClick={() => void refresh()}><RefreshCw className={"h-4 w-4 " + (loading ? "animate-spin" : "")} />刷新</Button>
        <Button onClick={() => { setEditingId(null); setForm(EMPTY); setFormOpen(true); }}><Plus className="h-4 w-4" />登记候选平台</Button>
      </div>
    </div>

    <div className="grid gap-3 sm:grid-cols-3">
      <Card><CardContent className="p-5"><p className="text-sm text-slate-500">候选平台总数</p><p className="mt-2 text-2xl font-semibold">{counts.total}</p></CardContent></Card>
      <Card><CardContent className="p-5"><p className="text-sm text-slate-500">等待双评审</p><p className="mt-2 text-2xl font-semibold text-amber-600">{counts.pending}</p></CardContent></Card>
      <Card><CardContent className="p-5"><p className="text-sm text-slate-500">已纳入清单</p><p className="mt-2 text-2xl font-semibold text-emerald-600">{counts.approved}</p></CardContent></Card>
    </div>

    {formOpen && <Card className="border-indigo-200">
      <CardHeader><CardTitle className="text-lg">{editingId ? "编辑平台调研" : "登记候选平台"}</CardTitle><CardDescription>编辑已登记的平台会清除旧评审结论，必须重新评审。</CardDescription></CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 md:grid-cols-2">
          <div><FieldLabel>平台名称</FieldLabel><Input value={form.platformName} onChange={(e) => setValue("platformName", e.target.value)} /></div>
          <div><FieldLabel>官方域名</FieldLabel><Input value={form.officialDomain} onChange={(e) => setValue("officialDomain", e.target.value)} placeholder="例如：tickets.example.cn" /></div>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {CHECK_FIELDS.map((field) => <div key={field.key}>
            <FieldLabel>{field.label}</FieldLabel>
            <Select value={form[field.key] === null ? "" : form[field.key] ? "yes" : "no"} onValueChange={(v) => setValue(field.key, v === "yes")}>
              <SelectTrigger><SelectValue placeholder="选择结论" /></SelectTrigger>
              <SelectContent><SelectItem value="yes">是 / 可获取</SelectItem><SelectItem value="no">否 / 不可获取</SelectItem></SelectContent>
            </Select>
          </div>)}
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <div><FieldLabel>字段稳定性</FieldLabel><Select value={form.fieldStability} onValueChange={(v) => setValue("fieldStability", v as PlatformFieldStability)}>
            <SelectTrigger><SelectValue placeholder="选择稳定性" /></SelectTrigger><SelectContent><SelectItem value="STABLE">稳定</SelectItem><SelectItem value="PARTIAL">部分稳定</SelectItem><SelectItem value="UNSTABLE">不稳定</SelectItem></SelectContent>
          </Select></div>
          <div><FieldLabel>官方身份依据</FieldLabel><Textarea className="rounded-xl" value={form.officialIdentityEvidence} onChange={(e) => setValue("officialIdentityEvidence", e.target.value)} placeholder="域名与票务 / 公告平台官方主体的对应依据" /></div>
          <div><FieldLabel>更新频率</FieldLabel><Textarea className="rounded-xl" value={form.updateFrequency} onChange={(e) => setValue("updateFrequency", e.target.value)} placeholder="例如：实时 / 每日 / 未知" /></div>
          <div><FieldLabel>访问或授权边界</FieldLabel><Textarea className="rounded-xl" value={form.accessBoundary} onChange={(e) => setValue("accessBoundary", e.target.value)} placeholder="公开访问、登录、robots、授权或其他限制" /></div>
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={saving} onClick={() => setFormOpen(false)}>取消</Button>
          <Button disabled={!valid || saving} onClick={() => void save()}>{saving ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}{saving ? "保存中..." : "保存调研信息"}</Button>
        </div>
      </CardContent>
    </Card>}

    {loading && items.length === 0 ? <Card><CardContent className="flex items-center justify-center gap-2 p-10 text-slate-500"><LoaderCircle className="h-4 w-4 animate-spin" />正在加载...</CardContent></Card>
      : items.length === 0 ? <Card><CardContent className="flex flex-col items-center p-10 text-center"><ClipboardCheck className="mb-3 h-8 w-8 text-slate-400" /><p className="font-medium">还没有候选平台</p><p className="mt-1 text-sm text-slate-500">登记平台调研并完成产品、研发双评审。</p></CardContent></Card>
      : <div className="space-y-4">{items.map((item) => <Card key={item.id} className="overflow-hidden">
        <CardHeader className="gap-3 border-b sm:flex-row sm:items-start sm:justify-between">
          <div><div className="flex flex-wrap items-center gap-2"><CardTitle className="text-lg">{item.platformName}</CardTitle><Badge variant="outline" className={STATUS_CLASS[item.status]}>{STATUS[item.status]}</Badge><span className="font-mono text-sm text-slate-500">{item.officialDomain}</span></div><CardDescription className="mt-2">更新于 {new Date(item.updateTime).toLocaleString("zh-CN")}</CardDescription></div>
          <Button variant="outline" size="sm" onClick={() => { setEditingId(item.id); setForm(formFrom(item)); setFormOpen(true); }}><Pencil className="h-4 w-4" />编辑调研</Button>
        </CardHeader>
        <CardContent className="space-y-5 pt-5">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{CHECK_FIELDS.map((field) => <div key={field.key} className="rounded-xl bg-slate-50 p-3"><p className="text-xs text-slate-500">{field.label}</p><p className="mt-1 text-sm font-medium">{item[field.key] ? "可获取" : "不可获取"}</p></div>)}</div>
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <div><p className="text-xs text-slate-500">字段稳定性</p><p className="mt-1 text-sm font-medium">{STABILITY[item.fieldStability]}</p></div>
            <div><p className="text-xs text-slate-500">官方身份依据</p><p className="mt-1 whitespace-pre-wrap text-sm">{item.officialIdentityEvidence}</p></div>
            <div><p className="text-xs text-slate-500">更新频率</p><p className="mt-1 whitespace-pre-wrap text-sm">{item.updateFrequency}</p></div>
            <div><p className="text-xs text-slate-500">访问 / 授权边界</p><p className="mt-1 whitespace-pre-wrap text-sm">{item.accessBoundary}</p></div>
          </div>
          {item.status === "APPROVED" && <div className="flex gap-2 rounded-xl border border-emerald-100 bg-emerald-50 p-3 text-sm text-emerald-800"><Check className="h-4 w-4 shrink-0" />平台域名已通过双评审；具体活动仍需活动级官方来源核验。</div>}
          {item.status === "REJECTED" && <div className="flex gap-2 rounded-xl border border-rose-100 bg-rose-50 p-3 text-sm text-rose-800"><CircleAlert className="h-4 w-4 shrink-0" />存在未通过评审，可修改调研信息后重新发起评审。</div>}
          <div className="grid gap-4 lg:grid-cols-2">{renderReview(item, "PRODUCT", "产品")}{renderReview(item, "ENGINEERING", "研发")}</div>
        </CardContent>
      </Card>)}</div>}
  </div>;
}
