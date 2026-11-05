import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { createStarlineEvent } from "@/services/starlineService";
import { getErrorMessage } from "@/utils/error";

import { STAGE, parseStarlineEventSearchResult } from "@/lib/eventSearch";
import type { EventCardData, EventSearchData } from "@/lib/eventSearch";
import { api } from "@/services/api";
export { parseStarlineEventSearchResult };

export function StarlineEventCards({ data }: { data: EventSearchData }) {
  const [pages, setPages] = useState<EventSearchData[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const current = pages.at(-1) ?? data;
  const items = [data, ...pages].flatMap(page => page.items);
  const byCity = new Map<string, EventCardData[]>();
  for (const item of items) {
    const key = item.city || "城市待核实";
    byCity.set(key, [...(byCity.get(key) ?? []), item]);
  }
  const loadMore = async () => {
    if (loading || !current.nextCursor) return;
    setLoading(true); setLoadError(null);
    try {
      const response = await api.post<unknown, unknown>("/starline/event-search", {
        artist: data.artist, city: data.city ?? undefined, window: data.window ?? "year",
        includeEnded: data.includeEnded ?? false, pageSize: data.pageSize ?? 20, cursor: current.nextCursor
      });
      const next = parseStarlineEventSearchResult(JSON.stringify(response));
      if (!next) throw new Error("演出结果格式变化，请重新查询");
      setPages(previous => [...previous, next]);
    } catch (error) { setLoadError(getErrorMessage(error, "加载失败，分页可能已过期，请重新查询")); }
    finally { setLoading(false); }
  };

  return (
    <div className="space-y-3">
      <div className="rounded-xl border border-rule bg-paper p-3 text-xs leading-6 text-muted-foreground">
        <p><span className="mr-2 rounded-full border border-warning-rule bg-warning-wash px-2 py-1 text-warning">
          {current.coverage.completeness === "UNKNOWN" ? "覆盖未知" : current.coverage.completeness === "PARTIAL" ? "覆盖受限" : "按预算检索"}
        </span>本次检索到 · 已展示 {items.length} / {current.coverage.merged} 条</p>
        <p>窗口：{current.coverage.window} · {current.coverage.queries.length} 个查询 · {current.coverage.sources?.length ?? "—"} 个已配置来源</p>
        {current.coverage.providerPageLimit && <p>搜索提供方存在单页或分页预算上限</p>}
        {current.coverage.sources?.map(source => <span key={source.domain} className="mr-3 inline-block">{source.platform} · {source.status === "OK" ? "检索有命中" : source.status === "QUERY_FAILED" ? "查询失败" : source.status === "QUERY_COMPLETED_NO_MATCH" ? "本次未命中" : "尚未观察到"}</span>)}
      </div>
      {[...byCity].map(([city, events]) => <section key={city} className="space-y-2" aria-label={city + "演出"}>
        <h4 className="px-1 text-sm font-medium">{city} · {events.length} 条</h4>
        {events.map((item, index) => <EventCard key={[item.eventDate, item.sourceUrl, index].join("-")} item={item} />)}
      </section>)}
      {current.notes.map((note, index) => (
        <p key={"note-" + index} className="px-1 text-xs leading-5 text-muted-foreground">{note}</p>
      ))}
      {loadError && <p role="alert" className="text-sm text-error">{loadError}</p>}
      {current.nextCursor && <button type="button" onClick={() => void loadMore()} disabled={loading}
        className="rounded-lg border border-rule px-4 py-2 text-sm disabled:opacity-60">{loading ? "加载中…" : "加载更多（还有 " + current.coverage.truncated + " 条）"}</button>}
    </div>
  );
}

function EventCard({ item }: { item: EventCardData }) {
  const navigate = useNavigate();
  const [saving, setSaving] = useState(false);
  const stage = STAGE[item.stage];
  const saleTime = parseShanghaiDateTime(item.saleStartTime);
  const futureSaleTime = saleTime != null && saleTime.getTime() > Date.now();
  const canAddReminder = Boolean(
    item.saleStartTime &&
    item.stage !== "ENDED" &&
    !item.isStale &&
    !item.conflict &&
    !item.dateUnknown &&
    !item.cityUnknown &&
    futureSaleTime
  );

  const addReminder = async () => {
    if (!canAddReminder || saving) return;
    setSaving(true);
    try {
      await createStarlineEvent({
        artistName: item.artistName,
        eventName: item.title,
        sourceName: item.sourceName,
        sourceUrl: item.sourceUrl,
        sourceConfirmed: true,
        ticketTime: toApiDateTime(item.saleStartTime!)
      });
      toast.success("已添加。去“我的提醒”页复制订阅链接，加到手机日历后生效。", {
        action: {
          label: "去我的提醒",
          onClick: () => navigate("/starline/reminders")
        }
      });
    } catch (error) {
      toast.error(getErrorMessage(error, "添加提醒失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <article className="rounded-xl border border-rule bg-paper p-4 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h3 className="min-w-0 flex-1 text-sm font-semibold leading-6 text-ink">{item.title}</h3>
        <span className={"shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium " + stage.className}>
          {stage.label}
        </span>
      </div>

      {item.daysToSale != null && item.stage === "ON_SHELF" ? (
        <p className="mt-2 text-sm font-semibold text-warning">{countdown(item.daysToSale)}</p>
      ) : item.daysToSale != null && item.stage === "ON_SALE" && item.daysToSale <= 0 ? (
        <p className="mt-2 text-sm text-success">
          {item.daysToSale === 0 ? "今天开票" : "已开票 " + Math.abs(item.daysToSale) + " 天"}
        </p>
      ) : null}

      <p className="mt-3 text-sm text-muted-foreground">
        {item.eventDate || "日期待核实"}{item.city ? " · " + item.city : ""}{item.cityUnknown ? "（城市待核实）" : ""}
      </p>
      {(item.venueName || item.showStartTime) && <p className="mt-2 text-sm text-muted-foreground">
        {item.venueName || "场馆待核实"} / 开场 {item.showStartTime ? item.showStartTime.replace("T", " ") : "待核实"}（北京时间）
      </p>}

      {item.approvedNote ? (
        <p className="mt-2 text-sm text-info">
          {item.approvedUrl ? (
            <a href={safeExternalUrl(item.approvedUrl)} target="_blank" rel="noreferrer" className="underline underline-offset-2">
              {item.approvedNote} · 查看公示
            </a>
          ) : item.approvedNote}
        </p>
      ) : null}

      {item.isStale ? (
        <p className="mt-2 rounded-lg bg-warning-wash px-3 py-2 text-xs text-warning">
          信息时效无法确认或已过期，请打开来源重新核验。
        </p>
      ) : null}
      {item.conflict ? (
        <div className="mt-2 rounded-lg bg-error-wash px-3 py-2 text-xs text-error">
          <p>票务来源的状态或开票时间不一致，暂不能据此添加提醒：</p>
          <ul className="mt-1 space-y-1">
            {item.sourceEvidence.map((source, index) => (
              <li key={source.sourceUrl + "-" + index}>
                <a href={safeExternalUrl(source.sourceUrl)} target="_blank" rel="noreferrer" className="underline">
                  {source.sourceName} · {STAGE[source.stage]?.label ?? source.stage}
                </a>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="mt-3 flex flex-wrap items-center justify-between gap-3 border-t border-rule pt-3">
        <a href={safeExternalUrl(item.sourceUrl)} target="_blank" rel="noreferrer" className="text-sm text-accent underline underline-offset-2">
          来源：{item.sourceName}
        </a>
        <div className="flex items-center gap-3">
          {canAddReminder ? (
            <>
            <button
              type="button"
              disabled={saving}
              onClick={() => void addReminder()}
              className="rounded-lg bg-accent px-3 py-2 text-sm font-medium text-white transition hover:bg-accent disabled:cursor-wait disabled:opacity-60 md:hidden"
            >
              {saving ? "添加中…" : "添加提醒"}
            </button>
            <span className="hidden text-xs text-muted-foreground md:inline">
              请在手机端添加日历提醒
            </span>
            </>
          ) : item.stage !== "ENDED" && !item.saleStartTime ? (
            <span className="text-xs text-muted-foreground">暂无准确开票时间，无法设置提醒</span>
          ) : item.isStale || item.conflict ? (
            <span className="text-xs text-muted-foreground">核验来源后再添加提醒</span>
          ) : item.saleStartTime && !futureSaleTime ? (
            <span className="text-xs text-muted-foreground">开票时间已过，无法添加提醒</span>
          ) : null}
        </div>
      </div>
    </article>
  );
}

function countdown(days: number): string {
  if (days > 0) return "还有 " + days + " 天开票";
  if (days === 0) return "今天开票";
  return "开票日已过 " + Math.abs(days) + " 天";
}

function parseShanghaiDateTime(value: string | null): Date | null {
  if (!value) return null;
  const normalized = value.length === 16 ? value + ":00" : value;
  const shanghaiIso = normalized.endsWith("Z") || /[+-]\d{2}:\d{2}$/.test(normalized)
    ? normalized
    : normalized + "+08:00";
  const date = new Date(shanghaiIso);
  return Number.isNaN(date.getTime()) ? null : date;
}

function toApiDateTime(value: string): string {
  return value.length === 16 ? value + ":00" : value;
}

function safeExternalUrl(value: string): string {
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : "#";
  } catch {
    return "#";
  }
}
