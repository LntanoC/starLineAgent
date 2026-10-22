import * as React from "react";
import { Bell, CalendarDays, CheckCircle2, Clock3, ExternalLink, Loader2, Plus, Ticket, XCircle } from "lucide-react";
import { toast } from "sonner";

import { AgentLayout } from "@/components/agent/AgentLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  cancelStarlineReminder,
  createStarlineEvent,
  listStarlineEvents,
  type StarlineEvent,
  type StarlineInfoStatus
} from "@/services/starlineService";

const reminderLabels: Record<string, string> = {
  NOT_CREATED: "仅记录信息",
  PENDING: "待提醒",
  SENDING: "发送中",
  SENT: "已发送",
  CANCELLED: "已取消",
  FAILED: "发送失败"
};

function formatDate(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

export function StarlineRemindersPage() {
  const [events, setEvents] = React.useState<StarlineEvent[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [cancelingId, setCancelingId] = React.useState<string | null>(null);
  const [infoStatus, setInfoStatus] = React.useState<StarlineInfoStatus>("ANNOUNCED");
  const [artistName, setArtistName] = React.useState("");
  const [eventName, setEventName] = React.useState("");
  const [sourceName, setSourceName] = React.useState("");
  const [sourceUrl, setSourceUrl] = React.useState("");
  const [sourceConfirmed, setSourceConfirmed] = React.useState(false);
  const [ticketTime, setTicketTime] = React.useState("");
  const [remindAt, setRemindAt] = React.useState("");
  const [email, setEmail] = React.useState("");

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
        infoStatus,
        ...(infoStatus === "ANNOUNCED"
          ? { sourceName, sourceUrl, sourceConfirmed, ticketTime, remindAt, email }
          : {})
      });
      toast.success(infoStatus === "ANNOUNCED" ? "已创建邮箱提醒" : "已记录为待公布");
      setArtistName("");
      setEventName("");
      setSourceName("");
      setSourceUrl("");
      setSourceConfirmed(false);
      setTicketTime("");
      setRemindAt("");
      setEmail("");
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
      toast.success("提醒已取消");
      await refresh();
    } catch {
      // api interceptor shows request errors
    } finally {
      setCancelingId(null);
    }
  };

  return (
    <AgentLayout>
      <div className="h-full min-h-0 overflow-y-auto bg-[#fbfaf8] px-5 py-7 md:px-9">
        <div className="mx-auto max-w-6xl">
          <header className="mb-7 flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="mb-2 text-xs font-semibold uppercase tracking-[0.22em] text-orange-700">STARLINE / EVENTS</p>
              <h1 className="text-2xl font-semibold tracking-tight text-slate-900 md:text-3xl">starline星序</h1>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">
                录入并核验公开信息，设置开票邮件提醒。尚未公布的内容会明确标记为“待公布”。
              </p>
            </div>
            <div className="flex items-center gap-2 rounded-full border border-orange-100 bg-orange-50 px-4 py-2 text-xs text-orange-800">
              <Bell className="h-4 w-4" />邮箱提醒
            </div>
          </header>

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
                    value={infoStatus}
                    onChange={(e) => setInfoStatus(e.target.value as StarlineInfoStatus)}
                  >
                    <option value="ANNOUNCED">已公布，可创建提醒</option>
                    <option value="TO_BE_ANNOUNCED">待公布，仅记录状态</option>
                  </select>
                </label>

                {infoStatus === "ANNOUNCED" ? (
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
                      <span>我已核对来源内容与艺人、活动及开票时间一致。</span>
                    </label>
                    <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                      开票时间
                      <Input required type="datetime-local" value={ticketTime} onChange={(e) => setTicketTime(e.target.value)} />
                    </label>
                    <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                      提醒发送时间
                      <Input required type="datetime-local" value={remindAt} onChange={(e) => setRemindAt(e.target.value)} />
                      <span className="block text-xs font-normal text-slate-400">按北京时间执行；须早于或等于开票时间。</span>
                    </label>
                    <label className="block space-y-1.5 text-sm font-medium text-slate-700">
                      接收提醒的邮箱
                      <Input required type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@example.com" />
                    </label>
                  </div>
                ) : (
                  <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                    <strong>待公布</strong>
                    <p className="mt-1 text-xs leading-5 text-amber-800">当前只保存状态，不会创建提醒或推测开票时间。</p>
                  </div>
                )}

                <Button type="submit" className="w-full rounded-xl bg-orange-600 hover:bg-orange-700" disabled={saving}>
                  {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                  {infoStatus === "ANNOUNCED" ? "确认来源并创建提醒" : "记录为待公布"}
                </Button>
              </form>
              <p className="mt-4 text-[11px] leading-5 text-slate-400">
                当前版本由用户录入并确认信息来源；真实信息检索 Agent 将在后续迭代接入。邮件投递需先在服务端配置 SMTP，默认关闭。
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
                  <p className="mt-1 text-sm text-slate-400">在左侧录入已核验信息，或标记为待公布。</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {events.map((item) => (
                    <article key={item.id} className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                          <h3 className="text-base font-semibold text-slate-900">{item.artistName}</h3>
                          <p className="mt-1 text-sm text-slate-600">{item.eventName || "演出/开票信息"}</p>
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {item.infoStatus === "TO_BE_ANNOUNCED" ? (
                            <span className="rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-800">待公布</span>
                          ) : (
                            <span className="rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700">来源已确认</span>
                          )}
                          <span className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600">{reminderLabels[item.reminderStatus]}</span>
                        </div>
                      </div>
                      {item.infoStatus === "ANNOUNCED" ? (
                        <div className="mt-4 grid gap-3 border-t border-slate-100 pt-4 text-sm sm:grid-cols-2">
                          <p className="flex items-start gap-2 text-slate-600"><Ticket className="mt-0.5 h-4 w-4 shrink-0 text-orange-600" /><span>开票：{formatDate(item.ticketTime)}</span></p>
                          <p className="flex items-start gap-2 text-slate-600"><Clock3 className="mt-0.5 h-4 w-4 shrink-0 text-orange-600" /><span>提醒：{formatDate(item.remindAt)}</span></p>
                          <p className="flex items-start gap-2 break-all text-slate-600"><Bell className="mt-0.5 h-4 w-4 shrink-0 text-orange-600" /><span>邮箱：{item.email}</span></p>
                          {item.sourceUrl ? (
                            <a className="flex items-start gap-2 break-all text-orange-700 hover:underline" href={item.sourceUrl} target="_blank" rel="noreferrer">
                              <ExternalLink className="mt-0.5 h-4 w-4 shrink-0" /><span>{item.sourceName} · 查看来源</span>
                            </a>
                          ) : null}
                        </div>
                      ) : (
                        <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">暂未获得已确认的开票信息，状态：待公布。</p>
                      )}
                      <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-3">
                        <span className="text-xs text-slate-400">创建于 {formatDate(item.createdTime)}</span>
                        {item.reminderStatus === "PENDING" ? (
                          <Button type="button" variant="outline" size="sm" className="rounded-xl text-rose-700 hover:bg-rose-50" onClick={() => cancel(item.id)} disabled={cancelingId === item.id}>
                            {cancelingId === item.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <XCircle className="h-3.5 w-3.5" />}取消提醒
                          </Button>
                        ) : item.reminderStatus === "SENT" ? (
                          <span className="inline-flex items-center gap-1 text-xs text-emerald-700"><CheckCircle2 className="h-3.5 w-3.5" />已发出提醒</span>
                        ) : null}
                      </div>
                    </article>
                  ))}
                </div>
              )}
            </section>
          </div>
        </div>
      </div>
    </AgentLayout>
  );
}