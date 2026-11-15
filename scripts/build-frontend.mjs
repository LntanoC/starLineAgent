import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const vite = join(root, "frontend", "node_modules", "vite", "bin", "vite.js");
const env = { ...process.env };
let buildTemp;

try {
  if (process.platform === "win32") {
    // 工作区的低完整性 esbuild 需要能删除 Node 在同一临时目录创建的文件。
    buildTemp = mkdtempSync(join(tmpdir(), "starline-frontend-build-"));
    const result = spawnSync("icacls", [buildTemp, "/setintegritylevel", "(OI)(CI)L"], { stdio: "inherit" });
    if (result.error || result.status !== 0) throw result.error ?? new Error("无法设置构建临时目录的完整性级别");
    env.TEMP = buildTemp;
    env.TMP = buildTemp;
  }
  const result = spawnSync(process.execPath, [vite, "build", ...process.argv.slice(2)], { env, stdio: "inherit" });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  if (buildTemp) {
    try { rmSync(buildTemp, { recursive: true, force: true }); }
    catch (error) { console.error("无法清理构建临时目录", error); process.exitCode = 1; }
  }
}
