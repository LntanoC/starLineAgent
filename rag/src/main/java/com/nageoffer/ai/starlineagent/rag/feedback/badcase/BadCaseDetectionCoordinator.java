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
import com.nageoffer.ai.starlineagent.framework.badcase.api.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
@Slf4j @Service @RequiredArgsConstructor
public class BadCaseDetectionCoordinator {
 private final List<BadCaseDetector>detectors;private final BadCaseIntakeService intake;private final BadCaseEvidenceEnricher enricher;private final BadCaseProperties properties;
 private final AtomicBoolean scanning=new AtomicBoolean();
 public Map<String,Object> scan(String selected,boolean dryRun,boolean older){
  if(!properties.isEnabled())return Map.of("busy",false,"disabled",true);
  if(!scanning.compareAndSet(false,true))return Map.of("busy",true);
  int detected=0,created=0,failed=0,inserted=0;Map<String,Object>details=new LinkedHashMap<>();
  try{Instant until=Instant.now().minusSeconds(15);int limit=Math.max(1,Math.min(500,properties.getBatchSize()));
   for(var d:detectors){String mode=properties.detectorMode(d.detectorCode());if((selected!=null&&!selected.isBlank()&&!selected.equals(d.detectorCode()))||"OFF".equals(mode))continue;
    var ctx=new BadCaseDetector.BadCaseScanContext(until.minus(Duration.ofDays(older?30:7)),until,limit,dryRun||"OFF".equals(properties.getIntake().getMode())||"SHADOW".equals(mode)||"SHADOW".equals(properties.getIntake().getMode()));
    try{var result=d.scan(ctx);detected+=result.signals().size();int errors=0;
     for(var s:result.signals()){try{if(dryRun){intake.metric(d.detectorCode(),"dry_run_total");continue;}var accepted=intake.submit(s);if(accepted.created())created++;if(accepted.inserted())inserted++;}catch(Exception e){errors++;failed++;log.warn("Bad case signal rejected detector={} rule={} type={}",d.detectorCode(),s.ruleCode(),e.getClass().getSimpleName());}}
     if(errors==0)d.checkpoint(ctx);details.put(d.detectorCode(),Map.of("scanned",result.scanned(),"detected",result.signals().size(),"mode",mode,"errors",errors));
    }catch(Exception e){failed++;details.put(d.detectorCode(),Map.of("error",e.getClass().getSimpleName()));log.warn("Bad case detector failed detector={} type={}",d.detectorCode(),e.getClass().getSimpleName());}
   }
   if(!dryRun){intake.reconcileRunSubjects();intake.invalidateNoFollowup();enricher.enrich(limit);}
   return Map.of("busy",false,"scanned",detected,"created",created,"inserted",inserted,"failed",failed,"detectors",details);
  }finally{scanning.set(false);}
 }
}
