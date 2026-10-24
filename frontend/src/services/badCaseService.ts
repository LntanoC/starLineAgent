import { api } from "@/services/api";
import type { SourceRef } from "@/types";

export const ROOT_CAUSES: Record<string, string> = {
  RETRIEVAL_MISS: "检索召回为空", RETRIEVAL_NOISE: "检索召回噪音", INTENT: "意图识别",
  HALLUCINATION: "幻觉 / 不忠实", FORMAT: "输出格式", PERF: "性能", OTHER: "其他"
};
export const FIX_ACTIONS: Record<string, string> = {
  MAPPING: "关键词映射", INTENT_NODE: "意图配置", KNOWLEDGE_BASE: "知识库", PROMPT: "提示词",
  RERANK: "检索参数", NONE: "无需修改"
};
export const REVIEW_STATUSES: Record<string, string> = {
  PENDING: "待判定", REVIEWED: "已判定", IGNORED: "已忽略", FIXED: "已修复"
};
export interface TraceNodeSummary {
  nodeId: string; parentNodeId?: string; depth?: number; nodeType: string; nodeName: string;
  status: string; durationMs?: number; errorMessage?: string; startTime?: string; extraData?: string;
}
export interface BadCaseReport {
  messageId: string; conversationId: string; userId: string; messageType: string;
  runId?: string; traceMatchedByFallback: boolean; traceMatchStatus: string; vote: number;
  reason?: string; comment?: string; userQuestion?: string; assistantAnswer?: string;
  thinkingContent?: string; messageStatus?: string; sources: SourceRef[];
  retrievedChunks: { docName?: string; text?: string }[]; traceNodes: TraceNodeSummary[];
  suspicions: string[]; evidenceNotes: string[]; messageCreateTime?: string;
}
export interface BadCaseRecord {
  id: string; report: BadCaseReport; rootCause?: string; rootCauseDetail?: string; fixAction?: string;
  fixRef?: string; fixNote?: string; reviewStatus: string; reviewer?: string; reviewTime?: string;
  revision: number; createTime: string; updateTime: string;
}
export interface ReviewPayload {
  rootCause?: string; rootCauseDetail: string; fixAction: string; fixRef: string;
  fixNote: string; reviewStatus: string; revision: number;
}
export interface BadCasePageData { records: BadCaseRecord[]; total: number; current: number; size: number; pages: number }
export interface BadCaseStats { total: number; byStatus: { value: string; count: number }[]; byRootCause: { value: string; count: number }[] }
export const getBadCases = (page: number, reviewStatus: string, rootCause: string) =>
  api.get<BadCasePageData, BadCasePageData>("/rag/bad-cases", { params: { page, size: 10, reviewStatus: reviewStatus || undefined, rootCause: rootCause || undefined } });
export const getBadCase = (id: string) => api.get<BadCaseRecord, BadCaseRecord>(`/rag/bad-cases/${encodeURIComponent(id)}`);
export const getBadCaseStats = () => api.get<BadCaseStats, BadCaseStats>("/rag/bad-cases/stats");
export const reviewBadCase = (id: string, data: ReviewPayload) => api.put(`/rag/bad-cases/${encodeURIComponent(id)}/review`, data);
export const scanBadCases = () => api.post<{ created: number; failed: number; scanned: number; busy: boolean }, { created: number; failed: number; scanned: number; busy: boolean }>("/rag/bad-cases/scan?includeOlder=true");
export const applyBadCaseMapping = (id: string, data: { sourceTerm: string; targetTerm: string; mappingId?: string; rootCause?: string; rootCauseDetail: string; fixNote: string; revision: number }) =>
  api.post<string, string>(`/rag/bad-cases/${encodeURIComponent(id)}/apply-mapping`, data);
