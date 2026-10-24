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
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BadCaseServiceTest {
    private final BadCaseRepository repo=mock(BadCaseRepository.class);
    private final BadCaseAnalyzer analyzer=mock(BadCaseAnalyzer.class);
    private final BadCaseService service=new BadCaseService(repo,analyzer,new BadCaseProperties());
    @Test void failedItemDoesNotStopBatchAndConflictIsIdempotent() {
        var a=new BadCaseRepository.Feedback("a","c","u",-1,null,null);
        var b=new BadCaseRepository.Feedback("b","c","u",-1,null,null);
        var c=new BadCaseRepository.Feedback("c","c","u",-1,null,null);
        var report=new BadCaseReport();
        when(repo.pendingFeedback(isNull(),anyInt())).thenReturn(List.of(a,b,c));
        when(analyzer.analyze(eq(a),anyLong())).thenThrow(new IllegalStateException("bad snapshot"));
        when(analyzer.analyze(eq(b),anyLong())).thenReturn(report);
        when(analyzer.analyze(eq(c),anyLong())).thenReturn(report);
        when(repo.insert(report)).thenReturn(true,false);
        var result=service.scan(true);
        assertThat(result.created()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);assertThat(result.scanned()).isEqualTo(3);
    }
    @Test void reviewedStatusRequiresHumanRootCause() {
        assertThatThrownBy(()->service.review("id",new BadCaseService.ReviewRequest(null,null,"NONE",null,null,"REVIEWED",0L),"admin"))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(repo);
    }
    @Test void concurrentReviewCannotOverwriteAnotherReviewer() {
        when(repo.find("id")).thenReturn(Optional.of(new BadCaseRecord("id",new BadCaseReport(),null,null,null,null,null,"PENDING",null,null,1,new Date(),new Date())));
        var request=new BadCaseService.ReviewRequest("OTHER","detail","NONE","","note","REVIEWED",0L);
        when(repo.review("id",request,"admin")).thenReturn(0);
        assertThatThrownBy(()->service.review("id",request,"admin")).isInstanceOf(ClientException.class).hasMessageContaining("刷新");
    }
}
