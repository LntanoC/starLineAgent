import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { createStarlineEvent } from "@/services/starlineService";
import { getErrorMessage } from "@/utils/error";

type EventStage = "APPROVED" | "ON_SHELF" | "ON_SALE" | "SOLD_OUT" | "ENDED";
type SourceTier = "GOV" | "TICKETING";

interface SourceEvidence {
  sourceName: string;
  tier: SourceTier;
  sourceUrl: string;
  stage: EventStage;
}

interface EventCardData {
  artistName: string;
  title: string;
  stage: EventStage;
  tier: SourceTier;
  city: string | null;
  eventDate: string | null;
  saleStartTime: string | null;
  daysToSale: number | null;
  canBuyTicket: boolean;
  sourceName: string;
  sourceUrl: string;
  approvedNote: string | null;
  approvedUrl: string | null;
  isStale: boolean;
  conflict: boolean;
  sourceEvidence: SourceEvidence[];
}

interface EventSearchData {
  artist: string;
  total: number;
  items: EventCardData[];
  notes: string[];
}

const STAGE: Record<EventStage, { label: string; className: string }> = {
  APPROVED: { label: "已获批", className: "border-sky-200 bg-sky-50 text-sky-700" },
  ON_SHELF: { label: "已上架待开票", className: "border-amber-200 bg-amber-50 text-amber-800" },
  ON_SALE: { label: "已开票", className: "border-emerald-200 bg-emerald-50 text-emerald-700" },
  SOLD_OUT: { label: "已售罄", className: "border-slate-200 bg-slate-100 text-slate-600" },
  ENDED: { label: "已结束", className: "border-slate-200 bg-slate-100 text-slate-500" }
};

const VALID_STAGES = new Set<EventStage>(["APPROVED", "ON_SHELF", "ON_SALE", "SOLD_OUT", "ENDED"]);
const VALID_TIERS = new Set<SourceTier>(["GOV", "TICKETING"]);

/** Return null for truncated or incompatible historical tool output; the caller then shows its raw trace. */
export function parseStarlineEventSearchResult(raw: string): EventSearchData | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object") return null;
    const data = value as Partial<EventSearchData>;
    if (
      typeof data.artist !== "string" ||
      typeof data.total !== "number" ||
      !Array.isArray(data.items) ||
      !Array.isArray(data.notes) ||
      !data.notes.every((note) => typeof note === "string") ||
      !data.items.every(isEventCardData)
    ) {
      return null;
    }
    return data as EventSearchData;
  } catch {
    return null;
  }
}

function isEventCardData(value: unknown): value is EventCardData {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<EventCardData>;
  return (
    typeof item.artistName === "string" &&
    typeof item.title === "string" &&
    typeof item.stage === "string" &&
    VALID_STAGES.has(item.stage as EventStage) &&
    typeof item.tier === "string" &&
    VALID_TIERS.has(item.tier as SourceTier) &&
    typeof item.sourceName === "string" &&
    typeof item.sourceUrl === "string" &&
    typeof item.isStale === "boolean" &&
    typeof item.conflict === "boolean" &&
    Array.isArray(item.sourceEvidence)
  );
}

export function StarlineEventCards({ data }: { data: EventSearchData }) {
  if (data.items.length === 0) {
    return (
      <div className="space-y-1 rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-600">
        {data.notes.map((note, index) => <p key={index}>{note}</p>)}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {data.items.map((item, index) => (
        <EventCard
          key={[item.eventDate || "date-tbd", item.city || "city-tbd", item.sourceUrl, index].join("-")}
          item={item}
        />
      ))}
      {data.notes.map((note, index) => (
        <p key={"note-" + index} className="px-1 text-xs leading-5 text-slate-500">{note}</p>
      ))}
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
    <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h3 className="min-w-0 flex-1 text-sm font-semibold leading-6 text-slate-900">{item.title}</h3>
        <span className={"shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium " + stage.className}>
          {stage.label}
        </span>
      </div>

      {item.daysToSale != null && item.stage === "ON_SHELF" ? (
        <p className="mt-2 text-sm font-semibold text-amber-700">{countdown(item.daysToSale)}</p>
      ) : item.daysToSale != null && item.stage === "ON_SALE" && item.daysToSale <= 0 ? (
        <p className="mt-2 text-sm text-emerald-700">
          {item.daysToSale === 0 ? "今天开票" : "已开票 " + Math.abs(item.daysToSale) + " 天"}
        </p>
      ) : null}

      <p className="mt-3 text-sm text-slate-600">
        {item.eventDate || "演出日期待公布"}{item.city ? " · " + item.city : ""}
      </p>

      {item.approvedNote ? (
        <p className="mt-2 text-sm text-sky-800">
          {item.approvedUrl ? (
            <a href={safeExternalUrl(item.approvedUrl)} target="_blank" rel="noreferrer" className="underline underline-offset-2">
              {item.approvedNote} · 查看公示
            </a>
          ) : item.approvedNote}
        </p>
      ) : null}

      {item.isStale ? (
        <p className="mt-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">
          信息时效无法确认或已过期，请打开来源重新核验。
        </p>
      ) : null}
      {item.conflict ? (
        <div className="mt-2 rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-800">
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

      <div className="mt-3 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-3">
        <a href={safeExternalUrl(item.sourceUrl)} target="_blank" rel="noreferrer" className="text-sm text-indigo-700 underline underline-offset-2">
          来源：{item.sourceName}
        </a>
        <div className="flex items-center gap-3">
          {canAddReminder ? (
            <>
            <button
              type="button"
              disabled={saving}
              onClick={() => void addReminder()}
              className="rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white transition hover:bg-indigo-700 disabled:cursor-wait disabled:opacity-60 md:hidden"
            >
              {saving ? "添加中…" : "添加提醒"}
            </button>
            <span className="hidden text-xs text-slate-500 md:inline">
              请在手机端添加日历提醒
            </span>
            </>
          ) : item.stage !== "ENDED" && !item.saleStartTime ? (
            <span className="text-xs text-slate-500">暂无准确开票时间，无法设置提醒</span>
          ) : item.isStale || item.conflict ? (
            <span className="text-xs text-slate-500">核验来源后再添加提醒</span>
          ) : item.saleStartTime && !futureSaleTime ? (
            <span className="text-xs text-slate-500">开票时间已过，无法添加提醒</span>
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
