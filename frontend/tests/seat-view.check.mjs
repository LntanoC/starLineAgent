import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const out = resolve(root, ".output/seat-view");
mkdirSync(out, { recursive: true });
execFileSync(process.execPath, [resolve(root, "node_modules/typescript/bin/tsc"), "src/lib/seatView.ts", "--target", "ES2020", "--module", "ES2020", "--outDir", out, "--skipLibCheck"], { cwd: root, stdio: "inherit" });
const { sectionPosition, percentage, mapBounds, resolveDocument, seatSearch } = await import(pathToFileURL(resolve(out, "seatView.js")).href);
const catalog = JSON.parse(readFileSync(resolve(root, "../resources/seat-view/demo-catalog.json"), "utf8"));
assert.equal(catalog.length, 5);
assert.equal(percentage("NaN"), 50);
assert.equal(percentage("Infinity"), 50);
assert.equal(percentage("-2"), 0);
assert.equal(percentage("201"), 100);
assert.equal(percentage(null), 50);
let positions = 0;
for (const doc of catalog) {
  assert.equal(doc.show.provenance, "DEMO");
  const bounds = mapBounds(doc);
  assert(bounds.every(Number.isFinite));
  for (const section of doc.show.sections) {
    assert.equal(section.ring.length, 4);
    assert.deepEqual(sectionPosition(section, 0, 0), section.ring[section.frontEdge]);
    assert.deepEqual(sectionPosition(section, 0, 100), section.ring[(section.frontEdge + 1) % 4]);
    for (const depth of [0, 20, 50, 80, 100]) for (const lateral of [0, 20, 50, 80, 100]) {
      const p = sectionPosition(section, depth, lateral);
      const crosses = section.ring.map((a, i) => {
        const b = section.ring[(i + 1) % 4];
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
      });
      assert(crosses.every((v) => v >= -1e-6) || crosses.every((v) => v <= 1e-6), "position outside " + section.code);
      assert(p[0] >= bounds[0] && p[0] <= bounds[0] + bounds[2]);
      assert(p[1] >= bounds[1] && p[1] <= bounds[1] + bounds[3]);
      positions++;
    }
  }
  const section = doc.show.sections[0];
  assert.equal(resolveDocument(catalog, seatSearch(doc, section.code, 40, 60)), doc);
}
assert.equal(resolveDocument(catalog, new URLSearchParams("show=unknown")), undefined);
assert.equal(resolveDocument(catalog, new URLSearchParams("venue=unknown")), undefined);
assert.equal(resolveDocument(catalog, new URLSearchParams({ venue: catalog[1].venue.id, show: catalog[0].show.id })), undefined);
console.log(`seat-view: 5 venues, ${positions} positions inside polygons; deep links and invalid parameters passed`);
