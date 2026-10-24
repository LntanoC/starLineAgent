/**
 * 概览页的路由入口：只做取数、报错与分支分发。
 */
import { useEffect } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import { AgentDashboard } from "./AgentDashboard";
import { DashboardHeader, LoadingBlock, useDashboardData } from "./DashboardCommon";

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

  const agentPerformance = performance?.engine === "agent" ? performance : null;

  useEffect(() => {
    if (error) toast.error(error);
  }, [error]);

  return (
      /*
        Agent 分支的排布（内容列容器查询 + 纵向余量分配）整套挂在这个修饰类下，
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
        ) : null}
        {/* 页尾贴着最后一段走：它是这一页的限定语，不是新的一段内容，段间距会让它读成孤立的一条 */}
        <p className="!mt-2 text-xs text-[#98A2B3]">
          仅统计 Agent 引擎产生的记录。
        </p>
        </> : null}
      </div>
  );
}
