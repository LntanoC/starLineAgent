import { useCallback, useEffect, useMemo, useState } from "react";
import { RefreshCw, Search, Settings2, Sparkles, Wrench } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import {
  getMcpTools,
  rebuildMcpToolVectors,
  recallMcpTools,
  syncMcpTools,
  updateMcpTool,
  type McpToolRecallResult,
  type McpToolRegistryItem,
  type McpToolUpdatePayload
} from "@/services/mcpToolService";
import { getErrorMessage } from "@/utils/error";

function BooleanSetting({ label, value, onChange }: { label: string; value: boolean; onChange: (value: boolean) => void }) {
  return (
    <label className="flex items-start gap-3 rounded-md border p-3 text-sm">
      <Checkbox checked={value} onCheckedChange={(checked) => onChange(checked === true)} />
      <span>{label}</span>
    </label>
  );
}

export function McpToolPage() {
  const [tools, setTools] = useState<McpToolRegistryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [editing, setEditing] = useState<McpToolRegistryItem | null>(null);
  const [draft, setDraft] = useState<McpToolUpdatePayload | null>(null);
  const [recallQuery, setRecallQuery] = useState("");
  const [recallResult, setRecallResult] = useState<McpToolRecallResult | null>(null);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      setTools(await getMcpTools());
    } catch (error) {
      toast.error(getErrorMessage(error, "加载 MCP 工具注册表失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const visibleTools = useMemo(() => {
    const value = keyword.trim().toLowerCase();
    if (!value) return tools;
    return tools.filter((tool) => `${tool.toolId} ${tool.displayName} ${tool.description}`.toLowerCase().includes(value));
  }, [keyword, tools]);

  const openEditor = (tool: McpToolRegistryItem) => {
    setEditing(tool);
    setDraft({
      displayName: tool.displayName,
      description: tool.description,
      requireConfirm: tool.requireConfirm,
      resident: tool.resident,
      dispatchable: tool.dispatchable,
      enabled: tool.enabled,
      sortOrder: tool.sortOrder ?? 900,
      remark: tool.remark || ""
    });
  };

  const save = async () => {
    if (!editing || !draft) return;
    try {
      setWorking(true);
      await updateMcpTool(editing.toolId, draft);
      toast.success("工具治理配置已保存；后续对话会使用新配置");
      setEditing(null);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存工具配置失败"));
    } finally {
      setWorking(false);
    }
  };

  const sync = async () => {
    try {
      setWorking(true);
      const result = await syncMcpTools();
      toast.success(`发现 ${result.discovered} 个工具，新增登记 ${result.newlyRegistered} 个；新工具默认停用`);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "同步 MCP 服务工具失败"));
    } finally {
      setWorking(false);
    }
  };

  const rebuild = async () => {
    try {
      setWorking(true);
      const result = await rebuildMcpToolVectors();
      toast.success(`已重建 ${result.rebuilt} 个工具描述向量`);
    } catch (error) {
      toast.error(getErrorMessage(error, "重建工具向量失败"));
    } finally {
      setWorking(false);
    }
  };

  const runRecall = async () => {
    if (!recallQuery.trim()) return;
    try {
      setWorking(true);
      setRecallResult(await recallMcpTools(recallQuery.trim()));
    } catch (error) {
      toast.error(getErrorMessage(error, "工具召回试跑失败"));
    } finally {
      setWorking(false);
    }
  };

  return (
    <div className="admin-page space-y-5">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">MCP 工具管理</h1>
          <p className="admin-page-subtitle">注册表集中治理工具权限；每轮对话将常驻工具与 Tool RAG 召回结果组合后交给 AgentScope。</p>
        </div>
        <div className="admin-page-actions">
          <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索工具 ID / 描述" className="w-[230px]" />
          <Button variant="outline" onClick={() => void load()} disabled={working}><RefreshCw className="mr-2 h-4 w-4" />刷新</Button>
          <Button variant="outline" onClick={() => void rebuild()} disabled={working}><Sparkles className="mr-2 h-4 w-4" />重建向量</Button>
          <Button className="admin-primary-gradient" onClick={() => void sync()} disabled={working}><Wrench className="mr-2 h-4 w-4" />同步 MCP 工具</Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">工具注册表</CardTitle>
          <CardDescription>同步只登记远端工具，不会自动开放权限。新工具须人工审核后启用。</CardDescription>
        </CardHeader>
        <CardContent>
          {loading ? <div className="py-8 text-center text-muted-foreground">加载中...</div> : visibleTools.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无工具。请确认 API 已连接 MCP 服务后同步。</div>
          ) : (
            <div className="overflow-x-auto">
              <Table className="min-w-[1050px]">
                <TableHeader><TableRow>
                  <TableHead>工具</TableHead><TableHead>描述</TableHead><TableHead>来源</TableHead>
                  <TableHead>策略</TableHead><TableHead>状态</TableHead><TableHead className="w-24">操作</TableHead>
                </TableRow></TableHeader>
                <TableBody>{visibleTools.map((tool) => (
                  <TableRow key={tool.toolId}>
                    <TableCell><div className="font-medium">{tool.displayName}</div><code className="text-xs text-muted-foreground">{tool.toolId}</code></TableCell>
                    <TableCell className="max-w-[360px] truncate" title={tool.description}>{tool.description}</TableCell>
                    <TableCell><Badge variant={tool.discovered ? "secondary" : "outline"}>{tool.discovered ? "已发现" : "服务未发现"}</Badge>
                      {tool.readOnlyHint === true ? <div className="mt-1 text-xs text-muted-foreground">MCP 标记为只读</div> : null}</TableCell>
                    <TableCell className="space-x-1">
                      {tool.resident ? <Badge variant="outline">常驻</Badge> : <Badge variant="outline">按需召回</Badge>}
                      {tool.dispatchable ? <Badge variant="outline">可分发</Badge> : <Badge variant="outline">不可分发</Badge>}
                    </TableCell>
                    <TableCell><Badge variant={tool.enabled ? "default" : "secondary"}>{tool.enabled ? "启用" : "停用"}</Badge>
                      {tool.requireConfirm ? <div className="mt-1 text-xs text-muted-foreground">调用需确认</div> : null}</TableCell>
                    <TableCell><Button size="sm" variant="outline" onClick={() => openEditor(tool)}><Settings2 className="mr-1 h-4 w-4" />配置</Button></TableCell>
                  </TableRow>
                ))}</TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Tool RAG 召回试跑</CardTitle>
          <CardDescription>查看问题对应的常驻工具与按需召回工具；空召回时本轮只保留常驻工具。</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2">
            <Input value={recallQuery} onChange={(event) => setRecallQuery(event.target.value)} onKeyDown={(event) => event.key === "Enter" && void runRecall()} placeholder="例如：薛之谦最近有什么演出？" />
            <Button onClick={() => void runRecall()} disabled={working || !recallQuery.trim()}><Search className="mr-2 h-4 w-4" />试跑</Button>
          </div>
          {recallResult ? <div className="grid gap-4 md:grid-cols-2">
            <RecallList title="常驻工具" items={recallResult.residents} />
            <RecallList title="本次召回" items={recallResult.recalled} />
            {recallResult.vectorFallback ? <p className="text-sm text-amber-700 md:col-span-2">语义召回暂不可用，本次结果已降级使用关键词匹配。</p> : null}
          </div> : null}
        </CardContent>
      </Card>

      <Dialog open={Boolean(editing)} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>配置 MCP 工具</DialogTitle><DialogDescription>{editing?.toolId}</DialogDescription></DialogHeader>
          {draft ? <div className="max-h-[65vh] space-y-4 overflow-y-auto pr-1">
            <label className="block space-y-2 text-sm"><span>显示名称</span><Input value={draft.displayName} onChange={(event) => setDraft({ ...draft, displayName: event.target.value })} /></label>
            <label className="block space-y-2 text-sm"><span>召回描述</span><Textarea rows={5} value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} /></label>
            <label className="block space-y-2 text-sm"><span>排序值</span><Input type="number" value={draft.sortOrder} onChange={(event) => setDraft({ ...draft, sortOrder: Number(event.target.value) || 0 })} /></label>
            <label className="block space-y-2 text-sm"><span>备注</span><Input value={draft.remark || ""} onChange={(event) => setDraft({ ...draft, remark: event.target.value })} /></label>
            <div className="grid gap-2 sm:grid-cols-2">
              <BooleanSetting label="启用工具" value={draft.enabled} onChange={(enabled) => setDraft({ ...draft, enabled })} />
              <BooleanSetting label="常驻：每轮直接加入" value={draft.resident} onChange={(resident) => setDraft({ ...draft, resident })} />
              <BooleanSetting label="允许 Tool RAG 分发" value={draft.dispatchable} onChange={(dispatchable) => setDraft({ ...draft, dispatchable })} />
              <BooleanSetting label="调用前要求用户确认" value={draft.requireConfirm} onChange={(requireConfirm) => setDraft({ ...draft, requireConfirm })} />
            </div>
            {editing?.readOnlyHint === true ? <p className="text-xs text-muted-foreground">MCP Server 标记为只读；此标记与显式确认策略共同决定本轮确认要求。</p> : null}
          </div> : null}
          <DialogFooter><Button variant="outline" onClick={() => setEditing(null)}>取消</Button><Button onClick={() => void save()} disabled={working || !draft?.displayName.trim() || !draft?.description.trim()}>保存</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function RecallList({ title, items }: { title: string; items: McpToolRecallResult["recalled"] }) {
  return <div className="rounded-lg border p-4">
    <h3 className="mb-3 text-sm font-medium">{title} <span className="text-muted-foreground">({items.length})</span></h3>
    {items.length === 0 ? <p className="text-sm text-muted-foreground">无</p> : <ul className="space-y-2">
      {items.map((item) => <li key={item.toolId} className="flex items-start justify-between gap-3 text-sm">
        <div><div className="font-medium">{item.displayName}</div><code className="text-xs text-muted-foreground">{item.toolId}</code></div>
        <div className="shrink-0 text-right"><div>{item.resident ? "常驻" : item.score.toFixed(3)}</div><div className="text-xs text-muted-foreground">{item.source}{item.discovered ? " · 已连接" : " · 未发现"}</div></div>
      </li>)}
    </ul>}
  </div>;
}
