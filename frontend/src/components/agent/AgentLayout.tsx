import * as React from "react";
import { Github } from "lucide-react";

import { AgentRawLog } from "@/components/agent/AgentRawLog";
import { AgentSidebar } from "@/components/agent/AgentSidebar";
import { useGitHubStars } from "@/hooks/useGitHubStars";
import { getAgentMeta } from "@/services/agentService";
import type { AgentEngineMeta } from "@/types/agent";

export type AgentMetaState =
  | { status: "probing" }
  | { status: "online"; meta: AgentEngineMeta }
  | { status: "offline"; message: string };

// 进页拉一次 /agent/v1/meta 点亮徽标与框架信息块
function useAgentMeta(): AgentMetaState {
  const [state, setState] = React.useState<AgentMetaState>({ status: "probing" });

  React.useEffect(() => {
    let alive = true;
    getAgentMeta()
      .then((meta) => {
        if (alive) setState({ status: "online", meta });
      })
      .catch((error) => {
        if (alive) {
          setState({ status: "offline", message: (error as Error).message || "连接失败" });
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  return state;
}

interface AgentHeaderProps {
  meta: AgentMetaState;
  rawOpen: boolean;
  onToggleRaw: () => void;
}

function AgentHeader({ meta, rawOpen, onToggleRaw }: AgentHeaderProps) {
  const starCount = useGitHubStars();

  const starLabel = starCount === null ? null : starCount.toLocaleString("en-US");

  const badgeName =
    meta.status === "online" ? meta.meta.framework : meta.status === "probing" ? "探测中" : "离线";

  return (
    <header className="agent-header">
      <div className="agent-brand">
        <span className="agent-wordmark">RAGENT</span>
        <span className="agent-brand-sep">/</span>
        <span className="agent-brand-tag">智能体</span>
      </div>

      <div className="agent-header-center">
        <span className="agent-badge">
          <span className="agent-dot" data-status={meta.status} aria-hidden="true" />
          <span className="agent-badge-name">{badgeName}</span>
        </span>
        {meta.status === "online" ? (
          <span className="agent-badge-model">{meta.meta.model || "未配模型"}</span>
        ) : null}
        {meta.status === "offline" ? (
          <span className="agent-badge-err" title={meta.message}>
            连接失败
          </span>
        ) : null}
      </div>

      <div className="agent-header-right">
        <a
          href="https://github.com/nageoffer/ragent"
          target="_blank"
          rel="noreferrer"
          className="agent-head-btn"
          data-github="true"
          aria-label="打开 GitHub 仓库"
        >
          <Github className="h-3.5 w-3.5" strokeWidth={1.5} />
          {starLabel ? <span className="agent-btn-glyph">{starLabel}</span> : null}
        </a>
        <button
          type="button"
          className="agent-head-btn"
          data-on={rawOpen}
          onClick={onToggleRaw}
          aria-pressed={rawOpen}
        >
          <span className="agent-btn-glyph">{"{ }"}</span> 原始帧
        </button>
      </div>
    </header>
  );
}

interface AgentLayoutProps {
  children: React.ReactNode;
}

export function AgentLayout({ children }: AgentLayoutProps) {
  const [rawOpen, setRawOpen] = React.useState(false);
  const meta = useAgentMeta();

  return (
    <div className="agent-app">
      <AgentHeader meta={meta} rawOpen={rawOpen} onToggleRaw={() => setRawOpen((v) => !v)} />
      <div className="agent-body">
        <AgentSidebar />
        <main className="agent-main">{children}</main>
        {rawOpen ? <AgentRawLog onClose={() => setRawOpen(false)} /> : null}
      </div>
    </div>
  );
}
