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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 上下文体量口径：字符数作为 token 的粗代理，非文本块按零计
 * 链路追踪也复用此口径，保证两处数字可比
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentContextChars {

    public static int total(List<Msg> context) {
        int sum = 0;
        for (Msg msg : context) {
            sum += of(msg);
        }
        return sum;
    }

    static int of(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return 0;
        }
        int sum = 0;
        for (ContentBlock block : msg.getContent()) {
            sum += of(block);
        }
        return sum;
    }

    private static int of(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return length(text.getText());
        }
        if (block instanceof ThinkingBlock thinking) {
            return length(thinking.getThinking());
        }
        if (block instanceof ToolUseBlock toolUse) {
            return length(toolUse.getName())
                    + (toolUse.getInput() == null ? 0 : toolUse.getInput().toString().length());
        }
        if (block instanceof ToolResultBlock result) {
            return ofOutput(result);
        }
        return 0;
    }

    static int ofOutput(ToolResultBlock block) {
        if (block.getOutput() == null) {
            return 0;
        }
        int sum = 0;
        for (ContentBlock nested : block.getOutput()) {
            sum += of(nested);
        }
        return sum;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
