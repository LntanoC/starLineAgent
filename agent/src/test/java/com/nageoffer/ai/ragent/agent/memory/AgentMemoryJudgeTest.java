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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryDecision.Action;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryJudgeTest {

    private static final List<AgentMessageDO> ONE_TURN = List.of(message("m-1", "c-1", "清空你保存的关于我的全部长期记忆"));

    private Model model;
    private AgentProperties agentProperties;
    private AgentMemoryProperties memoryProperties;
    private AgentPromptResolver promptResolver;
    private AgentMemoryJudge judge;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        agentProperties = new AgentProperties();
        agentProperties.getChat().setProvider("deepseek");
        memoryProperties = new AgentMemoryProperties();
        promptResolver = mock(AgentPromptResolver.class);
        when(promptResolver.render(eq(AgentPromptSlot.AGENT_MEMORY_EXTRACTION), anyMap())).thenReturn("抽取提示词");
        judge = new AgentMemoryJudge(model, agentProperties, promptResolver, memoryProperties);
    }

    @Test
    void shouldKeepAddsAfterClear() {
        answer("[{\"action\":\"CLEAR\"},{\"action\":\"ADD\",\"content\":\"用户住在南京\"}]");

        List<AgentMemoryDecision> decisions = judge.judge(List.of(), ONE_TURN);

        assertThat(decisions).extracting(AgentMemoryDecision::action).containsExactly(Action.CLEAR, Action.ADD);
        assertThat(decisions.get(1).content()).isEqualTo("用户住在南京");
    }

    @Test
    void shouldCollapseRepeatedClear() {
        answer("[{\"action\":\"CLEAR\"},{\"action\":\"CLEAR\"}]");

        assertThat(judge.judge(List.of(), ONE_TURN)).extracting(AgentMemoryDecision::action)
                .containsExactly(Action.CLEAR);
    }

    /**
     * 「清空后只记住我住南京」判成 CLEAR + SUPERSEDE：先清空再让替换指不着目标自然丢弃，南京就被静默吞掉了
     */
    @Test
    void shouldRejectClearMixedWithSupersede() {
        answer("[{\"action\":\"CLEAR\"},{\"action\":\"SUPERSEDE\",\"id\":\"m-7\",\"content\":\"用户住在南京\"}]");

        assertThatThrownBy(() -> judge.judge(List.of(new AgentMemoryItem("m-7", "用户住在杭州")), ONE_TURN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERSEDE");
    }

    @Test
    void shouldRejectClearMixedWithRetract() {
        answer("[{\"action\":\"RETRACT\",\"id\":\"m-7\"},{\"action\":\"CLEAR\"}]");

        assertThatThrownBy(() -> judge.judge(List.of(new AgentMemoryItem("m-7", "用户住在杭州")), ONE_TURN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETRACT");
    }

    /**
     * 一批跨会话时换会话处要隔开，否则 B 里的「它」会被指到 A 说过的东西上；同会话连着的不隔
     */
    @Test
    void shouldSeparateConversationsInMaterial() {
        answer("[{\"action\":\"NOOP\"}]");
        List<AgentMessageDO> pending = List.of(
                message("m-1", "c-A", "我住在杭州"),
                message("m-2", "c-A", "平时写 Java"),
                message("m-3", "c-B", "我搬到南京了"),
                message("m-4", "c-A", "它的屏幕多大"));

        judge.judge(List.of(), pending);

        String turns = renderedTurns();
        String separator = "——（以下换到另一段对话）——";
        assertThat(turns).contains(
                "- 我住在杭州\n- 平时写 Java\n" + separator + "\n- 我搬到南京了\n" + separator + "\n- 它的屏幕多大");
        assertThat(turns.split(separator, -1)).hasSize(3);
    }

    @Test
    void shouldKeepRenderedPromptAsSingleUserMessage() {
        answer("[]");
        memoryProperties.setContextWindowChars(20_000);

        judge.judge(List.of(new AgentMemoryItem("m-7", "用户住在杭州")), ONE_TURN);

        List<Msg> messages = captureMessages();
        assertThat(messages).extracting(Msg::getRole).containsExactly(MsgRole.USER);
        assertThat(messages.get(0).getTextContent()).isEqualTo("抽取提示词");
        assertThat(captureSlots()).containsEntry("memory_max_chars", "1500")
                .containsEntry("existing_memories", "<existing_memories>\nid=m-7 | 用户住在杭州\n</existing_memories>")
                .containsEntry("recent_turns", "<recent_turns>\n- 清空你保存的关于我的全部长期记忆\n</recent_turns>");
        verify(model).stream(anyList(), isNull(), any());
    }

    @Test
    void shouldUseNonStreamingAgentModelWithExplicitTimeoutAndRetries() {
        agentProperties.setMaxRetries(2);
        answer("[]");

        judge.judge(List.of(), ONE_TURN);

        GenerateOptions options = captureOptions();
        assertThat(options.getStream()).isFalse();
        assertThat(options.getTemperature()).isEqualTo(0.3D);
        assertThat(options.getTopP()).isNull();
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getExecutionConfig().getTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(options.getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void shouldNotSendThinkingParamToOtherProviders() {
        agentProperties.getChat().setProvider("bailian");
        answer("[]");

        judge.judge(List.of(), ONE_TURN);

        assertThat(captureOptions().getAdditionalBodyParams()).doesNotContainKey("thinking");
    }

    @Test
    void shouldExtractOnlyTextFromNonStreamingResponse() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(
                ChatResponse.builder().content(List.of(
                        ThinkingBlock.builder().thinking("这些推演不能写入记忆").build(),
                        TextBlock.builder().text("[{\"action\":\"ADD\",").build(),
                        TextBlock.builder().text("\"content\":\"用户住在南京\"}]").build()))
                        .finishReason("stop").build()));

        assertThat(judge.judge(List.of(), ONE_TURN)).containsExactly(AgentMemoryDecision.add("用户住在南京"));
    }

    @Test
    void shouldRejectMissingOrIncompleteFinishEvenWithValidJson() {
        for (String finishReason : Arrays.asList("length", "content_filter", "aborted", "tool_calls", null)) {
            when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response("[]", finishReason)));

            assertThatThrownBy(() -> judge.judge(List.of(), ONE_TURN))
                    .as(String.valueOf(finishReason))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("未正常结束");
        }
    }

    @Test
    void shouldRejectMultipleResponsesInNonStreamingMode() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(
                response("[", "length"), response("]", null)));

        assertThatThrownBy(() -> judge.judge(List.of(), ONE_TURN))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldRejectEmptyResponseStream() {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.empty());

        assertThatThrownBy(() -> judge.judge(List.of(), ONE_TURN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("返回为空");
    }

    @Test
    void shouldRejectMissingOrBlankResponseContent() {
        for (List<ContentBlock> content : Arrays.asList(null, List.<ContentBlock>of(),
                List.<ContentBlock>of(ThinkingBlock.builder().thinking("只有思考，没有正文").build()),
                List.<ContentBlock>of(TextBlock.builder().text(" \n ").build()))) {
            when(model.stream(anyList(), any(), any()))
                    .thenReturn(Flux.just(ChatResponse.builder().content(content).finishReason("stop").build()));

            assertThatThrownBy(() -> judge.judge(List.of(), ONE_TURN))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("返回为空");
        }
    }

    @Test
    void shouldPropagateModelFailureToPipeline() {
        IllegalStateException failure = new IllegalStateException("模型调用超时");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.error(failure));

        assertThatThrownBy(() -> judge.judge(List.of(), ONE_TURN)).isSameAs(failure);
    }

    private void answer(String raw) {
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response(raw, "stop")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> captureSlots() {
        ArgumentCaptor<Map<String, String>> slots = ArgumentCaptor.forClass(Map.class);
        verify(promptResolver).render(eq(AgentPromptSlot.AGENT_MEMORY_EXTRACTION), slots.capture());
        return slots.getValue();
    }

    private String renderedTurns() {
        return captureSlots().get("recent_turns");
    }

    @SuppressWarnings("unchecked")
    private List<Msg> captureMessages() {
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).stream(messages.capture(), any(), any());
        return messages.getValue();
    }

    private GenerateOptions captureOptions() {
        ArgumentCaptor<GenerateOptions> options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model).stream(anyList(), any(), options.capture());
        return options.getValue();
    }

    private ChatResponse response(String text, String finishReason) {
        List<ContentBlock> content = text == null ? List.of() : List.of(TextBlock.builder().text(text).build());
        return ChatResponse.builder().content(content).finishReason(finishReason).build();
    }

    private static AgentMessageDO message(String id, String conversationId, String content) {
        return AgentMessageDO.builder()
                .id(id)
                .conversationId(conversationId)
                .userId("u-1")
                .role("user")
                .content(content)
                .build();
    }
}
