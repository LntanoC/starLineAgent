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

package com.nageoffer.ai.starlineagent.agent.badcase;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ConversationSignalTest {
 @Test void explicitCorrectionHasAnObject(){assertThat(UserCorrectionDetector.explicitCorrection("不对，官方写的是明天12:00，你把时间改过来")).isTrue();assertThat(UserCorrectionDetector.explicitCorrection("你刚才说的时间错了")).isTrue();}
 @Test void negationAndTopicSwitchAreExcluded(){for(String s:new String[]{"不是说你错了","没错，继续","不用纠正","不是周杰伦，我想问林俊杰","继续","不对，再帮我查一下"})assertThat(UserCorrectionDetector.explicitCorrection(s)).as(s).isFalse();}
 @Test void similarityNormalizesPunctuationAndDoesNotMergeDifferentArtists(){assertThat(ConversationBehaviorDetector.similarity("周杰伦上海什么时候开票？","周杰伦上海什么时候开票")).isEqualTo(1);assertThat(ConversationBehaviorDetector.similarity("周杰伦上海什么时候开票","林俊杰广州有什么演出")).isLessThan(.82);}
 @Test void collectingDifferentRequiredFieldsIsProgress(){assertThat(ConversationBehaviorDetector.stalledClarification(java.util.List.of("请问你在哪个城市？","请提供演出的日期？","请问你需要开票还是开场提醒？"))).isFalse();assertThat(ConversationBehaviorDetector.stalledClarification(java.util.List.of("请问你在哪个城市？","请问你在哪个城市？","请问你在哪个城市？"))).isTrue();}
}
