import { Loader2 } from "lucide-react";

import { normalizeStatus, statusLabel } from "@/pages/admin/traces/traceUtils";
import { cn } from "@/lib/utils";

interface TraceStatusChipProps {
  status?: string | null;
  className?: string;
}

/** 链路状态胶囊：语义色区分成功/失败/已取消/运行中，列表页与详情页共用 */
export function TraceStatusChip({ status, className }: TraceStatusChipProps) {
  const normalized = normalizeStatus(status) || "unknown";
  const isRunning = normalized === "running";

  return (
    <span className={cn("trace-status-chip", className)} data-status={normalized}>
      {isRunning ? (
        <Loader2 className="trace-status-chip-icon animate-spin" />
      ) : (
        <span className="trace-status-chip-dot" />
      )}
      <span>{statusLabel(status)}</span>
    </span>
  );
}
