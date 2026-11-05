export type EventStage = "APPROVED" | "ON_SHELF" | "ON_SALE" | "SOLD_OUT" | "ENDED";
type SourceTier = "GOV" | "TICKETING";

interface SourceEvidence {
  sourceName: string;
  tier: SourceTier;
  sourceUrl: string;
  stage: EventStage;
}

export interface EventCardData {
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
  venueName?: string | null;
  showStartTime?: string | null;
  venueSource?: string | null;
  timeSource?: string | null;
  dateUnknown?: boolean;
  cityUnknown?: boolean;
  noise?: boolean;
}

export interface EventSearchData {
  artist: string;
  total: number;
  items: EventCardData[];
  notes: string[];
  city?: string | null;
  window?: "recent" | "year" | "all";
  includeEnded?: boolean;
  pageSize?: number;
  nextCursor?: string | null;
  coverage: {
    queries: string[];
    rawResults: number;
    afterSource: number;
    afterRelevance: number;
    afterDate: number;
    merged: number;
    returned: number;
    truncated: number;
    completeness: "BEST_EFFORT" | "PARTIAL" | "UNKNOWN";
    window: string;
    providerPageLimit: boolean;
    sources?: { platform: string; domain: string; status: string; raw: number; accepted: number }[];
    failures?: string[];
    filtered?: Record<string, number>;
  };
}

export const STAGE: Record<EventStage, { label: string; className: string }> = {
  APPROVED: { label: "已获批", className: "border-info-rule bg-info-wash text-info" },
  ON_SHELF: { label: "已上架待开票", className: "border-warning-rule bg-warning-wash text-warning" },
  ON_SALE: { label: "已开票", className: "border-success-rule bg-success-wash text-success" },
  SOLD_OUT: { label: "已售罄", className: "border-rule bg-paper text-muted-foreground" },
  ENDED: { label: "已结束", className: "border-rule bg-paper text-muted-foreground" }
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
      !data.items.every(isEventCardData) ||
      !validCoverage(data.coverage) ||
      data.coverage.returned !== data.items.length ||
      (data.nextCursor != null && typeof data.nextCursor !== "string")
    ) {
      return null;
    }
    return data as EventSearchData;
  } catch {
    return null;
  }
}

function validCoverage(value: unknown): value is EventSearchData["coverage"] {
  if (!value || typeof value !== "object") return false;
  const c = value as EventSearchData["coverage"];
  return Array.isArray(c.queries) && c.queries.every(q => typeof q === "string") &&
    [c.rawResults, c.afterSource, c.afterRelevance, c.afterDate, c.merged, c.returned, c.truncated].every(n => Number.isInteger(n) && n >= 0) &&
    c.rawResults >= c.afterSource && c.afterSource >= c.afterRelevance && c.afterRelevance >= c.afterDate && c.merged >= c.returned &&
    ["BEST_EFFORT", "PARTIAL", "UNKNOWN"].includes(c.completeness) && typeof c.window === "string" &&
    (c.sources == null || (Array.isArray(c.sources) && c.sources.every(s => s && typeof s.platform === "string" && typeof s.domain === "string" && typeof s.status === "string"))) &&
    (c.failures == null || (Array.isArray(c.failures) && c.failures.every(f => typeof f === "string")));
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
