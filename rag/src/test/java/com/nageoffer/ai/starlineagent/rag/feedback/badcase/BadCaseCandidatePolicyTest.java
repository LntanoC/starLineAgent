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

package com.nageoffer.ai.starlineagent.rag.feedback.badcase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class BadCaseCandidatePolicyTest {
 private final ObjectMapper json=new ObjectMapper();
 @Test void messageKeyIsSharedAcrossSignalSources(){var p=new BadCaseDedupPolicy();assertThat(p.key(signal("AGENT_RUNTIME","AGENT_MESSAGE","m","m"))).isEqualTo(p.key(signal("USER_CORRECTION","AGENT_MESSAGE","m","m")));}
 @Test void businessSubjectsWorkWithoutMessage(){assertThat(new BadCaseDedupPolicy().key(signal("STARLINE_DELIVERY","STARLINE_REMINDER","e",null))).isEqualTo("STARLINE_REMINDER:e");}
 @Test void oldReplayAndLowConfidenceNeverReopenFixed(){Instant done=Instant.now();assertThat(BadCaseDedupPolicy.shouldReopen("FIXED",true,done,done.minusSeconds(1),"HIGH")).isFalse();assertThat(BadCaseDedupPolicy.shouldReopen("FIXED",false,done,done.plusSeconds(1),"LOW")).isFalse();assertThat(BadCaseDedupPolicy.shouldReopen("REVIEWED",false,done,done.plusSeconds(1),"HIGH")).isFalse();assertThat(BadCaseDedupPolicy.shouldReopen("FIXED",true,done,done.plusSeconds(1),"HIGH")).isTrue();}
 @Test void incompleteEvidenceForcesUncertain(){assertThat(BadCaseAutoReviewService.effectiveVerdict("LIKELY_OK","PARTIAL","COMPLETE")).isEqualTo("UNCERTAIN");assertThat(BadCaseAutoReviewService.effectiveVerdict("LIKELY_BAD","COMPLETE","DISABLED")).isEqualTo("UNCERTAIN");assertThatThrownBy(()->BadCaseAutoReviewService.effectiveVerdict("REVIEWED","COMPLETE","COMPLETE")).isInstanceOf(IllegalArgumentException.class);}
 @Test void fixedSizeSamplingIsReproducibleUnderInputReordering(){var ids=List.of("a","b","c","d","e");var reverse=new ArrayList<>(ids);Collections.reverse(reverse);assertThat(UnvotedSampleDetector.select(ids,"seed",2)).hasSize(2).isEqualTo(UnvotedSampleDetector.select(reverse,"seed",2));}
 @Test void sampleApprovalRequiresAssertionsAndValidIds()throws Exception{var noAssert=json.readTree("[{\"id\":\"t1\",\"question\":\"公开时间尚未公布\",\"phase\":\"measure\"}]");assertThatThrownBy(()->BadCaseEvalSampleService.validateTurns(noAssert,"CONVERSATION",true)).hasMessageContaining("断言");assertThatThrownBy(()->BadCaseEvalSampleService.identifier("../oops")).hasMessageContaining("ID");}
 @Test void questionLongerThanRunnerLimitIsRejected()throws Exception{var t=json.createObjectNode().put("id","t1").put("question","长".repeat(501));assertThatThrownBy(()->BadCaseEvalSampleService.validateTurns(json.createArrayNode().add(t),"CONVERSATION",true)).hasMessageContaining("500");}
 @Test void businessSamplesNeedFixtureAndTestReference()throws Exception{assertThatThrownBy(()->BadCaseEvalSampleService.validateTurns(json.readTree("[{\"id\":\"t1\"}]"),"SERVICE_TEST",true)).hasMessageContaining("fixture");}
 @Test void secretsAndPersonalIdentifiersAreRedacted(){String value=BadCaseSanitizer.text("email=person@example.com api_key=sk-supersecret123 password=hunter123 Authorization: Bearer abcdef 13812345678",1000);assertThat(value).doesNotContain("person@example.com","sk-supersecret123","hunter123","13812345678");assertThat(BadCaseSanitizer.value(Map.of("password","abc"))).isEqualTo(Map.of("password","[REDACTED]"));}
 @Test void encodedEvidenceHasAHardUtf8ByteLimitAndRemainsValidJson()throws Exception {
  var service=new BadCaseIntakeService(null,json,null,new BadCaseDedupPolicy(),new BadCaseProperties());
  Map<String,Object>payload=new LinkedHashMap<>();for(int i=0;i<20;i++)payload.put("field"+i,"中文内容\"".repeat(1000));
  String encoded=service.evidence(payload);assertThat(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32000);assertThat(json.readTree(encoded).path("truncated").asBoolean()).isTrue();
 }
 @Test void yamlBooleanAliasesAndInvalidModesCannotEnableDisabledDetectors(){
  var p=new BadCaseProperties();var detector=new BadCaseProperties.Detector();p.getDetectors().put("behavior",detector);
  detector.setMode("false");assertThat(p.detectorMode("behavior")).isEqualTo("OFF");detector.setMode("true");assertThat(p.detectorMode("behavior")).isEqualTo("ON");
  detector.setMode("typo");assertThat(p.detectorMode("behavior")).isEqualTo("OFF");p.getIntake().setMode("FALSE");assertThat(p.getIntake().getMode()).isEqualTo("OFF");
 }
 private BadCaseSignal signal(String source,String type,String subject,String message){return new BadCaseSignal(source,"RULE","HIGH","S3",type,subject,message,"c","u",null,"one","title",Instant.now(),"v1",Map.of());}
}
