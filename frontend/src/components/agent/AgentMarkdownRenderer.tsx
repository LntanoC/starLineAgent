/* eslint-disable */
// @ts-nocheck

import * as React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkCjkFriendly from "remark-cjk-friendly";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import { ImageIcon } from "lucide-react";

interface AgentMarkdownRendererProps {
  content: string;
}

/**
 * 模型文本按 .agent-md 的实验台排版渲染
 * react-markdown v9 不再收 className 必须自己包 div 否则排版全失效
 * 代码块不做语法高亮 走纸底 mono 素块；rehype-sanitize 兜底防注入
 * 流式半截 markdown 未闭合语法会退化成原文 不会炸
 */
export function AgentMarkdownRenderer({ content }: AgentMarkdownRendererProps) {
  return (
    <div className="agent-md">
      <ReactMarkdown
        remarkPlugins={[[remarkGfm, { singleTilde: false }], remarkCjkFriendly]}
        rehypePlugins={[rehypeRaw, rehypeSanitize]}
        components={{
          img({ src, ...props }) {
            const [hasError, setHasError] = React.useState(false);

            if (hasError) {
              return (
                <span className="my-2 flex items-center gap-2 text-[length:var(--agent-t-sm)] text-[color:var(--agent-faint)]">
                  <ImageIcon className="h-4 w-4" strokeWidth={1.5} />
                  <span>图片加载失败</span>
                </span>
              );
            }

            return (
              <img src={src} alt="" onError={() => setHasError(true)} loading="lazy" {...props} />
            );
          },
          a({ children, href, ...props }) {
            return (
              <a target="_blank" rel="noreferrer" href={href} {...props}>
                {children}
              </a>
            );
          }
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
