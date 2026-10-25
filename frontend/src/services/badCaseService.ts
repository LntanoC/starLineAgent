import { api } from "@/services/api";
import type { SourceRef } from "@/types";

export const ROOT_CAUSES: Record<string, string> = {
  RETRIEVAL_MISS: "检索召回为空", RETRIEVAL_NOISE: "检索召回噪音", INTENT: "意图识别",
  HALLUCINATION: "幻觉 / 不忠实", FORMAT: "输出格式", PERF: "性能", OTHER: "其他", TOOL_EXECUTION: "工具执行", BUSINESS_RULE: "业务规则", DELIVERY: "邮件 / 通知交付"
};
export const FIX_ACTIONS: Record<string, string> = {
  MAPPING: "关键词映射", INTENT_NODE: "意图配置", KNOWLEDGE_BASE: "知识库", PROMPT: "提示词",
  RERANK: "检索参数", NONE: "无需修改", TOOL: "工具实现", BUSINESS_RULE: "业务规则", DELIVERY: "交付链路", EVAL_DATASET: "评测样本"
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
  revision: number; createTime: string; updateTime: string; candidate: CandidateMetadata;
}
export interface ReviewPayload {
  rootCause?: string; rootCauseDetail: string; fixAction: string; fixRef: string;
  fixNote: string; reviewStatus: string; revision: number;
}
export interface BadCasePageData { records: BadCaseRecord[]; total: number; current: number; size: number; pages: number }
export interface BadCaseStats { highPending: number; lowPending: number; todaySignals: number; total: number; byStatus: { value: string; count: number }[]; byRootCause: { value: string; count: number }[] }
export const getBadCases = (page: number, reviewStatus: string, rootCause: string, filters: Record<string, string | boolean | undefined> = {}) =>
  api.get<BadCasePageData, BadCasePageData>("/rag/bad-cases", { params: { ...filters, page, size: 10, reviewStatus: reviewStatus || undefined, rootCause: rootCause || undefined } });
export const getBadCase = (id: string) => api.get<BadCaseRecord, BadCaseRecord>(`/rag/bad-cases/${encodeURIComponent(id)}`);
export const getBadCaseStats = () => api.get<BadCaseStats, BadCaseStats>("/rag/bad-cases/stats");
export const reviewBadCase = (id: string, data: ReviewPayload) => api.put(`/rag/bad-cases/${encodeURIComponent(id)}/review`, data);
export const scanBadCases = () => api.post<{ created: number; failed: number; scanned: number; busy: boolean }, { created: number; failed: number; scanned: number; busy: boolean }>("/rag/bad-cases/scan?includeOlder=true");
export const applyBadCaseMapping = (id: string, data: { sourceTerm: string; targetTerm: string; mappingId?: string; rootCause?: string; rootCauseDetail: string; fixNote: string; revision: number }) =>
  api.post<string, string>(`/rag/bad-cases/${encodeURIComponent(id)}/apply-mapping`, data);

export const SOURCES: Record<string,string> = { USER_FEEDBACK:"用户点踩", USER_CORRECTION:"用户明确纠正", AGENT_RUNTIME:"运行 / 工具异常", STARLINE_BUSINESS:"星序业务规则", STARLINE_DELIVERY:"旧版提醒交付记录", CONVERSATION_BEHAVIOR:"会话行为", UNVOTED_SAMPLE:"未点踩抽样", EVALUATION:"评测 / 回归" };
export const TRIAGE: Record<string,string> = {OBSERVING:"观察中",AUTO_REVIEW_PENDING:"待自动评审",READY_FOR_HUMAN:"待人工核查",SUPPRESSED:"已抑制"};
export interface CandidateMetadata { subject_type: string; subject_id: string; title: string; primary_source: string; confidence: string; severity: string; triage_status: string; signal_count: number; first_detected_at: string; last_detected_at: string; snapshot_status: string; auto_review_result?: unknown; auto_review_model?: string; suppressed_reason?: string }
export interface BadCaseSignal { id: string; source: string; rule_code: string; confidence: string; severity: string; occurrence_key: string; evidence: unknown; detector_version: string; occurred_at: string; invalidated_at?: string; invalid_reason?: string }
export interface EvalSample { id: string; bad_case_id: string; case_id: string; dataset_name: string; test_lane: string; turns: unknown; tags: string[]; description?: string; status: string; revision: number; last_result?: string; last_run_id?: string; last_run_finished_at?: string; recurrence_count: number }
export const getBadCaseSignals=(id:string)=>api.get<BadCaseSignal[],BadCaseSignal[]>(`/rag/bad-cases/${encodeURIComponent(id)}/signals`);
export const getReviewHistory=(id:string)=>api.get<unknown[],unknown[]>(`/rag/bad-cases/${encodeURIComponent(id)}/review-history`);
export const promoteBadCase=(id:string,revision:number)=>api.post(`/rag/bad-cases/${encodeURIComponent(id)}/promote`,{revision});
export const getEvalSamples=(id?:string)=>api.get<EvalSample[],EvalSample[]>("/rag/bad-cases/eval-samples",{params:{candidateId:id}});
export const saveEvalSample=(id:string,draft:unknown)=>api.post(`/rag/bad-cases/${encodeURIComponent(id)}/eval-sample`,draft);
export const approveEvalSample=(id:string,revision:number)=>api.post(`/rag/bad-cases/eval-samples/${encodeURIComponent(id)}/approve`,{revision});
export const exportEvalSamples=(datasetName:string,testLane="CONVERSATION")=>api.get<unknown[],unknown[]>("/rag/bad-cases/eval-samples/export",{params:{datasetName,testLane}});
export const getEvalManifest=(datasetName:string)=>api.get<unknown[],unknown[]>("/rag/bad-cases/eval-samples/manifest",{params:{datasetName}});
export const scanDetector=(detector:string,mode:string)=>api.post<Record<string,unknown>,Record<string,unknown>>("/rag/bad-cases/scan",null,{params:{detector:detector||undefined,mode,includeOlder:true}});
export function downloadJson(value:unknown,name:string){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:"application/json"}));const a=document.createElement("a");a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
