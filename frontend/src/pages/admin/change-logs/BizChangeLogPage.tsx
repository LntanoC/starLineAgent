import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Boxes,
  Check,
  Copy,
  Database,
  Eye,
  FileText,
  ListChecks,
  MessagesSquare,
  Network,
  Orbit,
  ScrollText,
  Search,
  ShieldCheck,
  Sparkles,
  Tags,
  User,
  Workflow,
  XCircle,
  type LucideIcon
} from "lucide-react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";
import { toast } from "sonner";

import { RelativeTime } from "@/components/RelativeTime";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  getBizChangeLog,
  getBizChangeLogsPage,
  type BizChangeLog,
  type PageResult
} from "@/services/bizChangeLogService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE_OPTIONS = [10, 20, 50];
const ALL_VALUE = "__all__";

const FILTER_SELECT_TRIGGER_CLASS =
  "h-10 border-rule text-sm focus:ring-0 focus:ring-offset-0 focus-visible:ring-0 focus-visible:ring-offset-0 data-[state=open]:border-rule data-[state=open]:ring-0";
const FILTER_INPUT_CLASS = "h-10 border-rule text-sm focus-visible:border-rule focus-visible:ring-0 focus-visible:ring-offset-0";

const pad2 = (value: number) => String(value).padStart(2, "0");

const toDateInput = (date: Date) => `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;

// 默认查询最近一个月的数据
const defaultDateRange = () => {
  const end = new Date();
  const begin = new Date();
  begin.setMonth(begin.getMonth() - 1);
  return { beginTime: toDateInput(begin), endTime: toDateInput(end) };
};

const createDefaultFilters = () => ({
  bizType: "",
  operationType: "",
  success: "",
  bizId: "",
  operatorName: "",
  ...defaultDateRange()
});

const SUCCESS_OPTIONS = [
  { value: "true", label: "成功" },
  { value: "false", label: "失败" }
];

const BIZ_TYPE_OPTIONS = [
  { value: "AGENT_PROFILE", label: "智能体" },
  { value: "AGENT_SKILL", label: "技能" },
  { value: "KNOWLEDGE_BASE", label: "知识库" },
  { value: "KNOWLEDGE_DOCUMENT", label: "文档" },
  { value: "KNOWLEDGE_CHUNK", label: "文档分块" },
  { value: "INGESTION_PIPELINE", label: "数据通道" },
  { value: "INGESTION_TASK", label: "采集任务" },
  { value: "INTENT_TREE", label: "意图树" },
  { value: "QUERY_TERM_MAPPING", label: "关键词映射" },
  { value: "SAMPLE_QUESTION", label: "示例问题" },
  { value: "USER", label: "用户" }
];

const OPERATION_OPTIONS = [
  { value: "CREATE", label: "新增" },
  { value: "UPDATE", label: "修改" },
  { value: "DELETE", label: "删除" },
  { value: "ENABLE", label: "启用" },
  { value: "DISABLE", label: "禁用" },
  { value: "RUN", label: "执行" }
];

type DetailTab = "diff" | "before" | "after" | "context";

type DiffItem = {
  field?: string;
  before?: unknown;
  after?: unknown;
};

const labelOf = (options: { value: string; label: string }[], value?: string | null) => {
  return options.find((item) => item.value === value)?.label || value || "-";
};

const parseJson = (value?: string | null): unknown => {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
};

// 业务类型的图标与配色，让业务列更有辨识度也更大气
const BIZ_TYPE_META: Record<string, { icon: LucideIcon; className: string }> = {
  // 跟 AgentAvatar 的 orbit-indigo 预设对齐，别单独挑图标和配色
  AGENT_PROFILE: { icon: Orbit, className: "bg-accent-wash text-accent" },
  // 技能挂在智能体名下，取 AgentAvatar 的 sparkles-violet 预设，与智能体的靛蓝同族又能分开
  AGENT_SKILL: { icon: Sparkles, className: "bg-accent-wash text-accent" },
  KNOWLEDGE_BASE: { icon: Database, className: "bg-info-wash text-accent" },
  KNOWLEDGE_DOCUMENT: { icon: FileText, className: "bg-accent-wash text-accent" },
  KNOWLEDGE_CHUNK: { icon: Boxes, className: "bg-info-wash text-info" },
  INGESTION_PIPELINE: { icon: Workflow, className: "bg-warning-wash text-warning" },
  INGESTION_TASK: { icon: ListChecks, className: "bg-warning-wash text-warning" },
  INTENT_TREE: { icon: Network, className: "bg-accent-wash text-accent" },
  QUERY_TERM_MAPPING: { icon: Tags, className: "bg-accent-wash text-accent" },
  SAMPLE_QUESTION: { icon: MessagesSquare, className: "bg-success-wash text-success" },
  USER: { icon: User, className: "bg-warning-wash text-warning" }
};

// 操作类型使用语义化配色，与结果列的圆点样式区分开
const operationBadgeClass = (operationType?: string | null) => {
  switch (operationType) {
    case "CREATE":
    case "ENABLE":
      return "border-success-rule bg-success-wash text-success";
    case "UPDATE":
      return "border-info-rule bg-info-wash text-accent";
    case "DELETE":
      return "border-error-rule bg-error-wash text-error";
    case "DISABLE":
      return "border-rule bg-paper text-muted-foreground";
    case "RUN":
      return "border-warning-rule bg-warning-wash text-warning";
    default:
      return "border-rule bg-paper text-muted-foreground";
  }
};

function BizTypeCell({ bizType }: { bizType?: string | null }) {
  const meta = (bizType && BIZ_TYPE_META[bizType]) || { icon: Boxes, className: "bg-paper text-muted-foreground" };
  const Icon = meta.icon;
  return (
    <div className="flex items-center gap-2.5">
      <span className={cn("flex h-8 w-8 shrink-0 items-center justify-center rounded-lg", meta.className)}>
        <Icon className="h-[18px] w-[18px]" />
      </span>
      <span className="font-medium text-ink">{labelOf(BIZ_TYPE_OPTIONS, bizType)}</span>
    </div>
  );
}

// 尝试把「看起来像 JSON 的字符串」解析成对象/数组，只认 {} 与 []，避免把 "123"、"true" 这类误判
const parseEmbeddedJson = (value: string): unknown | undefined => {
  const trimmed = value.trim();
  if (trimmed.length < 2) return undefined;
  const first = trimmed[0];
  const last = trimmed[trimmed.length - 1];
  if (!((first === "{" && last === "}") || (first === "[" && last === "]"))) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return parsed !== null && typeof parsed === "object" ? parsed : undefined;
  } catch {
    return undefined;
  }
};

// 字段值本身是 JSON 字符串时先解出来，统一按结构渲染
const normalizeJsonValue = (value: unknown): unknown => {
  if (typeof value === "string") {
    const embedded = parseEmbeddedJson(value);
    return embedded === undefined ? value : embedded;
  }
  return value;
};

// 是否需要独占一块缩进展开（非空对象/数组）
const isExpandable = (value: unknown): boolean =>
  value !== null &&
  typeof value === "object" &&
  (Array.isArray(value) ? value.length > 0 : Object.keys(value).length > 0);

// 按 JSON 值类型着色的标量渲染
function JsonScalar({ value }: { value: unknown }) {
  if (value === null || value === undefined) {
    return <span className="italic text-muted-foreground">null</span>;
  }
  if (typeof value === "string") {
    return <span className="whitespace-pre-wrap break-all text-success">{value === "" ? '""' : value}</span>;
  }
  if (typeof value === "number") {
    return <span className="text-accent">{value}</span>;
  }
  if (typeof value === "boolean") {
    return <span className="text-accent">{String(value)}</span>;
  }
  return <span className="text-ink">{String(value)}</span>;
}

// 常规 JSON 树渲染，键值分明、嵌套缩进，字段里内嵌的 JSON 字符串也会递归展开
function JsonView({ data }: { data: unknown }) {
  const value = normalizeJsonValue(data);
  if (value === null || typeof value !== "object") {
    return <JsonScalar value={value} />;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return <span className="text-muted-foreground">[]</span>;
    }
    return (
      <div className="space-y-1.5 border-l border-rule pl-3">
        {value.map((item, index) => (
          <div key={index} className="flex gap-2">
            <span className="shrink-0 text-muted-foreground">{index}</span>
            <div className="min-w-0 flex-1">
              <JsonView data={item} />
            </div>
          </div>
        ))}
      </div>
    );
  }
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) {
    return <span className="text-muted-foreground">{"{}"}</span>;
  }
  return (
    <div className="space-y-1.5">
      {entries.map(([key, val]) => {
        const nested = isExpandable(normalizeJsonValue(val));
        return (
          <div key={key} className={nested ? "space-y-1" : "flex gap-2"}>
            <span className="shrink-0 font-medium text-muted-foreground">{key}</span>
            <div className={nested ? "pl-3" : "min-w-0 flex-1"}>
              <JsonView data={val} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

function ChangeDiffTable({ detail }: { detail: BizChangeLog | null }) {
  const diffItems = useMemo(() => {
    const parsed = parseJson(detail?.changeDiff) as DiffItem[] | null;
    return Array.isArray(parsed) ? parsed : [];
  }, [detail?.changeDiff]);

  if (!detail) {
    return <div className="py-8 text-center text-sm text-muted-foreground">请选择一条日志</div>;
  }

  if (diffItems.length === 0) {
    return <div className="py-8 text-center text-sm text-muted-foreground">没有字段差异</div>;
  }

  return (
    <div className="max-h-[440px] overflow-auto rounded-lg border border-rule">
      <Table className="min-w-[760px]">
        <TableHeader>
          <TableRow>
            <TableHead className="w-[220px]">字段</TableHead>
            <TableHead>变更前</TableHead>
            <TableHead>变更后</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {diffItems.map((item, index) => (
            <TableRow key={`${item.field || "field"}-${index}`}>
              <TableCell className="font-mono text-xs text-muted-foreground">{item.field || "/"}</TableCell>
              <TableCell className="max-w-[260px] break-words font-mono text-xs">
                <JsonView data={item.before} />
              </TableCell>
              <TableCell className="max-w-[260px] break-words font-mono text-xs">
                <JsonView data={item.after} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

// 一键复制按钮，成功后短暂切换为对勾，风格对齐聊天区代码块的复制交互
function CopyButton({ value, label = "复制" }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={handleCopy}
      title={label}
      aria-label={label}
      className="h-7 gap-1.5 px-2 text-xs text-muted-foreground hover:text-ink"
    >
      {copied ? <Check className="h-3.5 w-3.5 text-success" /> : <Copy className="h-3.5 w-3.5" />}
      {copied ? "已复制" : label}
    </Button>
  );
}

// 人类可读的字节体积
const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

// 变更前/后快照渲染成可整段复制的原始 JSON：语法高亮 + 行号，方便复制出去协助排查
function JsonCodeBlock({ value }: { value?: string | null }) {
  const text = useMemo(() => {
    const parsed = parseJson(value);
    if (parsed === null || parsed === undefined || parsed === "") return "";
    return typeof parsed === "string" ? parsed : JSON.stringify(parsed, null, 2);
  }, [value]);

  if (!text) {
    return (
      <div className="rounded-lg border border-dashed border-rule py-12 text-center text-sm text-muted-foreground">
        暂无快照数据
      </div>
    );
  }

  const lineCount = text.split("\n").length;
  const byteSize = new Blob([text]).size;

  return (
    <div className="overflow-hidden rounded-lg border border-rule bg-paper">
      <div className="flex items-center justify-between border-b border-rule px-3 py-1.5">
        <span className="font-mono text-[11px] font-medium text-muted-foreground">
          {"{}"} JSON · {lineCount} 行 · {formatBytes(byteSize)}
        </span>
        <CopyButton value={text} />
      </div>
      <div className="max-h-[440px] overflow-auto">
        <SyntaxHighlighter
          language="json"
          style={oneLight}
          PreTag="div"
          showLineNumbers
          customStyle={{
            margin: 0,
            padding: "0.75rem 1rem",
            background: "transparent",
            fontSize: "13px",
            lineHeight: "1.5"
          }}
          codeTagProps={{ style: { background: "transparent" } }}
          lineNumberStyle={{ minWidth: "2.5em", color: "var(--color-rule-2)" }}
        >
          {text}
        </SyntaxHighlighter>
      </div>
    </div>
  );
}

function ContextPanel({ detail }: { detail: BizChangeLog | null }) {
  if (!detail) return null;
  const rows = [
    ["业务类型", labelOf(BIZ_TYPE_OPTIONS, detail.bizType)],
    ["业务 ID", detail.bizId],
    ["操作类型", labelOf(OPERATION_OPTIONS, detail.operationType)],
    ["操作人", detail.operatorName || detail.operatorId || "-"],
    ["角色", detail.operatorRole || "-"],
    ["IP", detail.ip || "-"],
    ["类名", detail.className || "-"],
    ["方法", detail.methodName || "-"],
    ["User-Agent", detail.userAgent || "-"]
  ];
  return (
    <div className="grid gap-2 rounded-lg border border-rule p-4 text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="grid grid-cols-[96px_1fr] gap-3">
          <span className="text-muted-foreground">{label}</span>
          <span className="min-w-0 break-words font-medium text-ink">{value}</span>
        </div>
      ))}
    </div>
  );
}

export function BizChangeLogPage() {
  const [pageNo, setPageNo] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZE_OPTIONS[0]);
  const [pageData, setPageData] = useState<PageResult<BizChangeLog> | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<BizChangeLog | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>("diff");
  const [filters, setFilters] = useState(createDefaultFilters);
  const [query, setQuery] = useState(filters);

  const records = pageData?.records || [];
  const total = pageData?.total || 0;
  const pages = pageData?.pages || 1;
  const current = pageData?.current || pageNo;
  const rangeStart = total === 0 ? 0 : (current - 1) * pageSize + 1;
  const rangeEnd = total === 0 ? 0 : Math.min((current - 1) * pageSize + records.length, total);

  const loadData = useCallback(async (currentPage = pageNo, nextQuery = query, size = pageSize) => {
    try {
      setLoading(true);
      const result = await getBizChangeLogsPage({
        current: currentPage,
        size,
        bizType: nextQuery.bizType || undefined,
        operationType: nextQuery.operationType || undefined,
        success: nextQuery.success === "" ? undefined : nextQuery.success === "true",
        bizId: nextQuery.bizId.trim() || undefined,
        operatorName: nextQuery.operatorName.trim() || undefined,
        beginTime: nextQuery.beginTime ? `${nextQuery.beginTime} 00:00:00` : undefined,
        endTime: nextQuery.endTime ? `${nextQuery.endTime} 23:59:59` : undefined
      });
      setPageData(result);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载变更审计日志失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  }, [pageNo, pageSize, query]);

  useEffect(() => {
    loadData(pageNo, query, pageSize);
  }, [loadData, pageNo, query, pageSize]);

  const handleSearch = () => {
    setPageNo(1);
    setQuery({ ...filters });
  };

  const handleReset = () => {
    const next = createDefaultFilters();
    setFilters(next);
    setPageNo(1);
    setQuery(next);
  };

  const handleOpenDetail = async (id: string, initialTab: DetailTab = "diff") => {
    setDetailOpen(true);
    setDetailTab(initialTab);
    setDetail(null);
    try {
      setDetailLoading(true);
      setDetail(await getBizChangeLog(id));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载审计详情失败"));
      console.error(error);
    } finally {
      setDetailLoading(false);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">变更审计</h1>
          <p className="admin-page-subtitle">查看业务数据的新增、修改、删除、启用与禁用记录</p>
        </div>
      </div>

      <div className="rounded-xl border border-rule bg-paper p-3">
        <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap lg:items-center">
          <Select
            value={filters.bizType || ALL_VALUE}
            onValueChange={(value) => setFilters((prev) => ({ ...prev, bizType: value === ALL_VALUE ? "" : value }))}
          >
            <SelectTrigger aria-label="业务类型筛选" className={cn("w-[136px]", FILTER_SELECT_TRIGGER_CLASS)}>
              <SelectValue placeholder="全部业务" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_VALUE}>全部业务</SelectItem>
              {BIZ_TYPE_OPTIONS.map((item) => (
                <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select
            value={filters.operationType || ALL_VALUE}
            onValueChange={(value) => setFilters((prev) => ({ ...prev, operationType: value === ALL_VALUE ? "" : value }))}
          >
            <SelectTrigger aria-label="操作类型筛选" className={cn("w-[120px]", FILTER_SELECT_TRIGGER_CLASS)}>
              <SelectValue placeholder="全部操作" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_VALUE}>全部操作</SelectItem>
              {OPERATION_OPTIONS.map((item) => (
                <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select
            value={filters.success || ALL_VALUE}
            onValueChange={(value) => setFilters((prev) => ({ ...prev, success: value === ALL_VALUE ? "" : value }))}
          >
            <SelectTrigger aria-label="结果筛选" className={cn("w-[112px]", FILTER_SELECT_TRIGGER_CLASS)}>
              <SelectValue placeholder="全部结果" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_VALUE}>全部结果</SelectItem>
              {SUCCESS_OPTIONS.map((item) => (
                <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Input
            className={cn("w-[188px]", FILTER_INPUT_CLASS)}
            value={filters.bizId}
            onChange={(event) => setFilters((prev) => ({ ...prev, bizId: event.target.value }))}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="业务 ID"
          />
          <Input
            className={cn("w-[144px]", FILTER_INPUT_CLASS)}
            value={filters.operatorName}
            onChange={(event) => setFilters((prev) => ({ ...prev, operatorName: event.target.value }))}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="操作人"
          />
          <div className="flex h-10 items-center rounded-md border border-rule bg-paper px-2 transition-colors focus-within:border-rule">
            <input
              type="date"
              value={filters.beginTime}
              max={filters.endTime || undefined}
              onChange={(event) => setFilters((prev) => ({ ...prev, beginTime: event.target.value }))}
              className="w-[116px] bg-transparent px-1 text-sm text-muted-foreground focus:outline-none"
            />
            <span className="px-0.5 text-muted-foreground">~</span>
            <input
              type="date"
              value={filters.endTime}
              min={filters.beginTime || undefined}
              onChange={(event) => setFilters((prev) => ({ ...prev, endTime: event.target.value }))}
              className="w-[116px] bg-transparent px-1 text-sm text-muted-foreground focus:outline-none"
            />
          </div>
          <div className="flex items-center gap-2">
            <Button className="admin-primary-gradient h-10 px-4" onClick={handleSearch}>
              <Search className="mr-1.5 h-4 w-4" />
              查询
            </Button>
            <Button variant="outline" className="h-10 border-rule px-4" onClick={handleReset}>
              重置
            </Button>
          </div>
        </div>
      </div>

      <Card className="overflow-hidden">
        <CardContent className="pt-4">
          {loading ? (
            <div className="py-10 text-center text-sm text-muted-foreground">加载中…</div>
          ) : records.length === 0 ? (
            <div className="py-10 text-center text-sm text-muted-foreground">暂无变更审计日志</div>
          ) : (
            <Table className="min-w-[1300px] [&_th]:h-10 [&_th]:py-2 [&_td]:py-2">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[140px]">业务</TableHead>
                  <TableHead className="w-[180px]">业务 ID</TableHead>
                  <TableHead className="w-[100px]">操作</TableHead>
                  <TableHead className="w-[320px]">描述</TableHead>
                  <TableHead className="w-[140px]">操作人</TableHead>
                  <TableHead className="w-[90px]">结果</TableHead>
                  <TableHead className="w-[150px]">时间</TableHead>
                  <TableHead className="sticky right-0 z-20 w-[168px] bg-paper text-left shadow-divider">
                    详情
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id} className="group text-[13px] hover:!bg-paper">
                    <TableCell>
                      <BizTypeCell bizType={item.bizType} />
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{item.bizId}</TableCell>
                    <TableCell>
                      <Badge variant="outline" className={cn("font-medium", operationBadgeClass(item.operationType))}>
                        {labelOf(OPERATION_OPTIONS, item.operationType)}
                      </Badge>
                    </TableCell>
                    <TableCell className="max-w-[320px] truncate text-ink" title={item.actionDesc || ""}>
                      {item.actionDesc || "-"}
                    </TableCell>
                    <TableCell>
                      <span className="block truncate text-ink" title={item.operatorName || item.operatorId || ""}>
                        {item.operatorName || item.operatorId || "-"}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="inline-flex items-center gap-1.5">
                        <span className={cn("h-1.5 w-1.5 rounded-full", item.success ? "bg-success" : "bg-error")} />
                        <span className="text-ink">{item.success ? "成功" : "失败"}</span>
                      </span>
                    </TableCell>
                    <TableCell>
                      <RelativeTime value={item.createTime} />
                    </TableCell>
                    <TableCell className="sticky right-0 z-10 bg-paper shadow-divider group-hover:bg-paper">
                      <div className="flex items-center gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 px-2.5 text-xs"
                          title="查看变更差异"
                          onClick={() => handleOpenDetail(item.id, "diff")}
                        >
                          <Eye className="h-4 w-4" />
                          详情
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-8 px-2.5 text-xs"
                          title="查看操作上下文"
                          onClick={() => handleOpenDetail(item.id, "context")}
                        >
                          <ScrollText className="h-4 w-4" />
                          上下文
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

      {pageData && total > 0 ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
          <span>
            共 {total} 条，显示 {rangeStart}-{rangeEnd}
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <span>每页</span>
            <Select
              value={String(pageSize)}
              onValueChange={(value) => {
                setPageSize(Number(value));
                setPageNo(1);
              }}
            >
              <SelectTrigger className="h-8 w-[92px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PAGE_SIZE_OPTIONS.map((size) => (
                  <SelectItem key={size} value={String(size)}>
                    {size} 条
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button variant="outline" size="sm" onClick={() => setPageNo(1)} disabled={current <= 1}>
              首页
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={current <= 1}
            >
              上一页
            </Button>
            <span>
              {current} / {pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(pages || 1, prev + 1))}
              disabled={current >= pages}
            >
              下一页
            </Button>
            <Button variant="outline" size="sm" onClick={() => setPageNo(pages || 1)} disabled={current >= pages}>
              末页
            </Button>
          </div>
        </div>
      ) : null}

      <Dialog open={detailOpen} onOpenChange={setDetailOpen}>
        <DialogContent className="max-w-[980px] max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <ShieldCheck className="h-5 w-5 text-accent" />
              审计详情
            </DialogTitle>
            <DialogDescription>
              {detail ? `${labelOf(BIZ_TYPE_OPTIONS, detail.bizType)} · ${detail.bizId}` : "加载变更快照"}
            </DialogDescription>
          </DialogHeader>
          {detailLoading ? (
            <div className="py-10 text-center text-sm text-muted-foreground">加载中…</div>
          ) : (
            <div className="space-y-4">
              {detail && detail.success === false ? (
                <div className="flex items-start gap-2 rounded-lg border border-error-rule bg-error-wash px-3 py-2 text-sm text-error">
                  <XCircle className="mt-0.5 h-4 w-4 shrink-0" />
                  <span className="min-w-0 break-words">{detail.errorMessage || "操作失败"}</span>
                </div>
              ) : null}
              <div className="inline-flex items-center gap-1 rounded-lg bg-paper p-1">
                {[
                  { key: "diff", label: "差异" },
                  { key: "before", label: "变更前" },
                  { key: "after", label: "变更后" },
                  { key: "context", label: "上下文" }
                ].map((tab) => (
                  <button
                    key={tab.key}
                    type="button"
                    onClick={() => setDetailTab(tab.key as DetailTab)}
                    className={
                      detailTab === tab.key
                        ? "rounded-md bg-paper px-3 py-1.5 text-sm font-medium text-ink shadow-sm"
                        : "rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-ink"
                    }
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
              {detailTab === "diff" ? <ChangeDiffTable detail={detail} /> : null}
              {detailTab === "before" ? <JsonCodeBlock value={detail?.beforeSnapshot} /> : null}
              {detailTab === "after" ? <JsonCodeBlock value={detail?.afterSnapshot} /> : null}
              {detailTab === "context" ? <ContextPanel detail={detail} /> : null}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
