import * as React from "react";

import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";

/** 待机页取几条 后台配得少就少显示 不凑数 */
const SAMPLE_LIMIT = 4;

type SampleState =
  | { status: "loading" }
  | { status: "ready"; items: string[] }
  | { status: "error" };

/** 取过一次就记在模块里：换会话回到待机页不该重新洗牌 */
let cachedQuestions: string[] | null = null;

function useSampleQuestions() {
  const [state, setState] = React.useState<SampleState>(() =>
    cachedQuestions ? { status: "ready", items: cachedQuestions } : { status: "loading" }
  );
  const aliveRef = React.useRef(true);

  const load = React.useCallback(() => {
    setState({ status: "loading" });
    listSampleQuestions(SAMPLE_LIMIT)
      .then((rows) => {
        const items = (rows ?? [])
          .map((row) => row.question?.trim())
          .filter((question): question is string => Boolean(question));
        cachedQuestions = items;
        if (aliveRef.current) setState({ status: "ready", items });
      })
      .catch(() => {
        if (aliveRef.current) setState({ status: "error" });
      });
  }, []);

  React.useEffect(() => {
    aliveRef.current = true;
    if (!cachedQuestions) load();
    return () => {
      aliveRef.current = false;
    };
  }, [load]);

  return { state, reload: load };
}

/** 示例问题：来自后台配置 点一条只填进输入框 发不发由用户决定 */
function AgentSampleQuestions() {
  const setDraft = useAgentChatStore((store) => store.setDraft);
  const isAdmin = useAuthStore((store) => store.user?.role === "admin");
  const { state, reload } = useSampleQuestions();

  // 加载中不占位也不放骨架屏：请求就在同一屏内 闪一下比空框好
  if (state.status === "loading") {
    return null;
  }

  const questions = state.status === "ready" ? state.items : [];
  const isBlank = questions.length === 0;

  return (
    <section className="agent-empty-try">
      <span className="agent-empty-try-label">试试这些</span>
      {isBlank ? (
        <div className="agent-empty-blank">
          {state.status === "error" ? (
            <p>示例问题没取到，不影响提问——直接在下面写下你想问的就行。</p>
          ) : (
            <p>
              管理控制台还没有配置示例问题，直接在下面输入框写下你想问的就行
              {/* 管理员多一节句内旁注：指路而已 不给钮形——那个位置正常态摆的是能点的问句 摆一枚离开本页的钮就成了这屏最像动作的东西 */}
              {isAdmin ? (
                <>
                  ；也可以
                  <a
                    className="agent-empty-blank-link"
                    href="/admin/sample-questions"
                    target="_blank"
                    rel="noreferrer"
                  >
                    去后台添加几条
                  </a>
                </>
              ) : null}
              。
            </p>
          )}
          {state.status === "error" ? (
            <button type="button" className="agent-empty-blank-btn" onClick={reload}>
              重试
            </button>
          ) : null}
        </div>
      ) : (
        <div className="agent-empty-chips">
          {questions.map((question) => (
            <button
              key={question}
              type="button"
              className="agent-empty-chip"
              title="点击填入输入框"
              onClick={() => setDraft(question)}
            >
              <span>{question}</span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

/**
 * 待机空态：展示后台配置的示例问句；未配置时保留引导提示。
 */
export function AgentWelcomeScreen() {
  return (
    <div className="agent-stream-empty">
      <div className="agent-empty-wrap">
        <div className="agent-empty-card">
          <AgentSampleQuestions />
        </div>
      </div>
    </div>
  );
}
