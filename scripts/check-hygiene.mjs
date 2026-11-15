#!/usr/bin/env node
/**
 * 仓库卫生检查（Repo Hygiene Gate）
 *
 * 把「不应该进仓库的东西」变成可执行判定，供三处调用：
 *   1. 开发者 / AI agent 在声明完成前手动执行
 *   2. .githooks/pre-commit 提交前拦截
 *   3. .github/workflows/hygiene.yml 合并前兜底
 *
 * 用法：
 *   node scripts/check-hygiene.mjs                    检查，有新增违规则退出码 1
 *   node scripts/check-hygiene.mjs --update-baseline  把当前违规写入基线（仅用于收编存量债）
 *   node scripts/check-hygiene.mjs --verbose          打印每条违规的机器可读 key
 *
 * 基线机制：仓库历史上存在一批已知违规（见 .hygiene-baseline.json）。
 * 它们不会让检查失败，但**不得增加**。修掉一条就从基线里删一条。
 */

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  closeSync,
  existsSync,
  openSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, normalize, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const BASELINE_FILE = join(ROOT, ".hygiene-baseline.json");
const ARGS = new Set(process.argv.slice(2));
const VERBOSE = ARGS.has("--verbose");

/** 构建产物、本地快照等逐条禁止入库的路径规则 */
const FORBIDDEN_PATHS = [
  { re: /\.(jar|war|tar\.gz|tgz|zip|log)$/i, why: "构建产物 / 日志不应入库" },
  { re: /(^|\/)(target|dist|\.output|node_modules)\//, why: "构建产物目录不应入库" },
  { re: /\.tsbuildinfo$/, why: "增量编译缓存不应入库" },
  {
    re: /(^|\/)vite\.config\.(js|d\.ts)$/,
    why: "vite.config.ts 的 tsc 产物；Vite 优先加载 .js，会静默遮蔽 .ts 的修改",
  },
  { re: /(^|\/)\.local-backups\//, why: "本地回滚快照不属于仓库，git 本身就是备份" },
  { re: /^docs\/server-operations(\.md|\/)/, why: "服务器操作记录 / 上线快照不入库" },
  { re: /(^|\/)\.workbuddy-ai\//, why: "AI 工具本地状态" },
  { re: /(^|\/)\.env$/, why: "含环境变量与凭据，应改为 .env.example" },
];

/** 允许存在零字节文件的文件名（真正的占位符） */
const ZERO_BYTE_ALLOW = new Set([".gitkeep", ".keep", ".gitignore"]);

/** 完整应用配置只允许这些位置；带 profile 后缀的（application-xxx.yaml）不受限 */
const CONFIG_ALLOW = new Set([
  "bootstrap/src/main/resources/application.yaml",
  "resources/docker/server/application.yaml",
  "rag/src/test/resources/application.yaml",
  "mcp-server/src/main/resources/application.yml",
]);

/** 遍历时跳过的目录 */
const WALK_SKIP = new Set([".git", "node_modules", "target", "dist", ".output", ".analysis", ".idea"]);

/** 单文件体积上限：超过基本可判定为误提交的产物 */
const MAX_FILE_BYTES = 10 * 1024 * 1024;

const violations = [];

function add(kind, key, message) {
  violations.push({ kind, key, message });
}

/**
 * 调用 git 并取回标准输出。
 *
 * 部分受限执行环境（CI 沙箱、部分容器）禁止跨进程管道，child_process 会抛 EPERM。
 * 此时改为让 git 直接把内容写进 .git 目录下的临时文件再读回，不经过管道。
 */
function git(args) {
  try {
    return execFileSync("git", args, {
      cwd: ROOT,
      encoding: "utf8",
      maxBuffer: 256 * 1024 * 1024,
    });
  } catch (err) {
    if (err?.code !== "EPERM") throw err;
    const tmp = join(ROOT, ".git", "hygiene-stdout.tmp");
    const fd = openSync(tmp, "w");
    try {
      execFileSync("git", args, { cwd: ROOT, stdio: ["ignore", fd, "ignore"] });
    } finally {
      closeSync(fd);
    }
    return readFileSync(tmp, "utf8");
  }
}

function trackedFiles() {
  return git(["ls-files", "-z"]).split("\0").filter(Boolean);
}

function hashFile(absPath) {
  return createHash("md5").update(readFileSync(absPath)).digest("hex");
}

/** 遍历工作区（用于发现未跟踪但体积异常的产物） */
function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (WALK_SKIP.has(entry.name)) continue;
    const abs = join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(abs, out);
    } else if (entry.isFile()) {
      out.push(abs);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// 1. 禁止入库的文件类型与路径
// ---------------------------------------------------------------------------
function checkForbiddenTracked(files) {
  for (const f of files) {
    for (const rule of FORBIDDEN_PATHS) {
      if (rule.re.test(f)) {
        add("tracked", `forbidden:${f}`, `${f} —— ${rule.why}`);
        break;
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 2. 零字节文件（多为生成失败或占位残留）
// ---------------------------------------------------------------------------
function checkZeroByte(files) {
  for (const f of files) {
    if (ZERO_BYTE_ALLOW.has(f.split("/").pop())) continue;
    const abs = join(ROOT, f);
    if (!existsSync(abs)) continue;
    if (statSync(abs).size === 0) {
      add("tracked", `empty:${f}`, `${f} —— 0 字节文件，通常是生成失败或残留`);
    }
  }
}

// ---------------------------------------------------------------------------
// 3. 完整 application.yaml 只允许一份来源
// ---------------------------------------------------------------------------
function checkConfigCopies(files) {
  for (const f of files) {
    const base = f.split("/").pop();
    if (base !== "application.yaml" && base !== "application.yml") continue;
    if (CONFIG_ALLOW.has(f)) continue;
    add(
      "tracked",
      `config-copy:${f}`,
      `${f} —— 又一份完整 application 配置。改配置改已有那份，差异用 application-<profile>.yaml 覆盖`,
    );
  }
}

// ---------------------------------------------------------------------------
// 4. SQL 内容重复（DDL 只应有一处权威副本）
// ---------------------------------------------------------------------------
function checkDuplicateSql(files) {
  const byHash = new Map();
  for (const f of files) {
    if (!f.endsWith(".sql")) continue;
    const abs = join(ROOT, f);
    if (!existsSync(abs)) continue;
    const h = hashFile(abs);
    if (!byHash.has(h)) byHash.set(h, []);
    byHash.get(h).push(f);
  }
  for (const [, group] of byHash) {
    if (group.length < 2) continue;
    const sorted = [...group].sort();
    add(
      "tracked",
      `dup-sql:${sorted.join("|")}`,
      `SQL 内容完全重复（${group.length} 份）：${sorted.join(" , ")} —— DDL 只保留 resources/database 下这一份`,
    );
  }
}

// ---------------------------------------------------------------------------
// 5. 体积异常的文件（覆盖被 .gitignore 隐藏的本地产物）
// ---------------------------------------------------------------------------
function checkOversize() {
  for (const abs of walk(ROOT)) {
    const size = statSync(abs).size;
    if (size <= MAX_FILE_BYTES) continue;
    const rel = relative(ROOT, abs).split(sep).join("/");
    add(
      "disk",
      `oversize:${rel}`,
      `${rel} —— ${(size / 1024 / 1024).toFixed(1)} MB，超过 ${MAX_FILE_BYTES / 1024 / 1024} MB 上限；可重建产物请删除`,
    );
  }
}

// ---------------------------------------------------------------------------
// 6. 前端从 main.tsx 不可达的模块（死文件）
// ---------------------------------------------------------------------------
function checkFrontendOrphans() {
  const srcDir = join(ROOT, "frontend", "src");
  if (!existsSync(srcDir)) return;

  const files = walk(srcDir).filter((p) => /\.(ts|tsx)$/.test(p));
  const byAbs = new Map(files.map((p) => [p, readFileSync(p, "utf8")]));

  const resolveSpec = (spec, fromFile) => {
    if (!spec.startsWith(".") && !spec.startsWith("@/")) return null;
    const base = spec.startsWith("@/")
      ? join(srcDir, spec.slice(2))
      : join(dirname(fromFile), spec);
    for (const suffix of ["", ".ts", ".tsx", "/index.ts", "/index.tsx"]) {
      const candidate = normalize(base + suffix);
      if (byAbs.has(candidate)) return candidate;
    }
    return null;
  };

  const entry = join(srcDir, "main.tsx");
  if (!byAbs.has(entry)) return;

  const reachable = new Set();
  const stack = [entry];
  while (stack.length > 0) {
    const current = stack.pop();
    if (reachable.has(current)) continue;
    reachable.add(current);
    const code = byAbs.get(current) ?? "";
    // 同时匹配静态 import 与 import() 动态导入
    for (const m of code.matchAll(/(?:from\s+|import\s*\(?\s*)["']([^"']+)["']/g)) {
      const target = resolveSpec(m[1], current);
      if (target && !reachable.has(target)) stack.push(target);
    }
  }

  for (const abs of files) {
    if (reachable.has(abs) || abs.endsWith(".d.ts")) continue;
    const rel = relative(ROOT, abs).split(sep).join("/");
    add("frontend", `orphan:${rel}`, `${rel} —— 从 main.tsx 不可达，疑似废弃模块`);
  }
}

// ---------------------------------------------------------------------------
// 7. package.json 中声明但从未被引用的运行时依赖
// ---------------------------------------------------------------------------
function checkUnusedNpmDeps() {
  const pkgPath = join(ROOT, "frontend", "package.json");
  const srcDir = join(ROOT, "frontend", "src");
  if (!existsSync(pkgPath) || !existsSync(srcDir)) return;

  const pkg = JSON.parse(readFileSync(pkgPath, "utf8"));
  const deps = Object.keys(pkg.dependencies ?? {});

  let corpus = "";
  for (const abs of walk(srcDir)) {
    if (/\.(ts|tsx|css)$/.test(abs)) corpus += readFileSync(abs, "utf8");
  }
  for (const name of ["index.html", "vite.config.ts", "tailwind.config.cjs", "postcss.config.cjs"]) {
    const abs = join(ROOT, "frontend", name);
    if (existsSync(abs)) corpus += readFileSync(abs, "utf8");
  }

  for (const dep of deps) {
    if (corpus.includes(dep)) continue;
    add(
      "frontend",
      `unused-dep:${dep}`,
      `${dep} —— package.json 声明为运行时依赖，但源码中 0 处引用`,
    );
  }
}

// ---------------------------------------------------------------------------
// 8. 游离在 Maven 构建之外的 .java（不会被编译，也不受重构保护）
// ---------------------------------------------------------------------------
function checkStrayJava(files) {
  for (const f of files) {
    if (!f.endsWith(".java")) continue;
    if (/\/src\/(main|test)\/java\//.test(f)) continue;
    add(
      "java",
      `stray-java:${f}`,
      `${f} —— 不在任何模块的 src/{main,test}/java 下，改接口时不会编译报错`,
    );
  }
}

// ---------------------------------------------------------------------------
// 执行
// ---------------------------------------------------------------------------
const files = trackedFiles();
checkForbiddenTracked(files);
checkZeroByte(files);
checkConfigCopies(files);
checkDuplicateSql(files);
checkOversize();
checkFrontendOrphans();
checkUnusedNpmDeps();
checkStrayJava(files);

const baseline = existsSync(BASELINE_FILE)
  ? new Set(JSON.parse(readFileSync(BASELINE_FILE, "utf8")).allowed ?? [])
  : new Set();

if (ARGS.has("--update-baseline")) {
  const allowed = [...new Set(violations.map((v) => v.key))].sort();
  writeFileSync(
    BASELINE_FILE,
    JSON.stringify(
      {
        _comment:
          "已知存量违规。此文件只应缩小，不应增长；修掉一条就删一条，不要用 --update-baseline 掩盖新问题。",
        count: allowed.length,
        allowed,
      },
      null,
      2,
    ) + "\n",
    "utf8",
  );
  console.log(`基线已写入 ${relative(ROOT, BASELINE_FILE)}，共 ${allowed.length} 条。`);
  process.exit(0);
}

const fresh = violations.filter((v) => !baseline.has(v.key));
const known = violations.length - fresh.length;

if (fresh.length === 0) {
  console.log(`[hygiene] 通过。${known > 0 ? `（另有 ${known} 条基线内的存量违规未计入）` : ""}`);
  process.exit(0);
}

const grouped = new Map();
for (const v of fresh) {
  if (!grouped.has(v.kind)) grouped.set(v.kind, []);
  grouped.get(v.kind).push(v);
}

console.error(`[hygiene] 发现 ${fresh.length} 条新增违规：\n`);
for (const [kind, items] of grouped) {
  console.error(`## ${kind} (${items.length})`);
  for (const v of items) {
    console.error(`  - ${v.message}`);
    if (VERBOSE) console.error(`      key: ${v.key}`);
  }
  console.error("");
}
console.error("这些问题必须修复，而不是加进基线。规则见 AGENTS.md 的「硬约束」一节。");
process.exit(1);
