import { useCallback, useEffect, useMemo, useState } from "react";
import { Check, ClipboardCheck, Globe2, LoaderCircle, Pencil, Plus, Power, RefreshCw, Save, X } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  createStarlinePlatformSource, listStarlinePlatformSources, setStarlinePlatformSourceEnabled,
  updateStarlinePlatformSource, type PlatformFieldStability, type PlatformSourceTier, type SavePlatformSourceRequest,
  type StarlinePlatformSource
} from "@/services/starlinePlatformSourceService";
import { getErrorMessage } from "@/utils/error";

type FormState = {
  platformName: string; officialDomain: string; sourceTier: PlatformSourceTier; publicAccessible: boolean | null;
  activityFieldsAvailable: boolean | null; ticketTimeAvailable: boolean | null;
  ticketStatusAvailable: boolean | null; fieldStability: PlatformFieldStability | "";
  officialIdentityEvidence: string; updateFrequency: string; accessBoundary: string;
};
const EMPTY: FormState = {
  platformName: "", officialDomain: "", sourceTier: "TICKETING", publicAccessible: null, activityFieldsAvailable: null,
  ticketTimeAvailable: null, ticketStatusAvailable: null, fieldStability: "",
  officialIdentityEvidence: "", updateFrequency: "", accessBoundary: ""
};
const CHECK_FIELDS = [
  { key: "publicAccessible", label: "页面可公开访问" },
  { key: "activityFieldsAvailable", label: "活动 / 场次字段" },
  { key: "ticketTimeAvailable", label: "开票时间字段" },
  { key: "ticketStatusAvailable", label: "开票状态字段" }
] as const;
const STATUS = { ACTIVE: "已启用", DISABLED: "已停用" };
const STATUS_CLASS = {
  ACTIVE: "border-emerald-200 bg-emerald-50 text-emerald-700",
  DISABLED: "border-slate-200 bg-slate-100 text-slate-600"
};
const STABILITY: Record<PlatformFieldStability, string> = { STABLE: "稳定", PARTIAL: "部分稳定", UNSTABLE: "不稳定" };

function FieldLabel({ children }: { children: React.ReactNode }) {
  return <label className="mb-1.5 block text-sm font-medium text-slate-700">{children}</label>;
}

function formFrom(source: StarlinePlatformSource): FormState {
  return {
    platformName: source.platformName, officialDomain: source.officialDomain, sourceTier: source.sourceTier,
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
  const [changingStatus, setChangingStatus] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY);

  const refresh = useCallback(async () => {
    setLoading(true);
    try { setItems(await listStarlinePlatformSources()); }
    catch (error) { toast.error(getErrorMessage(error, "读取官方来源白名单失败")); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);

  const counts = useMemo(() => ({
    total: items.length,
    active: items.filter((item) => item.status === "ACTIVE" && item.publicAccessible).length,
    disabled: items.filter((item) => item.status !== "ACTIVE" || !item.publicAccessible).length
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
      platformName: form.platformName.trim(), officialDomain: form.officialDomain.trim(), sourceTier: form.sourceTier,
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
        toast.success(body.publicAccessible ? "白名单已更新并立即生效" : "来源已保存并停用");
      } else {
        await createStarlinePlatformSource(body);
        toast.success(body.publicAccessible ? "来源已加入白名单并立即生效" : "来源已登记，因页面不可公开访问而停用");
      }
      setFormOpen(false); setForm(EMPTY); setEditingId(null);
      await refresh();
    } catch (error) { toast.error(getErrorMessage(error, "保存来源失败")); }
    finally { setSaving(false); }
  };

  const toggleEnabled = async (source: StarlinePlatformSource) => {
    const enabled = source.status !== "ACTIVE";
    setChangingStatus(source.id);
    try {
      await setStarlinePlatformSourceEnabled(source.id, { enabled });
      toast.success(enabled ? "来源已启用并立即生效" : "来源已停用并立即从查询白名单移除");
      await refresh();
    } catch (error) { toast.error(getErrorMessage(error, "更新白名单状态失败")); }
    finally { setChangingStatus(""); }
  };

  return <div className="space-y-6 pb-8">
    <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div>
        <p className="mb-2 flex items-center gap-2 text-sm font-medium text-indigo-600"><Globe2 className="h-4 w-4" />星序 · 信息来源治理</p>
        <h1 className="text-2xl font-semibold text-slate-950">官方来源白名单</h1>
        <p className="mt-2 text-sm text-slate-600">管理员新增、编辑或停用来源后立即生效。演出查询只返回 URL 主机名匹配当前启用白名单的结果。</p>
        <p className="mt-1 text-xs text-slate-500">填写根域名后，该域名及其子域名都可匹配；例如 example.cn 同时允许 www.example.cn。</p>
      </div>
      <div className="flex gap-2">
        <Button variant="outline" disabled={loading} onClick={() => void refresh()}><RefreshCw className={"h-4 w-4 " + (loading ? "animate-spin" : "")} />刷新</Button>
        <Button onClick={() => { setEditingId(null); setForm(EMPTY); setFormOpen(true); }}><Plus className="h-4 w-4" />添加来源</Button>
      </div>
    </div>

    <div className="grid gap-3 sm:grid-cols-3">
      <Card><CardContent className="p-5"><p className="text-sm text-slate-500">来源总数</p><p className="mt-2 text-2xl font-semibold">{counts.total}</p></CardContent></Card>
      <Card><CardContent className="p-5"><p className="text-sm text-slate-500">已启用</p><p className="mt-2 text-2xl font-semibold text-emerald-600">{counts.active}</p></CardContent></Card>
      <Card><CardContent className="p-5"><p className="text-sm text-slate-500">已停用</p><p className="mt-2 text-2xl font-semibold text-slate-500">{counts.disabled}</p></CardContent></Card>
    </div>

    {formOpen && <Card className="border-indigo-200">
      <CardHeader><CardTitle className="text-lg">{editingId ? "编辑白名单来源" : "添加白名单来源"}</CardTitle><CardDescription>保存后立即影响下一次演出查询；将页面可公开访问设为“否”会自动停用该来源。</CardDescription></CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 md:grid-cols-2">
          <div><FieldLabel>平台名称</FieldLabel><Input value={form.platformName} onChange={(e) => setValue("platformName", e.target.value)} /></div>
          <div><FieldLabel>官方域名</FieldLabel><Input value={form.officialDomain} onChange={(e) => setValue("officialDomain", e.target.value)} placeholder="例如：tickets.example.cn" /></div>
        </div>
        <div className="max-w-md">
          <FieldLabel>信息源层级</FieldLabel>
          <Select value={form.sourceTier} onValueChange={(value) => setValue("sourceTier", value as PlatformSourceTier)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="GOV">文旅许可公示（仅证明已获批复）</SelectItem>
              <SelectItem value="TICKETING">票务平台（上架 / 开票 / 售罄）</SelectItem>
            </SelectContent>
          </Select>
          <p className="mt-1 text-xs text-slate-500">该层级参与演出阶段判定，保存后立即生效。</p>
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
          <Button disabled={!valid || saving} onClick={() => void save()}>{saving ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}{saving ? "保存中..." : "保存并生效"}</Button>
        </div>
      </CardContent>
    </Card>}

    {loading && items.length === 0 ? <Card><CardContent className="flex items-center justify-center gap-2 p-10 text-slate-500"><LoaderCircle className="h-4 w-4 animate-spin" />正在加载...</CardContent></Card>
      : items.length === 0 ? <Card><CardContent className="flex flex-col items-center p-10 text-center"><ClipboardCheck className="mb-3 h-8 w-8 text-slate-400" /><p className="font-medium">还没有白名单来源</p><p className="mt-1 text-sm text-slate-500">添加一个官方域名后，演出查询就会使用它过滤搜索结果。</p></CardContent></Card>
      : <div className="space-y-4">{items.map((item) => <Card key={item.id} className="overflow-hidden">
        <CardHeader className="gap-3 border-b sm:flex-row sm:items-start sm:justify-between">
          <div><div className="flex flex-wrap items-center gap-2"><CardTitle className="text-lg">{item.platformName}</CardTitle><Badge variant="outline" className={item.sourceTier === "GOV" ? "border-sky-200 bg-sky-50 text-sky-700" : "border-amber-200 bg-amber-50 text-amber-700"}>{item.sourceTier === "GOV" ? "文旅许可" : "票务平台"}</Badge><Badge variant="outline" className={STATUS_CLASS[item.status]}>{STATUS[item.status]}</Badge><span className="font-mono text-sm text-slate-500">{item.officialDomain}</span></div><CardDescription className="mt-2">更新于 {new Date(item.updateTime).toLocaleString("zh-CN")}</CardDescription></div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={changingStatus === item.id} onClick={() => void toggleEnabled(item)}>
              {changingStatus === item.id ? <LoaderCircle className="h-4 w-4 animate-spin" /> : item.status === "ACTIVE" ? <Power className="h-4 w-4" /> : <Check className="h-4 w-4" />}
              {item.status === "ACTIVE" ? "停用" : "启用"}
            </Button>
            <Button variant="outline" size="sm" onClick={() => { setEditingId(item.id); setForm(formFrom(item)); setFormOpen(true); }}><Pencil className="h-4 w-4" />编辑</Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-5 pt-5">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{CHECK_FIELDS.map((field) => <div key={field.key} className="rounded-xl bg-slate-50 p-3"><p className="text-xs text-slate-500">{field.label}</p><p className="mt-1 text-sm font-medium">{item[field.key] ? "可获取" : "不可获取"}</p></div>)}</div>
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <div><p className="text-xs text-slate-500">字段稳定性</p><p className="mt-1 text-sm font-medium">{STABILITY[item.fieldStability]}</p></div>
            <div><p className="text-xs text-slate-500">官方身份依据</p><p className="mt-1 whitespace-pre-wrap text-sm">{item.officialIdentityEvidence}</p></div>
            <div><p className="text-xs text-slate-500">更新频率</p><p className="mt-1 whitespace-pre-wrap text-sm">{item.updateFrequency}</p></div>
            <div><p className="text-xs text-slate-500">访问 / 授权边界</p><p className="mt-1 whitespace-pre-wrap text-sm">{item.accessBoundary}</p></div>
          </div>
          {item.status === "ACTIVE" && item.publicAccessible && <div className="flex gap-2 rounded-xl border border-emerald-100 bg-emerald-50 p-3 text-sm text-emerald-800"><Check className="h-4 w-4 shrink-0" />此来源已进入实时查询白名单；停用后将立即从查询结果中排除。</div>}
          {item.status === "ACTIVE" && !item.publicAccessible && <div className="flex gap-2 rounded-xl border border-amber-100 bg-amber-50 p-3 text-sm text-amber-800"><X className="h-4 w-4 shrink-0" />该来源标记为不可公开访问，因此不会被查询服务使用。</div>}
        </CardContent>
      </Card>)}</div>}
  </div>;
}
