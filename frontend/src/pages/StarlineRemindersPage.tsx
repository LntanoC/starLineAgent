import * as React from "react";
import { Bell, CalendarDays, CheckCircle2, Clock3, Copy, ExternalLink, Loader2, Plus, Ticket, XCircle } from "lucide-react";
import { toast } from "sonner";

import { AgentLayout } from "@/components/agent/AgentLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  cancelStarlineReminder,
  createStarlineEvent,
  getCalendarToken,
  listStarlineEvents,
  resetCalendarToken,
  type StarlineEvent,
  type StarlineReminderStatus
} from "@/services/starlineService";

type EntryMode = "ANNOUNCED" | "TO_BE_ANNOUNCED";

const reminderLabels: Record<StarlineReminderStatus, string> = {
  ACTIVE: "关注中",
  CANCELLED: "已取消"
};

function formatDate(value: string | null) {
  if (!value) return "待公布";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false, timeZone: "Asia/Shanghai" });
}

function CalendarSubscribeCard() {
  const [token, setToken] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [resetting, setResetting] = React.useState(false);

  React.useEffect(() => {
    getCalendarToken()
      .then(setToken)
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  const url = token
    ? window.location.origin + "/api/starlineagent/starline/calendar.ics?token=" + encodeURIComponent(token)
    : null;

  const copy = async () => {
    if (!url) return;
    try {
      if (navigator.clipboard?.writeText && window.isSecureContext) {
        await navigator.clipboard.writeText(url);
      } else {
        const input = document.createElement("textarea");
        input.value = url;
        input.setAttribute("readonly", "");
        input.style.position = "fixed";
        input.style.opacity = "0";
        document.body.appendChild(input);
        input.select();
        const copied = document.execCommand("copy");
        document.body.removeChild(input);
        if (!copied) throw new Error("copy failed");
      }
      toast.success("日历订阅链接已复制");
    } catch {
      toast.info("请手动选择并复制订阅链接");
    }
  };

  const reset = async () => {
    if (!window.confirm("重置后旧链接会立即失效，已订阅的设备需要重新添加。继续吗？")) return;
    setResetting(true);
    try {
      setToken(await resetCalendarToken());
      toast.success("订阅链接已重置，请在日历应用中更新");
    } catch {
      // api interceptor shows request errors
    } finally {
      setResetting(false);
    }
  };

  return (
    <section className="mb-6 rounded-2xl border border-orange-100 bg-white p-5 shadow-sm md:p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2 text-slate-900">
            <CalendarDays className="h-5 w-5 text-orange-700" />
            <h2 className="font-semibold">日历订阅</h2>
          </div>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600">
            订阅后，已核验的开票活动会同步到日历，并在开票前 10 分钟提醒。没有准确开票时间的活动会标记为“待公布”，不会生成闹钟。
          </p>
        </div>
        <div className="rounded-full bg-orange-50 px-3 py-1.5 text-xs text-orange-800">
          iPhone · Android · Outlook
        </div>
      </div>

      {loading ? (
        <div className="mt-4 flex items-center gap-2 text-sm text-slate-500"><Loader2 className="h-4 w-4 animate-spin" />正在获取订阅链接…</div>
      ) : url ? (
        <>
          <div className="mt-4 flex flex-col gap-2 sm:flex-row">
            <Input readOnly value={url} className="min-w-0 flex-1 font-mono text-xs" aria-label="日历订阅链接" />
            <Button type="button" variant="outline" onClick={copy} className="shrink-0">
              <Copy className="h-4 w-4" />复制链接
            </Button>
          </div>
          <div className="mt-4 grid gap-3 text-sm text-slate-600 md:grid-cols-3">
            <details className="rounded-xl bg-slate-50 p-3">
              <summary className="cursor-pointer font-medium text-slate-800">iPhone / iPad</summary>
              <ol className="mt-2 list-decimal space-y-1 pl-5 text-xs leading-5">
                <li>复制上方订阅链接。</li>
                <li>打开“设置”→“日历”→“日历账户”→“添加账户”→“其他”。</li>
                <li>选择“添加已订阅的日历”，粘贴链接并存储。</li>
              </ol>
            </details>
            <details className="rounded-xl bg-slate-50 p-3">
              <summary className="cursor-pointer font-medium text-slate-800">Android</summary>
              <ol className="mt-2 list-decimal space-y-1 pl-5 text-xs leading-5">
                <li>在支持网络日历订阅的日历应用中添加日历。</li>
                <li>选择“通过 URL 订阅”，粘贴上方链接并保存。</li>
                <li>确认新日历已在系统日历中显示。</li>
              </ol>
            </details>
            <details className="rounded-xl bg-slate-50 p-3">
              <summary className="cursor-pointer font-medium text-slate-800">Outlook</summary>
              <ol className="mt-2 list-decimal space-y-1 pl-5 text-xs leading-5">
                <li>打开 Outlook 日历，选择“添加日历”。</li>
                <li>选择“从 Web 订阅”，粘贴上方链接并保存。</li>
              </ol>
            </details>
          </div>
          <div className="mt-4 flex flex-col justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900 sm:flex-row sm:items-center">
            <span>订阅链接只允许读取你的星序日历，等同私人凭证。日历应用的刷新时间由客户端决定，新活动或取消可能需要一段时间同步。</span>
            <Button type="button" variant="outline" size="sm" onClick={reset} disabled={resetting} className="shrink-0">
              {resetting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
              重置订阅链接
            </Button>
          </div>
        </>
      ) : (
        <p className="mt-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-700">暂时无法获取日历订阅链接，请刷新页面后重试。</p>
      )}
    </section>
  );
}

export function StarlineRemindersPage() {
  const [events, setEvents] = React.useState<StarlineEvent[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [cancelingId, setCancelingId] = React.useState<string | null>(null);
  const [entryMode, setEntryMode] = React.useState<EntryMode>("ANNOUNCED");
  const [artistName, setArtistName] = React.useState("");
  const [eventName, setEventName] = React.useState("");
  const [sourceName, setSourceName] = React.useState("");
  const [sourceUrl, setSourceUrl] = React.useState("");
  const [sourceConfirmed, setSourceConfirmed] = React.useState(false);
  const [ticketTime, setTicketTime] = React.useState("");

  const refresh = React.useCallback(async () => {
    setLoading(true);
    try {
      setEvents(await listStarlineEvents());
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    refresh().catch(() => null);
  }, [refresh]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSaving(true);
    try {
      await createStarlineEvent({
        artistName,
        eventName: eventName || undefined,
        ...(entryMode === "ANNOUNCED" ? { sourceName, sourceUrl, sourceConfirmed, ticketTime } : {})
      });
      toast.success(entryMode === "ANNOUNCED"
        ? "活动已加入日历，开票前 10 分钟提醒"
        : "已记录为待公布；有准确开票时间后再添加日历提醒");
      setArtistName("");
      setEventName("");
      setSourceName("");
      setSourceUrl("");
      setSourceConfirmed(false);
      setTicketTime("");
      await refresh();
    } catch {
      // api interceptor shows request errors
    } finally {
      setSaving(false);
    }
  };

  const cancel = async (id: string) => {
    setCancelingId(id);
    try {
      await cancelStarlineReminder(id);
      toast.success("活动已取消，日历将在后续刷新时移除");
      await refresh();
    } catch {
      // api interceptor shows request errors
    } finally {
      setCancelingId(null);
    }
  };

  return (
    <AgentLayout>
      <div className="h-full min-h-0 w-full min-w-0 overflow-y-auto bg-[#fbfaf8] px-4 py-5 sm:px-5 sm:py-7 md:px-9">
        <div className="mx-auto max-w-6xl">
          <header className="mb-7 flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="mb-2 text-xs font-semibold uppercase tracking-[0.22em] text-orange-700">STARLINE / EVENTS</p>
              <h1 className="text-2xl font-semibold tracking-tight text-slate-900 md:text-3xl">starline星序</h1>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">
                记录并核验艺人演出与开票信息，通过日历订阅接收开票提醒。
              </p>
            </div>
            <div className="flex items-center gap-2 rounded-full border border-orange-100 bg-orange-50 px-4 py-2 text-xs text-orange-800">
              <Bell className="h-4 w-4" />开票前 10 分钟
            </div>
          </header>

          <CalendarSubscribeCard />

          <div className="grid gap-6 lg:grid-cols-[minmax(300px,0.85fr)_minmax(0,1.4fr)]">
            <section className="h-fit rounded-2xl border border-slate-200 bg-white p-5 shadow-sm md:p-6">
              <div className="mb-5 flex items-center gap-3">
                <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-orange-50 text-orange-700">
                  <Plus className="h-5 w-5" />
                </span>
                <div>
                  <h2 className="font-semibold text-slate-900">记录活动信息</h2>
                  <p className="mt-1 text-xs text-slate-500">一个艺人、一场活动一条记录</p>
                </div>
              </div>
              <form onSubmit={submit} className="space-y-4">
                <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                  艺人名称
                  <Input required value={artistName} onChange={(e) => setArtistName(e.target.value)} placeholder="例如：艺人名称" />
                </label>
                <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                  活动名称 <span className="font-normal text-slate-400">（待公布时可留空）</span>
                  <Input value={eventName} onChange={(e) => setEventName(e.target.value)} placeholder="例如：巡演名称 / 城市站" />
                </label>
                <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                  信息状态
                  <select
                    className="h-11 w-full rounded-2xl border border-slate-200 bg-white px-4 text-sm outline-none focus:ring-2 focus:ring-orange-200"
                    value={entryMode}
                    onChange={(e) => setEntryMode(e.target.value as EntryMode)}
                  >
                    <option value="ANNOUNCED">已公布，设置日历提醒</option>
                    <option value="TO_BE_ANNOUNCED">待公布，仅记录状态</option>
                  </select>
                </label>

                {entryMode === "ANNOUNCED" ? (
                  <div className="space-y-4 border-t border-slate-100 pt-4">
                    <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                      来源名称
                      <Input required value={sourceName} onChange={(e) => setSourceName(e.target.value)} placeholder="官方售票平台 / 艺人工作室" />
                    </label>
                    <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                      来源链接
                      <Input required type="url" value={sourceUrl} onChange={(e) => setSourceUrl(e.target.value)} placeholder="https://..." />
                    </label>
                    <label className="flex cursor-pointer items-start gap-2.5 rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-600">
                      <input
                        className="mt-0.5 accent-orange-600"
                        type="checkbox"
                        checked={sourceConfirmed}
                        onChange={(e) => setSourceConfirmed(e.target.checked)}
                        required
                      />
                      <span>我已核对来源内容与艺人、活动及准确开票时间一致。</span>
                    </label>
                    <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                      开票时间（北京时间）
                      <Input required type="datetime-local" value={ticketTime} onChange={(e) => setTicketTime(e.target.value)} />
                      <span className="block text-xs font-normal text-slate-400">
                        订阅日历的闹钟固定提前 10 分钟；距开票不足 10 分钟时无法创建，未公布时间时请标记为“待公布”。
                      </span>
                    </label>
                  </div>
                ) : (
                  <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                    <strong>待公布</strong>
                    <p className="mt-1 text-xs leading-5 text-amber-800">只记录活动，不猜测开票时间，也不会添加日历闹钟。</p>
                  </div>
                )}

                <Button type="submit" className="w-full rounded-xl bg-orange-600 hover:bg-orange-700" disabled={saving}>
                  {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                  {entryMode === "ANNOUNCED" ? "确认来源并订阅提醒" : "记录为待公布"}
                </Button>
              </form>
              <p className="mt-4 text-[11px] leading-5 text-slate-400">
                活动信息仍由用户录入并核验。日历应用的同步周期由客户端决定，新增或取消可能需要一段时间才显示。
              </p>
            </section>

            <section className="min-w-0">
              <div className="mb-3 flex items-center justify-between">
                <div>
                  <h2 className="font-semibold text-slate-900">我的活动</h2>
                  <p className="mt-1 text-xs text-slate-500">只显示当前登录账号创建的记录</p>
                </div>
                <Button type="button" variant="outline" size="sm" onClick={() => refresh().catch(() => null)} disabled={loading}>
                  {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : "刷新"}
                </Button>
              </div>
              {loading ? (
                <div className="rounded-2xl border border-slate-200 bg-white px-5 py-12 text-center text-sm text-slate-500">
                  <Loader2 className="mx-auto mb-2 h-5 w-5 animate-spin" />正在读取记录…
                </div>
              ) : events.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-5 py-14 text-center">
                  <CalendarDays className="mx-auto mb-3 h-8 w-8 text-slate-300" />
                  <p className="font-medium text-slate-700">还没有活动记录</p>
                  <p className="mt-1 text-sm text-slate-400">录入已核验信息，或标记为待公布。</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {events.map((item) => {
                    const waiting = item.ticketTime == null;
                    return (
                      <article key={item.id} className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div>
                            <h3 className="text-base font-semibold text-slate-900">{item.artistName}</h3>
                            <p className="mt-1 text-sm text-slate-600">{item.eventName || "演出/开票信息"}</p>
                          </div>
                          <div className="flex flex-wrap gap-2">
                            {waiting ? (
                              <span className="rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-800">待公布</span>
                            ) : (
                              <span className="rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700">来源已确认</span>
                            )}
                            <span className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600">{reminderLabels[item.reminderStatus]}</span>
                          </div>
                        </div>
                        {waiting ? (
                          <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">尚无准确开票时间，因此不会出现在日历中，也不会触发提醒。</p>
                        ) : (
                          <div className="mt-4 grid gap-3 border-t border-slate-100 pt-4 text-sm sm:grid-cols-2">
                            <p className="flex items-start gap-2 text-slate-600"><Ticket className="mt-0.5 h-4 w-4 shrink-0 text-orange-600" /><span>开票：{formatDate(item.ticketTime)}</span></p>
                            <p className="flex items-start gap-2 text-slate-600"><Clock3 className="mt-0.5 h-4 w-4 shrink-0 text-orange-600" /><span>提醒：开票前 10 分钟</span></p>
                            {item.sourceUrl ? (
                              <a className="flex items-start gap-2 break-all text-orange-700 hover:underline" href={item.sourceUrl} target="_blank" rel="noreferrer">
                                <ExternalLink className="mt-0.5 h-4 w-4 shrink-0" /><span>{item.sourceName} · 查看来源</span>
                              </a>
                            ) : null}
                          </div>
                        )}
                        <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-3">
                          <span className="text-xs text-slate-400">创建于 {formatDate(item.createdTime)}</span>
                          {item.reminderStatus === "ACTIVE" ? (
                            <Button type="button" variant="outline" size="sm" className="rounded-xl text-rose-700 hover:bg-rose-50" onClick={() => cancel(item.id)} disabled={cancelingId === item.id}>
                              {cancelingId === item.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <XCircle className="h-3.5 w-3.5" />}
                              {waiting ? "取消关注" : "取消提醒"}
                            </Button>
                          ) : (
                            <span className="inline-flex items-center gap-1 text-xs text-slate-500"><CheckCircle2 className="h-3.5 w-3.5" />已取消</span>
                          )}
                        </div>
                      </article>
                    );
                  })}
                </div>
              )}
            </section>
          </div>
        </div>
      </div>
    </AgentLayout>
  );
}
