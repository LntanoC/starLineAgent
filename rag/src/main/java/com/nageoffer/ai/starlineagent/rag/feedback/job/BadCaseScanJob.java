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

package com.nageoffer.ai.starlineagent.rag.feedback.job;

import com.nageoffer.ai.starlineagent.rag.feedback.badcase.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BadCaseScanJob {
    private final BadCaseService service;
    private final BadCaseProperties properties;
    private final BadCaseRepository repository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Scheduled(fixedDelayString="${rag.feedback.bad-case.scan-delay-ms:300000}",initialDelay=60000)
    public void scan() { run(false); }

    /** 定期补扫历史，避免重启/停机超过 lookback 窗口后漏单；每批仍有限额。 */
    @Scheduled(fixedDelay=3600000,initialDelay=90000)
    public void catchUp() { run(true); }

    @Scheduled(fixedDelay=86400000,initialDelay=120000)
    public void archive() {
        if(!properties.isEnabled()) return;
        try { int count=repository.archiveResolved(properties.getRetentionDays()); if(count>0) {jdbc.update("UPDATE t_bad_case_signal SET evidence='{\"archived\":true}'::jsonb WHERE bad_case_id IN (SELECT id FROM t_bad_case WHERE deleted=1 AND archived_at IS NOT NULL)");log.info("归档 bad case {} 条",count); } }
        catch(Exception e) { log.warn("bad case 归档失败",e); }
    }
    private void run(boolean includeOlder) {
        if(!properties.isEnabled()) return;
        try {
            var result=service.scan(includeOlder);
            if(result.created()>0||result.failed()>0) log.info("bad case 扫描完成: {}",result);
        } catch(Exception e) { log.warn("bad case 扫描批次失败，将在下一轮重试",e); }
    }
}
