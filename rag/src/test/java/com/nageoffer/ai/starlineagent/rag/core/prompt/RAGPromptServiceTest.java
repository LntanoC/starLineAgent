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

package com.nageoffer.ai.starlineagent.rag.core.prompt;

import com.nageoffer.ai.starlineagent.rag.config.RAGConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RAGPromptServiceTest {

    /**
     * 基础模板已迁入 DB 由管理员可编辑，故此处只桩一段带标记的假模板
     * 断言落在本类的职责（选模板、清理、追加引用规则）上，不再断言模板正文内容
     */
    private static final String STUB_BASE_TEMPLATE = "# 桩基础模板\n\n正文占位";

    private static RAGPromptService service(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);

        AgentPromptResolver resolver = mock(AgentPromptResolver.class);
        when(resolver.resolve(any())).thenReturn(STUB_BASE_TEMPLATE);

        return new RAGPromptService(new PromptTemplateLoader(new DefaultResourceLoader()), resolver, properties);
    }

    private static PromptContext kbContext() {
        return PromptContext.builder()
                .kbContext("<content ref=\"1\">资料</content>")
                .build();
    }

    @Test
    void includesCitationRulesFromKnowledgePrompt() {
        String result = service(true).buildSystemPrompt(kbContext());

        assertTrue(result.contains("# 行内引用规则"));
        assertTrue(result.contains("[N](#cite-N)"));
        // 引用规则追加在基础模板之后
        assertTrue(result.indexOf("# 桩基础模板") < result.indexOf("# 行内引用规则"));

        // 引用只落在正文单元末尾，标题一律不带引用，示例本身也不能出现带引用的标题
        assertTrue(result.contains("标题与小标题一律不加引用"));
        assertTrue(result.replace("\r\n", "\n")
                .contains("## 二级标题\n\n第一部分包含要点 A 和要点 B。[1](#cite-1)"));
        assertFalse(result.contains("## 二级标题 ["));

        // 同一单元依据多份资料时连写编号
        assertTrue(result.contains("`[1](#cite-1)[3](#cite-3)`"));
        assertTrue(result.contains("由两份资料共同支撑。[1](#cite-1)[3](#cite-3)"));

        // 出处只允许数字角标：文档名与内部标签的禁令由基础模板统一声明，引用规则只声明自己是它的唯一例外
        assertTrue(result.contains("本章只豁免一件事：出处改以数字角标呈现"));
        assertTrue(result.contains("不报文档名、不出现输入侧的标签与属性字样、不罗列来源清单"));
    }

    @Test
    void omitsCitationRulesWhenCitationDisabled() {
        String result = service(false).buildSystemPrompt(kbContext());

        assertFalse(result.contains("# 行内引用规则"));
        assertFalse(result.contains("#cite-"), "关闭引用时不得向模型提及角标格式");
        assertFalse(result.contains("ref=\""), "关闭引用时上下文不注入编号，提示词也不应描述该属性");
    }

    @Test
    void usesResolvedTemplateAsBase() {
        // 回答模板统一从 AgentPromptResolver 读取
        for (boolean citationEnabled : new boolean[]{true, false}) {
            String result = service(citationEnabled).buildSystemPrompt(kbContext());
            assertTrue(result.startsWith(STUB_BASE_TEMPLATE));
        }
    }

}
