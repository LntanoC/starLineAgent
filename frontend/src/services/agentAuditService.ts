import { api } from "@/services/api";

export interface AgentAuditRun {
  runId: string; parentRunId?: string; resumeMessageId?: string;
  conversationId: string; userId: string; userMessageId?: string; assistantMessageId?: string;
  question?: string; answer?: string; runStatus: string; captureStatus: string;
  roundCount: number; modelCallCount: number; toolCallCount: number;
  inputTokens?: number; outputTokens?: number; cachedTokens?: number; durationMs?: number;
  errorMessage?: string; createTime: string; updateTime: string;
}
export interface AgentAuditNode {
  nodeId: string; parentNodeId?: string; runId: string; roundIndex: number;
  nodeType: string; nodeName: string; status: string; sequence: number;
  batchId?: string; toolCallId?: string; durationSource?: string;
  inputPayload?: unknown; outputPayload?: unknown;
  inputTokens?: number; outputTokens?: number; cachedTokens?: number; ttftMs?: number;
  startedAt?: string; endedAt?: string; durationMs?: number; errorMessage?: string;
}
export interface AgentAuditDetail { run: AgentAuditRun; nodes: AgentAuditNode[] }
export interface AgentAuditPage { records: AgentAuditRun[]; total: number; current: number; size: number; pages: number }
export interface AgentAuditFilters {
  current?: number; size?: number; runId?: string; conversationId?: string; messageId?: string;
  runStatus?: string; beginTime?: string; endTime?: string; slowOnly?: boolean; minInputTokens?: number;
}
export const getAgentRuns = (params: AgentAuditFilters) => api.get<AgentAuditPage, AgentAuditPage>("/agent/runs", { params });
export const getAgentRun = (runId: string) => api.get<AgentAuditDetail, AgentAuditDetail>(`/agent/runs/${encodeURIComponent(runId)}`);
export const getAgentRunByMessage = (messageId: string) => api.get<AgentAuditDetail, AgentAuditDetail>(`/agent/runs/by-message/${encodeURIComponent(messageId)}`);
export const RUN_LABELS: Record<string, string> = { RUNNING: "运行中", NORMAL: "已完成", FAILED: "失败", INTERRUPTED: "已中断", AWAITING_CONFIRM: "待确认" };
export const CAPTURE_LABELS: Record<string, string> = { COMPLETE: "采集完整", PARTIAL: "部分采集", DISABLED: "内容采集已关闭" };
export const NODE_LABELS: Record<string, string> = { SUCCESS: "成功", FAILED: "失败", RUNNING: "运行中", INTERRUPTED: "已中断", AWAITING: "待确认", DENIED: "已拒绝", AWAITING_CONFIRM: "待确认" };
