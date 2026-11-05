import { z } from "zod";

const moment = z.string().nullable();
const amount = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).nullable();
const warning = z.object({ code: z.string(), message: z.string() });
const evidence = z.object({ provider: z.string(), url: z.string().nullable(), note: z.string() }).passthrough();
const place = z.object({ name: z.string(), address: z.string(), city: z.string().nullable() }).passthrough();
const costItem = z.object({
  category: z.string(), label: z.string(), minMinor: amount, maxMinor: amount, currency: z.literal("CNY"),
  status: z.enum(["QUOTED", "REFERENCE", "ESTIMATED", "UNKNOWN"]), scope: z.enum(["PER_PERSON", "PER_PARTY", "PER_VEHICLE"]),
  source: z.string().nullable(), note: z.string().nullable()
}).superRefine((item, ctx) => {
  if (item.status === "UNKNOWN" ? item.minMinor !== null || item.maxMinor !== null
    : item.minMinor === null || item.maxMinor === null || item.maxMinor < item.minMinor || !item.source) {
    ctx.addIssue({ code: "custom", message: "Invalid cost provenance or unknown amount" });
  }
});
const segment = z.object({
  mode: z.string(), from: place, to: place, departAt: moment, arriveAt: moment,
  durationMinutes: z.number().int().nonnegative(), serviceNo: z.string().nullable(),
  scheduled: z.boolean(), source: z.string()
}).passthrough();
const feasibility = z.enum(["FEASIBLE", "TIGHT", "INFEASIBLE", "UNKNOWN"]);
const cost = z.object({ items: z.array(costItem), knownSubtotalMinor: z.number().int().nonnegative(),
  minTotalMinor: amount, maxTotalMinor: amount, perPersonMinMinor: amount, perPersonMaxMinor: amount,
  unknownCategories: z.array(z.string()), currency: z.literal("CNY"), partySize: z.number().int().min(1).max(20) });
export const journeySchema = z.object({
  schemaVersion: z.literal("trip-plan.v3"), status: z.enum(["OK", "NEEDS_CLARIFICATION", "PARTIAL", "FAILED"]),
  request: z.object({ partySize: z.number().int().min(1).max(20), budgetMinor: amount }).passthrough().nullable(),
  event: z.object({ artist: z.string(), tour: z.string().nullable(), city: z.string(), venue: place.nullable(),
    showStart: moment, showEnd: moment, evidence: z.array(evidence) }).passthrough().nullable(),
  options: z.array(z.object({ id: z.string(), label: z.string(), modes: z.array(z.string()),
    segments: z.array(segment), travelMinutes: z.number().int().nonnegative(), totalMinutes: z.number().int(),
    departOriginAt: moment, arriveVenueAt: moment, arriveVenueBy: moment, feasibility, slackMinutes: z.number().int().nullable(),
    cost, confidence: z.enum(["HIGH", "MEDIUM", "LOW"]), transfers: z.number().int().nonnegative(),
    returnJourney: z.object({ status: z.string(), segments: z.array(segment), departAt: moment, arriveAt: moment, feasibility, warnings: z.array(warning) }),
    assumptions: z.array(z.string()), warnings: z.array(warning),
    evidence: z.array(evidence)
  })),
  recommendedOptionId: z.string().nullable(), recommendReason: z.string().nullable(),
  questions: z.array(z.object({ field: z.string(), question: z.string(), options: z.array(z.string()), required: z.boolean() })).max(2),
  warnings: z.array(warning), generatedAt: z.string()
});
export type JourneyPlan = z.infer<typeof journeySchema>;
export type JourneyOption = JourneyPlan["options"][number];
export function parseJourney(raw: string): JourneyPlan | null {
  try { const result = journeySchema.safeParse(JSON.parse(raw)); return result.success ? result.data : null; }
  catch { return null; }
}
export function moneyRange(min: number | null, max: number | null): string {
  if (min === null || max === null) return "待核实";
  const format = (amount: number) => new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY", minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(amount / 100);
  return min === max ? format(min) : format(min) + "–" + format(max);
}
export function available(option: JourneyOption): boolean {
  return ["FEASIBLE", "TIGHT"].includes(option.feasibility) && (option.returnJourney.status === "NOT_REQUESTED"
    || ["FEASIBLE", "TIGHT"].includes(option.returnJourney.feasibility));
}
export function selectJourney(options: JourneyOption[], kind: "fastest" | "cheapest" | "fewest"): JourneyOption | undefined {
  const candidates = options.filter(available).filter((option) => kind !== "cheapest" || option.cost.unknownCategories.length === 0 && option.cost.maxTotalMinor !== null);
  return [...candidates].sort((a, b) => kind === "fastest" ? a.travelMinutes - b.travelMinutes
    : kind === "fewest" ? a.transfers - b.transfers || a.travelMinutes - b.travelMinutes
    : a.cost.maxTotalMinor! - b.cost.maxTotalMinor!)[0];
}
