import { api } from "@/services/api";

export interface McpToolRegistryItem {
  toolId: string;
  displayName: string;
  description: string;
  requireConfirm: boolean;
  resident: boolean;
  dispatchable: boolean;
  enabled: boolean;
  sortOrder: number;
  remark?: string | null;
  updateTime?: string | null;
  discovered: boolean;
  readOnlyHint?: boolean | null;
}

export interface McpToolUpdatePayload {
  displayName: string;
  description: string;
  requireConfirm: boolean;
  resident: boolean;
  dispatchable: boolean;
  enabled: boolean;
  sortOrder: number;
  remark?: string | null;
}

export interface McpToolRecallItem {
  toolId: string;
  displayName: string;
  resident: boolean;
  score: number;
  source: string;
  discovered: boolean;
}

export interface McpToolRecallResult {
  residents: McpToolRecallItem[];
  recalled: McpToolRecallItem[];
  vectorFallback: boolean;
}

export async function getMcpTools(): Promise<McpToolRegistryItem[]> {
  return api.get<McpToolRegistryItem[], McpToolRegistryItem[]>("/admin/mcp-tools");
}

export async function syncMcpTools(): Promise<{ discovered: number; newlyRegistered: number }> {
  return api.post<{ discovered: number; newlyRegistered: number }, { discovered: number; newlyRegistered: number }>(
    "/admin/mcp-tools/sync"
  );
}

export async function updateMcpTool(toolId: string, payload: McpToolUpdatePayload): Promise<void> {
  await api.put(`/admin/mcp-tools/${encodeURIComponent(toolId)}`, payload);
}

export async function rebuildMcpToolVectors(): Promise<{ rebuilt: number }> {
  return api.post<{ rebuilt: number }, { rebuilt: number }>("/admin/mcp-tools/rebuild-vectors");
}

export async function recallMcpTools(query: string): Promise<McpToolRecallResult> {
  return api.post<McpToolRecallResult, McpToolRecallResult>("/admin/mcp-tools/recall-test", { query });
}
