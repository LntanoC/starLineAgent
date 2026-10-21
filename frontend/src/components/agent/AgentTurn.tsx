import * as React from "react";

import { AgentMarkdownRenderer } from "@/components/agent/AgentMarkdownRenderer";
import {
  buildTimelineRows,
  formatDuration,
  type TraceChannel,
  type TraceRow
} from "@/lib/agentTimeline";
import { useAgentChatStore } from "@/stores/agentChatStore";
import type { AgentBlockUI, AgentConfirmCall, AgentTurn } from "@/types/agent";

const GLYPH: Record<TraceChannel, string> = {
  user: "▷",
  reasoning: "○",
  tool: "●",
  answer: "▮",
  hint: "·",
  confirm: "◈",
  error: "✕",
  batch: "≡"
};

/**
 * 确认卡的五种状态各配一枚芯片与一句引导语
 * 同意与取消各说各的：一句话罩两种结局只能含混，而这一句要回答的正是「到底办没办」
 * 引导语只说裁决结果，跑到哪一步交给逐项芯片与汇总后缀
 */
const CONFIRM_STATE: Partial<Record<string, { label: string; cls: string; lead: string }>> = {
  pending: {
    label: "待确认",
    cls: "agent-status-run",
    lead: "以下操作会真实生效，确认后才执行"
  },
  submitting: {
    label: "提交中",
    cls: "agent-status-run",
    lead: "正在把你的决定送给后台，结果以这里稍后的显示为准"
  },
  approved: { label: "已同意", cls: "agent-status-ok", lead: "以下操作已确认执行" },
  denied: { label: "已取消", cls: "agent-status-ok", lead: "以下操作已取消，未曾执行" },
  expired: {
    label: "已失效",
    cls: "agent-status-err",
    lead: "这一步已经失效，未曾执行，需要的话请重新提问"
  }
};

/**
 * 工具行七态芯片 denied 走静字不走红——用户自己按的取消不是出错
 */
const TOOL_STATE: Partial<Record<string, { label: string; cls: string }>> = {
  pending: { label: "待执行", cls: "agent-status-idle" },
  running: { label: "运行中", cls: "agent-status-run" },
  awaiting: { label: "待确认", cls: "agent-status-idle" },
  done: { label: "完成", cls: "agent-status-ok" },
  failed: { label: "失败", cls: "agent-status-err" },
  denied: { label: "已拒绝", cls: "agent-status-idle" },
  interrupted: { label: "已中断", cls: "agent-status-err" }
};

// awaiting 再分：卡里点名的在等授权 同批其余的只是随批等着
const BATCH_WAITING = { label: "随批等待", cls: "agent-status-idle" };

// 确认卡逐项芯片 不收 pending / awaiting：还没轮到的标出来会读成已了结
const ITEM_STATE: Partial<Record<string, { label: string; cls: string }>> = {
  running: TOOL_STATE.running,
  done: TOOL_STATE.done,
  failed: TOOL_STATE.failed,
  denied: TOOL_STATE.denied,
  interrupted: TOOL_STATE.interrupted
};

// 耗时 tooltip 三个通道量的东西不同
const DURATION_HINT: Partial<Record<TraceChannel, string>> = {
  batch: "服务端计时 · 工具体时间包络",
  tool: "服务端计时 · 本次执行耗时",
  reasoning: "服务端计时 · 这段文字流了多久",
  answer: "服务端计时 · 这段文字流了多久",
  error: "服务端计时 · 这段文字流了多久"
};

const NAME: Record<TraceChannel, string> = {
  user: "you",
  reasoning: "reasoning",
  tool: "tool",
  answer: "answer",
  hint: "hint",
  confirm: "confirm",
  error: "error",
  batch: "batch"
};

interface AgentTurnItemProps {
  turn: AgentTurn;
  /** 卡头旁注 目前只有待机空态的预演卡用它标「示例」 */
  note?: string;
}

/** 一轮用户↔助手收进一张卡：轮次头 + 各通道轨迹行 示波器时间轴在卡内贯穿 */
export function AgentTurnItem({ turn, note }: AgentTurnItemProps) {
  const rows = buildTimelineRows(turn);
  const headTs = rows[0]?.ts || "";
  const streaming = turn.assistants.some((assistant) => assistant.status === "streaming");
  // 流式中不显示总耗时 收尾实测或回放差值就绪后才亮
  // 一问多答按段累加：确认前那段与续跑那段合起来才是这一轮从提问到收尾的真实耗时
  const totalMs = turn.assistants.reduce((sum, assistant) => sum + (assistant.elapsedMs ?? 0), 0);
  const elapsed = streaming ? "" : formatDuration(totalMs || undefined);

  return (
    <section className="agent-turn">
      <header className="agent-turn-head">
        <span className="agent-turn-no">TURN {turn.index}</span>
        {note ? <span className="agent-turn-note">{note}</span> : null}
        <span className="agent-turn-ts">
          {headTs}
          {elapsed ? <span className="agent-turn-dur"> · {elapsed}</span> : null}
        </span>
      </header>
      {rows.map((row, i) => (
        <TraceRowItem key={row.key} row={row} showTs={i > 0} />
      ))}
    </section>
  );
}

function TraceRowItem({ row, showTs }: { row: TraceRow; showTs: boolean }) {
  const toolState =
    row.channel === "tool"
      ? row.batchWaiting
        ? BATCH_WAITING
        : TOOL_STATE[row.block?.status ?? ""]
      : undefined;
  const failed = row.channel === "tool" && row.block?.status === "failed";
  const running = row.channel === "tool" && row.block?.status === "running";
  const confirmState = row.channel === "confirm" ? CONFIRM_STATE[row.block?.status ?? ""] : undefined;
  // 文本通道耗时标「生成」 工具通道不标
  const textual = row.channel === "reasoning" || row.channel === "answer" || row.channel === "error";

  return (
    <div
      className="agent-row"
      data-channel={row.channel}
      data-failed={failed}
      data-streaming={Boolean(row.streaming || running)}
    >
      <div className="agent-row-rail">
        <span className="agent-node">{GLYPH[row.channel]}</span>
      </div>
      <div className="agent-row-content">
        <div className="agent-meta-line">
          <span className="agent-channel">{NAME[row.channel]}</span>
          {confirmState ? <span className={confirmState.cls}>{confirmState.label}</span> : null}
          {row.channel === "tool" && row.block?.name ? (
            <span className="agent-tool-chip">{row.block.name}</span>
          ) : null}
          {row.channel === "tool" &&
          row.block?.displayName &&
          row.block.displayName !== row.block.name ? (
            <span className="text-[color:var(--agent-muted)]">{row.block.displayName}</span>
          ) : null}
          {row.channel === "batch" ? (
            <span
              className="agent-batch-note"
              title={
                row.parallel
                  ? "各工具体的执行区间有重叠 · 行序是模型声明的先后，不是执行先后"
                  : undefined
              }
            >
              同批 {row.batchSize} 个工具{row.parallel ? " · 并行" : ""}
            </span>
          ) : null}
          {toolState ? <span className={toolState.cls}>{toolState.label}</span> : null}
          {/* 耗时 */}
          {row.durationMs != null ? (
            <span className="agent-row-dur" title={DURATION_HINT[row.channel] ?? DURATION_HINT.tool}>
              {textual ? " · 生成 " : " · "}
              {formatDuration(row.durationMs)}
            </span>
          ) : null}
          {showTs ? <span className="agent-row-ts">{row.ts}</span> : null}
        </div>
        <RowBody row={row} />
      </div>
    </div>
  );
}

function RowBody({ row }: { row: TraceRow }) {
  // 批头只是一行交代 底下各工具行自己有正文
  if (row.channel === "batch") {
    return null;
  }
  if (row.channel === "tool" && row.block) {
    return <ToolCallBox block={row.block} messageId={row.messageId} />;
  }
  if (row.channel === "confirm" && row.block) {
    return <ConfirmBox block={row.block} messageId={row.messageId} outcomes={row.outcomes} />;
  }
  if (row.channel === "reasoning" && row.block) {
    return <ReasoningRow block={row.block} messageId={row.messageId} streaming={row.streaming} />;
  }
  if (row.channel === "answer") {
    return (
      <div className="agent-answer-form">
        <AgentMarkdownRenderer content={row.text ?? ""} />
      </div>
    );
  }
  return <div className="agent-row-text">{row.text}</div>;
}

/**
 * 多项同工具调用时把取值一样的参数提到卡头 每项只留差异
 * 差异集为空说明两次调用一模一样 那就不提 免得每项只剩一个编号
 */
function splitCommonFields(calls: AgentConfirmCall[]) {
  const all = calls.map((call) => call.fields ?? []);
  if (calls.length < 2 || calls.some((call) => call.name !== calls[0].name)) {
    return { common: [], items: all };
  }
  const shared = all[0].filter((field) =>
    all.every((fields) => fields.some((f) => f.name === field.name && f.value === field.value))
  );
  const names = new Set(shared.map((field) => field.name));
  const items = all.map((fields) => fields.filter((field) => !names.has(field.name)));
  return items.every((fields) => fields.length === 0)
    ? { common: [], items: all }
    : { common: shared, items };
}

/** 一半成功一半失败时 只说「已同意」会把失败那条盖过去 */
function outcomeSummary(outcomes?: (AgentBlockUI | undefined)[]) {
  if (!outcomes || outcomes.length < 2) return "";
  const done = outcomes.filter((block) => block?.status === "done").length;
  const failed = outcomes.filter((block) => block?.status === "failed").length;
  if (done + failed < outcomes.length) return "";
  if (failed === 0) return `${done} 项全部完成`;
  if (done === 0) return `${failed} 项全部失败`;
  return `${done} 项成功 ${failed} 项失败`;
}

function FieldList({ fields, className }: { fields: AgentConfirmCall["fields"]; className: string }) {
  if (!fields || fields.length === 0) return null;
  return (
    <dl className={className}>
      {fields.map((field) => (
        <React.Fragment key={field.name}>
          <dt>{field.label}</dt>
          <dd>{field.value}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}

/**
 * 写操作确认卡：列出这一步要动的工具与入参 整卡一次裁决 不逐条勾选
 * 裁决后按钮撤走只留结论 —— 已经发生的事不该再摆一副能改的样子
 * 只报中文名：这里问的是「要不要办这件事」 工具 ID 属于实现 要查看下面的 tool 行一直都在
 */
function ConfirmBox({
  block,
  messageId,
  outcomes
}: {
  block: AgentBlockUI;
  messageId?: string;
  outcomes?: (AgentBlockUI | undefined)[];
}) {
  const confirmPendingTool = useAgentChatStore((state) => state.confirmPendingTool);
  const toggleBlockOpen = useAgentChatStore((state) => state.toggleBlockOpen);
  const isStreaming = useAgentChatStore((state) => state.isStreaming);
  const calls = block.calls ?? [];
  const pending = block.status === "pending";
  // 兜底句不猜结局：后端将来加了新状态，这里宁可只报事实
  const lead = CONFIRM_STATE[block.status ?? ""]?.lead ?? "以下操作已提交确认";
  const summary = outcomeSummary(outcomes);
  const { common, items } = splitCommonFields(calls);
  // 有共同项即说明各项同工具 工具名跟着提到卡头 不必每项重复一遍
  const head = common.length > 0 ? calls[0] : undefined;
  const raw = calls.map((call) => call.arguments).filter(Boolean);

  return (
    <div className="agent-confirm">
      <p className="agent-confirm-lead">
        {lead}
        {summary ? <span className="agent-confirm-summary"> · {summary}</span> : null}
      </p>
      {head ? (
        <div className="agent-confirm-head">
          <div className="agent-confirm-name">
            <span>{head.displayName || head.name}</span>
            <span className="agent-row-count">×{calls.length}</span>
          </div>
          <FieldList fields={common} className="agent-confirm-fields" />
        </div>
      ) : null}
      <ul className="agent-confirm-list">
        {calls.map((call, i) => {
          const state = ITEM_STATE[outcomes?.[i]?.status ?? ""];
          return (
            <li key={call.toolCallId}>
              <div className="agent-confirm-name">
                {calls.length > 1 ? <span className="agent-confirm-no">#{i + 1}</span> : null}
                {head ? null : <span>{call.displayName || call.name}</span>}
                {state ? <span className={state.cls}>{state.label}</span> : null}
              </div>
              <FieldList fields={items[i]} className="agent-confirm-fields" />
            </li>
          );
        })}
      </ul>
      {raw.length > 0 ? (
        <div className="agent-confirm-raw">
          <button
            type="button"
            className="agent-tool-summary"
            onClick={() => {
              if (messageId) toggleBlockOpen(messageId, block.id);
            }}
            aria-expanded={Boolean(block.open)}
          >
            <span className="agent-caret">{block.open ? "▾" : "▸"}</span>
            <span className="agent-tool-preview">原始参数</span>
          </button>
          {block.open ? raw.map((text, i) => <pre key={i} className="agent-pre">{text}</pre>) : null}
        </div>
      ) : null}
      {pending ? (
        <div className="agent-confirm-actions">
          <button
            type="button"
            className="agent-confirm-btn"
            data-primary="true"
            disabled={isStreaming || !messageId}
            onClick={() => {
              if (messageId) confirmPendingTool(messageId, block.id, true);
            }}
          >
            确认执行
          </button>
          <button
            type="button"
            className="agent-confirm-btn"
            disabled={isStreaming || !messageId}
            onClick={() => {
              if (messageId) confirmPendingTool(messageId, block.id, false);
            }}
          >
            取消
          </button>
        </div>
      ) : null}
    </div>
  );
}

/**
 * 思考轨迹：正在想时强制展开实时滚字 块结束自动收成一行摘要 点开可重看
 */
function ReasoningRow({
  block,
  messageId,
  streaming
}: {
  block: AgentBlockUI;
  messageId?: string;
  streaming?: boolean;
}) {
  const toggleBlockOpen = useAgentChatStore((state) => state.toggleBlockOpen);
  const open = Boolean(block.open) || Boolean(streaming);

  return (
    <div>
      <button
        type="button"
        className="agent-reasoning-toggle"
        onClick={() => {
          if (messageId) toggleBlockOpen(messageId, block.id);
        }}
        aria-expanded={open}
      >
        <span className="agent-caret">{open ? "▾" : "▸"}</span>
        <span className="agent-reasoning-peek">{peek(block.text ?? "")}</span>
      </button>
      {open ? (
        <div className="agent-reasoning-body">
          <AgentMarkdownRenderer content={block.text ?? ""} />
        </div>
      ) : null}
    </div>
  );
}

/**
 * 工具块：一行结果摘要 + 展开看完整返回 失败时错因直接显在摘要位（不展开也看得到）
 */
function ToolCallBox({ block, messageId }: { block: AgentBlockUI; messageId?: string }) {
  const toggleBlockOpen = useAgentChatStore((state) => state.toggleBlockOpen);
  const open = Boolean(block.open);
  const failed = block.status === "failed";
  const raw = block.result ?? "";
  const parsed = React.useMemo(() => tryParse(raw), [raw]);

  if (block.status === "running") {
    return (
      <div className="agent-toolbox">
        <div className="agent-tool-summary">
          <span className="agent-caret">▸</span>
          <span className="agent-tool-preview">执行中…</span>
        </div>
      </div>
    );
  }

  // 没到过执行就没有返回可看
  if (block.status === "pending" || block.status === "awaiting" || block.status === "denied") {
    return null;
  }
  // 中断且无结果也不展开
  if (block.status === "interrupted" && !raw) {
    return null;
  }

  const summary = failed ? errorSummary(raw) : summarize(parsed, raw);
  const full = parsed != null ? stringify(parsed) : raw;

  return (
    <div className="agent-toolbox">
      <button
        type="button"
        className="agent-tool-summary"
        onClick={() => {
          if (messageId) toggleBlockOpen(messageId, block.id);
        }}
        aria-expanded={open}
      >
        <span className="agent-caret">{open ? "▾" : "▸"}</span>
        <span className="agent-tool-preview">{summary}</span>
      </button>
      {open ? <pre className="agent-pre">{full || "（空返回）"}</pre> : null}
    </div>
  );
}

/** 单行去 markdown 记号：标题/列表前缀与强调符 折叠摘要不该露原始符号 */
function stripMdMarks(line: string): string {
  return line
    .replace(/^#{1,6}\s*/, "")
    .replace(/^[-*+]\s+/, "")
    .replace(/[*_`>]/g, "")
    .trim();
}

/** 压平 markdown 文本为一行：丢分隔线 逐行去记号后以空格拼接 */
function flattenMd(text: string): string {
  return text
    .split("\n")
    .map((s) => s.trim())
    .filter((s) => s && !/^(-{3,}|\*{3,}|_{3,})$/.test(s))
    .map(stripMdMarks)
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

/** 取首个非空行、去掉 markdown 记号 作为思考折叠态的一行摘要 */
function peek(text: string): string {
  const line =
    text
      .split("\n")
      .map((s) => s.trim())
      .find(Boolean) ?? "";
  const clean = stripMdMarks(line);
  return clean.length > 84 ? `${clean.slice(0, 84)}…` : clean || "思考中";
}

function tryParse(text: string): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** 去掉可能的 Error 前缀 只留人能看懂的错因 */
function errorSummary(text: string): string {
  const t = text.replace(/^\s*error[:：]\s*/i, "").trim();
  return t.length > 120 ? `${t.slice(0, 120)}…` : t || "工具执行出错";
}

function summarize(json: unknown, text: string): string {
  if (json == null) {
    const t = flattenMd(text);
    return t.length > 96 ? `${t.slice(0, 96)}…` : t || "（空返回）";
  }
  if (Array.isArray(json)) return `数组 · ${json.length} 项`;
  if (typeof json === "object") {
    const compact = JSON.stringify(json);
    if (compact.length <= 96) return compact;
    const keys = Object.keys(json as object);
    return `对象 · ${keys.slice(0, 4).join(", ")}${keys.length > 4 ? "…" : ""}`;
  }
  return String(json);
}

function stringify(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}
