/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.starlineagent.rag.constant;

/**
 * RAG 系统常量类
 *
 * <p>
 * 定义 RAG（Retrieval-Augmented Generation）系统中使用的各种常量配置，包括不限于：
 * <ul>
 *   <li>查询改写提示词模板</li>
 *   <li>RAG 问答提示词模板</li>
 *   <li>系统对话提示词模板</li>
 *   <li>......</li>
 * </ul>
 * </p>
 *
 * <p>
 * 这些常量主要用于控制 RAG 系统中的查询优化、文档检索和智能问答流程
 * </p>
 */
public class RAGConstant {

    /**
     * 查询改写 + 多问句拆分提示词模板路径
     * 要求同时返回改写后的单条查询和子问题列表
     */
    public static final String QUERY_REWRITE_AND_SPLIT_PROMPT_PATH = "prompt/user-question-rewrite.st";


    /**
     * 会话标题生成提示词模板路径
     * 通过 {@code {title_max_chars}} 与 {@code {question}} 控制标题长度与输入问题
     */
    public static final String CONVERSATION_TITLE_PROMPT_PATH = "prompt/conversation-title.st";


    /**
     * 知识资料回答的行内引用规则
     * <p>
     * 仅在 {@code rag.citation.enabled=true} 且存在知识库上下文时，由 Prompt 编排层追加
     */
    public static final String ANSWER_CITATION_RULES_PROMPT_PATH = "prompt/answer-citation-rules.st";


    // ==================== 上下文格式化模板（单文件多 section） ====================

    /**
     * 上下文格式化模板文件路径
     * <p>
     * 包含所有上下文格式化所需的 section，通过 {@code --- section: name ---} 分隔，
     * 使用 {@code PromptTemplateLoader.renderSection(path, section, slots)} 渲染
     */
    public static final String CONTEXT_FORMAT_PATH = "prompt/context-format.st";
}
