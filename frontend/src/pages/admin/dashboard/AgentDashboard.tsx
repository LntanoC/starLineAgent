/**
 * Agent 分支的整页正文：KPI 带 + 两层等高分栏。
 * 排布与 Workflow 分支零重叠——这一页的栅格断点、纵向余量分配全挂在 globals.css 的
 * dashboard-page--agent 下，按内容列宽断而不是按视口断，那边一条规则也落不到 Workflow 上
 */
import { ShieldCheck, Sparkles } from "lucide-react";

import { cn } from "@/lib/utils";
import type { AgentDashboardPerformance, DashboardOverview } from "@/services/dashboardService";

import {
  AgentConfirmations,
  AgentMemoryContext,
  AgentRunHealth,
  AgentToolAnalysis
} from "./AgentDashboardSections";
import {
  KpiBand,
  TrafficCard,
  WINDOW_LABEL_MAP,
  baseKpiItems,
  formatNumber,
  formatPercent,
  toChange,
  type DashboardTimeWindow,
  type DashboardTrendBundle,
  type KPICardProps
} from "./DashboardCommon";

// ==========================================================================
// KPI Cards
// ==========================================================================

/**
 * 后两格：助手回复与工具成功率。前两格与 Workflow 分支同源，从 baseKpiItems 接过来
 */
const agentKpiItems = (
    overview: DashboardOverview | null,
    performance: AgentDashboardPerformance
): KPICardProps[] => {
  const toolSuccess =
      performance.tools.total > 0 ? (performance.tools.done / performance.tools.total) * 100 : null;

  return [
    ...baseKpiItems(overview),
    {
      value: formatNumber(performance.replies.total),
      label: "助手回复",
      // 环比与前两格同一个口径（本周期 - 上周期 ÷ 上周期），上周期没有回复时后端返 null，这格就只剩从属读数
      change: toChange(performance.replies.deltaPct),
      upIsGood: true,
      icon: <Sparkles className="h-[18px] w-[18px]" />,
      sub: `${formatNumber(performance.replies.withBlocks)} 条有轨迹`
    },
    {
      value: formatPercent(toolSuccess) === "-" ? null : formatPercent(toolSuccess),
      label: "工具成功率",
      upIsGood: true,
      // 这一格读的是"成不成"而不是"用了什么工具"，盾牌比扳手更贴那层语义，也与绿色片对得上
      icon: <ShieldCheck className="h-[18px] w-[18px]" />,
      chipTone: "good",
      // 没有轨迹时不能写「失败 0 次」：那是在断言零失败，而实际只是没记录可查
      sub: performance.tools.total > 0 ? `失败 ${formatNumber(performance.tools.failed)} 次` : "暂无工具轨迹"
    }
  ];
};

// ==========================================================================
// Agent Dashboard
// ==========================================================================

/**
 * 两层分栏共用同一个类。列定义、右列的 360px 硬下限与 1.55 : 1 的比例
 * 都在 globals.css 的 dashboard-split 里，那边按内容列宽断而不是按视口断
 */
const SPLIT_COLS = "dashboard-split";

export const AgentDashboard = ({
                                 overview,
                                 performance,
                                 trends,
                                 timeWindow,
                                 loading
                               }: {
  overview: DashboardOverview;
  performance: AgentDashboardPerformance;
  trends: DashboardTrendBundle;
  timeWindow: DashboardTimeWindow;
  loading?: boolean;
}) => {
  // 四张卡各自的弹框都要复述这个范围，统一从这一处取，两边不会说出两个窗口
  const windowLabel = WINDOW_LABEL_MAP[timeWindow];

  return (
      /*
        dashboard-stack 是纵向余量分配链上的一环（admin-content → dashboard-page--agent → 这里 → 两层分栏），
        整体降透明度表示"在刷新"，避免重新取数时整页塌成骨架造成布局跳动
      */
      <div className={cn("dashboard-stack space-y-3", loading && "opacity-60 transition-opacity")}>
        {/* KPI 带按内容列宽断（容器查询），断点写在 globals.css 的 dashboard-kpi-grid 里 */}
        <KpiBand items={agentKpiItems(overview, performance)} className="dashboard-kpi-grid" />

        {/*
          第二层分栏：两张卡都是栅格的直接子元素，靠 grid 默认的 stretch 把矮的那张拉到同高，
          上下沿才能同时对齐。中间再包一层 div 会把拉伸吃掉，卡片重新变成各撑各的高度
        */}
        <div className={cn("grid gap-x-4 gap-y-3", SPLIT_COLS, "dashboard-split--trend")}>
          <TrafficCard
              trends={trends}
              overview={overview}
              timeWindow={timeWindow}
              loading={loading}
              plotFill
          />
          <AgentRunHealth data={performance} windowLabel={windowLabel} />
        </div>

        {/*
          第三层沿用同一套分栏：两层的列边缘落在同一条线上，页面才只有一套栅格。
          右列是两张卡叠一列，本身撑不到左列那么高，所以整列拉满再让两张卡各占一半剩余高度，
          否则左卡的底沿会孤零零地掉在右列下方几十像素处
        */}
        <div className={cn("grid gap-x-4 gap-y-3", SPLIT_COLS, "dashboard-split--tools")}>
          <div className="min-w-0">
            <AgentToolAnalysis className="dashboard-split-fill" data={performance} windowLabel={windowLabel} />
          </div>
          {/*
            右列这道 12px 的缝直接进第三层的高度：这一层的高 = max(左边工具卡, 确认 + 缝 + 记忆)，
            而右列一路都是它更高。缝收一档，整层就矮一档
          */}
          <div className="flex min-w-0 flex-col gap-3">
            <AgentConfirmations className="dashboard-split-item" data={performance} windowLabel={windowLabel} />
            <AgentMemoryContext className="dashboard-split-item" data={performance} windowLabel={windowLabel} />
          </div>
        </div>
      </div>
  );
};
