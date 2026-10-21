import * as React from "react";

import { useAgentChatStore } from "@/stores/agentChatStore";

interface AgentRawLogProps {
  onClose: () => void;
}

// 原始帧抽屉：照单全收本次连接的每一条 SSE 帧 供深度核对
export function AgentRawLog({ onClose }: AgentRawLogProps) {
  const frames = useAgentChatStore((state) => state.frames);
  const bodyRef = React.useRef<HTMLDivElement>(null);
  const stickRef = React.useRef(true);

  // 贴着底就跟随最新帧 往上翻查历史时不打扰
  React.useEffect(() => {
    const el = bodyRef.current;
    if (el && stickRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [frames.length]);

  const onScroll = () => {
    const el = bodyRef.current;
    if (!el) return;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  };

  return (
    <div className="agent-rawlog">
      <div className="agent-rawlog-head">
        <span className="agent-rawlog-title">原始帧 · {frames.length}</span>
        <button type="button" className="agent-rawlog-close" onClick={onClose} aria-label="关闭">
          ✕
        </button>
      </div>
      <div className="agent-rawlog-body" ref={bodyRef} onScroll={onScroll}>
        {frames.length === 0 ? (
          <div className="agent-rawlog-empty">还没有帧，发一条消息看看。</div>
        ) : (
          frames.map((frame) => (
            <div key={frame.id} className="agent-frame">
              <div className="agent-frame-head">
                <span className="agent-frame-ts">{frame.ts}</span>
                <span className="agent-frame-name">{frame.name}</span>
              </div>
              <pre className="agent-frame-pre">{renderData(frame.data)}</pre>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function renderData(data: unknown): string {
  if (typeof data === "string") return data;
  try {
    return JSON.stringify(data, null, 2);
  } catch {
    return String(data);
  }
}
