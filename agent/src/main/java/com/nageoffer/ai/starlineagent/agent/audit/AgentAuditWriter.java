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

package com.nageoffer.ai.starlineagent.agent.audit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A bounded side channel, with reserved completion slots and no work on the SSE thread. */
@Slf4j
@Component
@DependsOn("dataSource")
@RequiredArgsConstructor
public class AgentAuditWriter {
    private final AgentAuditProperties properties;
    private BlockingQueue<Job> events;
    private final BlockingQueue<Job> completions = new ArrayBlockingQueue<>(64);
    private final AtomicLong bytes = new AtomicLong();
    private volatile boolean running;
    private Thread worker;

    @PostConstruct
    public void start() {
        events=new ArrayBlockingQueue<>(Math.max(8,properties.getQueueCapacity()));
        if(!properties.isEnabled())return;
        running=true;
        worker=new Thread(this::drain,"agent-audit");
        worker.setDaemon(true);
        worker.start();
    }
    public boolean offer(long size, boolean terminal, Runnable action, Runnable failure) {
        long cost=Math.max(256,size);
        long limit=Math.max(1048576,properties.getMaxQueueBytes())/(terminal?1:2);
        long total=bytes.addAndGet(cost);
        if(!running||total>limit){bytes.addAndGet(-cost);failure.run();return false;}
        Job job=new Job(cost,action,failure);
        if(!(terminal?completions:events).offer(job)){bytes.addAndGet(-cost);failure.run();return false;}
        return true;
    }
    private void drain() {
        while(running||!events.isEmpty()||!completions.isEmpty()){
            try{
                Job job=completions.poll();
                if(job==null) job=events.poll(200,TimeUnit.MILLISECONDS);
                if(job==null) continue;
                try {
                    boolean saved=false;
                    for(int attempt=0;attempt<3&&!saved;attempt++){
                        try{job.action.run();saved=true;}
                        catch(Exception error){
                            if(attempt==2){job.failure.run();log.warn("Agent audit write failed, type={}",error.getClass().getSimpleName());}
                            else Thread.sleep(100L*(attempt+1));
                        }
                    }
                }finally{bytes.addAndGet(-job.bytes);}
            }catch(InterruptedException ignored){if(!running) break;}
            catch(Exception error){log.warn("Agent audit worker recovered, type={}",error.getClass().getSimpleName());}
        }
    }
    @PreDestroy
    public void close() throws InterruptedException {
        running=false;
        if(worker!=null){worker.join(5000);if(worker.isAlive())worker.interrupt();}
    }
    private record Job(long bytes,Runnable action,Runnable failure){}
}
