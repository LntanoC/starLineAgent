// Offline comparison of saved discovery snapshots with manually verified public evidence.
import { readFileSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";
export function evaluate(entry) {
  const { result, reference = [] } = entry;
  assert(result?.coverage && Array.isArray(result.items), "Expected saved search_artist_events JSON");
  const c = result.coverage;
  assert.equal(c.returned, result.items.length);
  assert(c.rawResults >= c.afterSource && c.afterSource >= c.afterRelevance && c.afterRelevance >= c.afterDate);
  for (const item of result.items) {
    assert(/^https?:\/\//.test(item.sourceUrl));
    if (!item.eventDate) assert.equal(item.dateUnknown, true);
  }
  const missing = reference.filter(event => !result.items.some(item => item.city === event.city && item.eventDate === event.eventDate));
  return { artist: entry.artist ?? result.artist, observedAt: entry.observedAt ?? null,
    cities: [...new Set(result.items.map(item => item.city).filter(Boolean))], returned: result.items.length,
    merged: c.merged, remaining: c.truncated, completeness: c.completeness, unknownDates: result.items.filter(item => item.dateUnknown).length,
    failures: c.failures ?? [], missing: missing.map(event => ({ city: event.city, eventDate: event.eventDate, sourceUrl: event.sourceUrl })),
    note: "差异仅相对于人工公开证据；还有未加载分页时不能断定遗漏。" };
}
if (process.argv.includes("--self-test")) {
  const result = { artist: "fixture", items: [{ city: "杭州", eventDate: null, dateUnknown: true, sourceUrl: "https://example.com/1" }],
    coverage: { rawResults: 2, afterSource: 2, afterRelevance: 1, afterDate: 1, merged: 1, returned: 1, truncated: 0, completeness: "PARTIAL" } };
  assert.equal(evaluate({ result, reference: [{ city: "无锡", eventDate: "2026-12-20" }] }).missing.length, 1);
  console.log("Coverage evaluator self-test passed");
} else if (process.argv[2]) {
  const input = JSON.parse(readFileSync(process.argv[2], "utf8"));
  const report = { evaluatedAt: new Date().toISOString(), entries: (Array.isArray(input) ? input : [input]).map(evaluate) };
  const json = JSON.stringify(report, null, 2) + "\n";
  if (process.argv[3]) writeFileSync(process.argv[3], json); else process.stdout.write(json);
} else { console.log("Usage: node scripts/evaluate-event-coverage.mjs saved-snapshots.json [report.json] | --self-test"); }
