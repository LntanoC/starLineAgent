/**
 * 概览页的路由入口：只做取数、报错与分支分发。
 * 两个引擎的正文各自成文件（AgentDashboard / WorkflowDashboard），共用的只有取数与读数件，
 * 排布一条不共用——早先那套纵向余量分配挂在公共祖先上，把 Workflow 的页盒也一起撑高了
 */
import { useEffect } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import { AgentDashboard } from "./AgentDashboard";
import { DashboardHeader, LoadingBlock, useDashboardData } from "./DashboardCommon";
import { WorkflowDashboard } from "./WorkflowDashboard";

export function DashboardPage() {
  const {
    engine,
    timeWindow,
    setTimeWindow,
    loading,
    error,
    lastUpdated,
    overview,
    performance,
    trends,
    refresh
  } = useDashboardData();

  const workflowPerformance = performance?.engine === "workflow" ? performance : null;
  const agentPerformance = performance?.engine === "agent" ? performance : null;

  useEffect(() => {
    if (error) toast.error(error);
  }, [error]);

  return (
      /*
        Agent 分支的排布（内容列容器查询 + 纵向余量分配）整套挂在这个修饰类下，
        不靠"本页有没有某张卡"推断。Workflow 分支拿不到这个类，globals.css 里那套一条也落不到它身上，
        它的栅格仍是各自组件上的 Tailwind 视口断点
      */
      <div className={cn("admin-page dashboard-page", engine === "agent" && "dashboard-page--agent")}>
        <DashboardHeader
            engine={engine}
            timeWindow={timeWindow}
            lastUpdated={lastUpdated}
            loading={loading}
            onRefresh={() => void refresh()}
            onTimeWindowChange={setTimeWindow}
        />

        {error && <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          <span>{error}。{overview ? "已显示可用的概览，趋势可稍后重试。" : "统计暂不可用，请刷新重试。"}</span>
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={loading}>重新加载</Button>
        </div>}

        {loading && !overview ? <div role="status" aria-label="正在加载统计" className="space-y-4">
          <LoadingBlock className="h-[128px]" /><LoadingBlock className="h-[300px]" />
        </div> : overview && engine ? <>
        {agentPerformance ? (
            <AgentDashboard
                overview={overview}
                performance={agentPerformance}
                trends={trends}
                timeWindow={timeWindow}
                loading={loading}
            />
        ) : (
            <WorkflowDashboard
                overview={overview}
                performance={workflowPerformance}
                trends={trends}
                timeWindow={timeWindow}
                lastUpdated={lastUpdated}
                loading={loading}
            />
        )}
        {/* 页尾贴着最后一段走：它是这一页的限定语，不是新的一段内容，段间距会让它读成孤立的一条 */}
        <p className="!mt-2 text-xs text-[#98A2B3]">
          仅统计当前 {engine === "agent" ? "Agent" : "Workflow"} 引擎产生的记录{engine === "workflow" && "；消息含用户与助手记录，活跃会话消息均值 = 窗口内消息数 ÷ 窗口内有消息的会话数"}。
        </p>
        </> : null}
      </div>
  );
}
