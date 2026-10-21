import * as React from "react";

import { AgentTurnItem } from "@/components/agent/AgentTurn";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import type { AgentTurn } from "@/types/agent";

/** 待机页取几条 后台配得少就少显示 不凑数 */
const SAMPLE_LIMIT = 4;

/** 图注带：四格按时间顺序讲一次问答的四段 ▷○●▮ 只在这里解释一次 */
const STEPS = [
  ["▷", "提问", "你说的原话"],
  ["○", "思考", "它怎么想的"],
  ["●", "工具", "调了什么、返回什么"],
  ["▮", "答复", "最后怎么答"]
];

/**
 * 预演轨迹：结构与真轨迹完全一致 走同一个 AgentTurnItem
 * 时刻写死不取当前时间 空态每次渲染都该长一样 也方便截图自验
 * 每行都按吃满内容列来写 短句配 880 宽的卡右边永远空一截 读作"这卡太宽"
 * 走两轮「思考→工具」：一问三小块 拆成两次检索是真实跑法 也把 ReAct 的循环演出来
 * 顺带把卡撑到六行——整块够厚 居中才不至于上下各空一大片（别靠摊开结果面板凑高度 门面返回本就与答复同源 摊开只是把同一段话说两遍）
 */
const DEMO_TURN: AgentTurn = {
  id: "demo",
  index: 1,
  user: {
    id: "demo-u",
    role: "user",
    content: "公司的数据安全是怎么做的？传输、权限、离职这几块分别有什么要求",
    createdAt: "2026-01-01T09:41:03"
  },
  assistants: [
    {
      id: "demo-a",
      role: "assistant",
      content: "",
      status: "done",
      elapsedMs: 4200,
      createdAt: "2026-01-01T09:41:03",
      blocks: [
        {
          id: 1,
          kind: "reasoning",
          at: "09:41:03",
          text: "问的是制度类问题，答案属于知识库里沉淀的静态规范，先按传输与权限这两块检索原文，不必调业务系统的实时接口。"
        },
        {
          id: 2,
          kind: "tool",
          at: "09:41:04",
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          // 各自独批
          batchId: "demo-0",
          callIndex: 0,
          durationMs: 1200,
          result:
            "## 数据安全管理规范（2025 修订）\n\n- 传输：全链路 TLS 1.3，内网服务之间互相调用同样不走明文，证书每年轮换一次\n- 权限：按岗位最小化授权，每季度复核一次，转岗当天回收原岗位的多余权限"
        },
        {
          id: 3,
          kind: "reasoning",
          at: "09:41:05",
          text: "传输和权限有了，离职回收落在另一份操作手册里，再检索一次把这块补齐再作答。"
        },
        {
          id: 4,
          kind: "tool",
          at: "09:41:06",
          name: "search_knowledge",
          displayName: "知识库检索",
          status: "done",
          batchId: "demo-1",
          callIndex: 0,
          durationMs: 900,
          result:
            "## 员工离职操作手册（2025 修订）\n\n- 账号回收：离职当日回收全部系统账号，含第三方 SaaS 与外部协作平台，回收记录留存半年备查"
        },
        {
          id: 5,
          kind: "answer",
          at: "09:41:07",
          text: "按《数据安全管理规范（2025 修订）》与《员工离职操作手册》，这件事拆成三层来做：\n\n- **传输加密**：全链路 TLS 1.3，内网服务之间互相调用同样不走明文，证书每年轮换一次\n- **权限最小化**：按岗位授权，每季度复核一次，转岗当天回收原岗位的多余权限\n- **离职回收**：当日回收全部系统账号，含第三方 SaaS 与外部协作平台"
        }
      ]
    }
  ]
};

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
 * 待机空态：一张卡讲清"你的话会怎样被打出来" 再给一行能点的问句
 * 演示 + 图注带 + 问句行收进同一张卡整块居中 留白封顶不摊成两块死区 排布见 globals.css
 */
export function AgentWelcomeScreen() {
  return (
    <div className="agent-stream-empty">
      <div className="agent-empty-wrap">
        {/* 三段一张卡：边框圆角挂外层 figure 只管「演示 + 注」这对语义（figcaption 必须是 figure 的首尾子元素 问句行只能落在外层） */}
        <div className="agent-empty-card">
          <figure className="agent-empty-figure">
            <div className="agent-empty-demo" aria-hidden="true">
              <AgentTurnItem turn={DEMO_TURN} note="示例" />
            </div>
            <figcaption className="agent-empty-cap">
              {STEPS.map(([glyph, name, desc]) => (
                <span key={glyph} className="agent-empty-step">
                  <span className="agent-empty-step-head">
                    {/* 答复那一格的字符跟着卡里的答复节点转橙 图例与被解释的东西不许两个颜色 */}
                    <span
                      className="agent-empty-step-glyph"
                      data-answer={glyph === "▮" ? "true" : undefined}
                    >
                      {glyph}
                    </span>
                    {name}
                  </span>
                  <span className="agent-empty-step-desc">{desc}</span>
                </span>
              ))}
            </figcaption>
          </figure>
          <AgentSampleQuestions />
        </div>
      </div>
    </div>
  );
}
