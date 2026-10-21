import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Pencil, Plus, Power, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { RelativeTime } from "@/components/RelativeTime";
import type { AgentSkill, PageResult } from "@/services/agentSkillService";
import {
  deleteAgentSkill,
  getAgentSkillsPage,
  toggleAgentSkillEnabled
} from "@/services/agentSkillService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

export function AgentSkillPage() {
  const navigate = useNavigate();
  const [pageData, setPageData] = useState<PageResult<AgentSkill> | null>(null);
  const [loading, setLoading] = useState(true);
  const [pageNo, setPageNo] = useState(1);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [keyword, setKeyword] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<AgentSkill | null>(null);
  const [disableTarget, setDisableTarget] = useState<AgentSkill | null>(null);

  const loadData = useCallback(async (current = pageNo, keywordValue = keyword) => {
    try {
      setLoading(true);
      const data = await getAgentSkillsPage(current, PAGE_SIZE, keywordValue || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载技能失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  }, [keyword, pageNo]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchKeyword.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadData(1, keyword);
  };

  const toggleEnabled = async (item: AgentSkill) => {
    try {
      await toggleAgentSkillEnabled(item.id, !item.enabled);
      toast.success(item.enabled ? `「${item.name}」已停用` : `「${item.name}」已启用`);
      await loadData(pageNo, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
      console.error(error);
    } finally {
      setDisableTarget(null);
    }
  };

  // 停用会让它解锁的工具重新直接暴露，带解锁工具的技能停用前先确认
  const handleToggle = (item: AgentSkill) => {
    if (item.enabled && item.toolIds?.length) {
      setDisableTarget(item);
      return;
    }
    void toggleEnabled(item);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteAgentSkill(deleteTarget.id);
      toast.success("删除成功");
      setPageNo(1);
      await loadData(1, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const records = pageData?.records || [];

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">技能管理</h1>
          <p className="admin-page-subtitle">
            技能是写给模型看的办事手册，智能体先看清单再决定要不要展开正文；勾选为解锁项的工具要先加载手册才对模型可见
          </p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchKeyword}
            onChange={(event) => setSearchKeyword(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="搜索技能名称/标识/场景"
            className="w-[240px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => navigate("/admin/agent-skills/new")}>
            <Plus className="w-4 h-4 mr-2" />
            新建技能
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              暂无技能，点击上方按钮新建
            </div>
          ) : (
            <Table className="min-w-[1020px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[200px]">技能名称</TableHead>
                  <TableHead className="w-[170px]">技能标识</TableHead>
                  <TableHead className="w-[300px]">适用场景</TableHead>
                  <TableHead className="w-[110px] whitespace-nowrap">解锁工具</TableHead>
                  <TableHead className="w-[80px]">状态</TableHead>
                  <TableHead className="w-[160px]">更新时间</TableHead>
                  <TableHead className="w-[210px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="font-medium max-w-[190px] truncate" title={item.name}>
                      {item.name}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-500">{item.skillCode}</TableCell>
                    <TableCell className="max-w-[290px] truncate text-muted-foreground" title={item.description}>
                      {item.description}
                    </TableCell>
                    <TableCell>
                      {item.toolIds?.length ? (
                        <Badge variant="secondary">{item.toolIds.length} 个</Badge>
                      ) : (
                        <span className="text-muted-foreground">纯说明</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant={item.enabled ? "default" : "outline"} className="whitespace-nowrap">
                        {item.enabled ? "启用" : "停用"}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <RelativeTime value={item.updateTime} />
                    </TableCell>
                    <TableCell className="text-center">
                      <div className="flex justify-center gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => navigate(`/admin/agent-skills/${item.id}`)}
                        >
                          <Pencil className="w-4 h-4 mr-0.5" />
                          编辑
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleToggle(item)}>
                          <Power className="w-4 h-4 mr-0.5" />
                          {item.enabled ? "停用" : "启用"}
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(item)}
                        >
                          <Trash2 className="w-4 h-4 mr-0.5" />
                          删除
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {pageData ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
          <span>共 {pageData.total} 条</span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={pageData.current <= 1}
            >
              上一页
            </Button>
            <span>
              {pageData.current} / {pageData.pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(pageData.pages || 1, prev + 1))}
              disabled={pageData.current >= pageData.pages}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}

      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              删除后「{deleteTarget?.name}」不再交给模型，它解锁的工具会恢复成直接暴露，是否继续？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={!!disableTarget} onOpenChange={() => setDisableTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认停用</AlertDialogTitle>
            <AlertDialogDescription>
              停用后「{disableTarget?.name}」不再交给模型，它解锁的 {disableTarget?.toolIds?.length ?? 0} 个工具会恢复成直接暴露、不再要求先看手册。
              要关掉这项办理能力本身，请到意图管理停用对应的 MCP 节点。是否继续？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={() => disableTarget && toggleEnabled(disableTarget)}>
              停用
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
