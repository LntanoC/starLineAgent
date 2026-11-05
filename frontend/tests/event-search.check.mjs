import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const out = resolve(root, ".output/event-contract");
mkdirSync(out, { recursive: true });
execFileSync(process.execPath, [resolve(root, "node_modules/typescript/bin/tsc"), "src/lib/eventSearch.ts", "--target", "ES2020", "--module", "ES2020", "--outDir", out, "--skipLibCheck"], { cwd: root, stdio: "inherit" });
const { parseStarlineEventSearchResult: parse } = await import(pathToFileURL(resolve(out, "eventSearch.js")).href);
const raw = readFileSync(resolve(root, "../mcp-server/target/event-search-ui-fixture.json"), "utf8");
const data = parse(raw); assert(data, "Java event-search contract should parse");
assert.equal(data.coverage.returned, data.items.length);
assert(data.nextCursor); assert(data.notes.some(note => note.includes("还有 1 条")));
for (const mutate of [d => delete d.coverage, d => d.coverage.returned++, d => d.coverage.afterSource = d.coverage.rawResults + 1, d => d.items[0].sourceUrl = 12]) {
  const changed = JSON.parse(raw); mutate(changed); assert.equal(parse(JSON.stringify(changed)), null);
}
assert.equal(parse("{bad json"), null);
console.log("Event search Java/TS contract, coverage counts, cursor and historical fallback passed");
