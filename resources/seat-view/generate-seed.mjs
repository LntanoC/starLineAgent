// 显式生成幂等演示种子，重复迁移不覆盖管理员已校对的演出。
import { readFileSync, writeFileSync } from "node:fs";
const catalog = JSON.parse(readFileSync(new URL("./demo-catalog.json", import.meta.url), "utf8"));
// 上游生成器在字母表用尽后产生标点/控制字符编号；转为稳定的十六进制后缀。
for (const document of catalog) {
  for (const [index, section] of document.show.sections.entries()) {
    if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,99}$/.test(section.code)) {
      section.code = section.code.replace(/[^A-Za-z0-9-]/g, (char) => "u" + char.codePointAt(0).toString(16));
      section.label = ["", "内场", "下层", "上层"][section.level] + "示意分区" + (index + 1);
    }
  }
}
writeFileSync(new URL("./demo-catalog.json", import.meta.url), JSON.stringify(catalog, null, 2) + "\n");
const literal = (value) => "'" + value.replaceAll("'", "''") + "'";
const statements = ["-- venue-seats MIT 示例，只供演示；绝不覆盖已存在的场馆和演出。", "BEGIN;"];
for (const document of catalog) {
  statements.push(`INSERT INTO t_seat_view_venue(id, document) VALUES (${literal(document.venue.id)}, ${literal(JSON.stringify(document.venue))}::jsonb) ON CONFLICT DO NOTHING;`);
  statements.push(`INSERT INTO t_seat_view_show(id, venue_id, document) VALUES (${literal(document.show.id)}, ${literal(document.venue.id)}, ${literal(JSON.stringify(document.show))}::jsonb) ON CONFLICT DO NOTHING;`);
}
statements.push("COMMIT;", "");
writeFileSync(new URL("../database/upgrades/v1.1.0/261104_seat_view_demo.sql", import.meta.url), statements.join("\n"));
console.log(`Generated ${catalog.length} venue/show demo snapshots`);
