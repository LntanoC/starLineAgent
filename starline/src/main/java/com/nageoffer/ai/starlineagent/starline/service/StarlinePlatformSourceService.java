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

package com.nageoffer.ai.starlineagent.starline.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.starline.controller.admin.request.SetStarlinePlatformSourceEnabledRequest;
import com.nageoffer.ai.starlineagent.starline.controller.admin.request.SaveStarlinePlatformSourceRequest;
import com.nageoffer.ai.starlineagent.starline.controller.admin.vo.StarlinePlatformSourceVO;
import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlinePlatformSourceDO;
import com.nageoffer.ai.starlineagent.starline.dao.mapper.StarlinePlatformSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.IDN;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StarlinePlatformSourceService {
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final Set<String> STABILITY_VALUES = Set.of("STABLE", "PARTIAL", "UNSTABLE");
    private static final Set<String> SOURCE_TIERS = Set.of("GOV", "TICKETING");

    private final StarlinePlatformSourceMapper sourceMapper;

    public List<StarlinePlatformSourceVO> listCandidates() {
        List<StarlinePlatformSourceDO> sources = sourceMapper.selectList(
                Wrappers.<StarlinePlatformSourceDO>lambdaQuery().orderByDesc(StarlinePlatformSourceDO::getUpdateTime));
        return sources.stream().map(StarlinePlatformSourceService::toVO).toList();
    }

    @Transactional
    public String create(SaveStarlinePlatformSourceRequest request) {
        StarlinePlatformSourceDO source = toEntity(request);
        ensureDomainAvailable(source.getOfficialDomain(), null);
        LocalDateTime now = now();
        source.setStatus(Boolean.TRUE.equals(source.getPublicAccessible()) ? ACTIVE : DISABLED);
        source.setCreatedTime(now);
        source.setUpdateTime(now);
        sourceMapper.insert(source);
        return source.getId();
    }

    @Transactional
    public void update(String id, SaveStarlinePlatformSourceRequest request) {
        StarlinePlatformSourceDO current = sourceMapper.selectById(id);
        if (current == null) throw new ClientException("\u5e73\u53f0\u6765\u6e90\u4e0d\u5b58\u5728");
        StarlinePlatformSourceDO source = toEntity(request);
        ensureDomainAvailable(source.getOfficialDomain(), id);
        source.setId(id);
        source.setStatus(Boolean.TRUE.equals(source.getPublicAccessible()) ? ACTIVE : DISABLED);
        source.setUpdateTime(now());
        sourceMapper.updateById(source);
    }

    @Transactional
    public void setEnabled(String id, SetStarlinePlatformSourceEnabledRequest request) {
        StarlinePlatformSourceDO source = sourceMapper.selectById(id);
        if (source == null) throw new ClientException("\u5e73\u53f0\u6765\u6e90\u4e0d\u5b58\u5728");
        if (request == null || request.getEnabled() == null) {
            throw new ClientException("\u8bf7\u9009\u62e9\u662f\u5426\u542f\u7528\u767d\u540d\u5355\u6765\u6e90");
        }
        if (request.getEnabled() && !Boolean.TRUE.equals(source.getPublicAccessible())) {
            throw new ClientException("\u4ec5\u53ef\u542f\u7528\u53ef\u516c\u5f00\u8bbf\u95ee\u7684\u6765\u6e90");
        }
        source.setStatus(request.getEnabled() ? ACTIVE : DISABLED);
        source.setUpdateTime(now());
        sourceMapper.updateById(source);
    }

    /** Reads the current administrator-managed allowlist for the MCP search service. */
    public List<EnabledSource> listEnabledSources() {
        return sourceMapper.selectList(Wrappers.<StarlinePlatformSourceDO>lambdaQuery()
                .eq(StarlinePlatformSourceDO::getStatus, ACTIVE)
                .eq(StarlinePlatformSourceDO::getPublicAccessible, true)
                .orderByAsc(StarlinePlatformSourceDO::getPlatformName))
                .stream().map(source -> new EnabledSource(source.getPlatformName(),
                        source.getOfficialDomain(), source.getSourceTier())).toList();
    }

    private StarlinePlatformSourceDO toEntity(SaveStarlinePlatformSourceRequest request) {
        if (request == null) throw new ClientException("\u8bf7\u63d0\u4ea4\u5e73\u53f0\u8c03\u7814\u4fe1\u606f");
        StarlinePlatformSourceDO source = new StarlinePlatformSourceDO();
        source.setPlatformName(required(request.getPlatformName(), "\u8bf7\u586b\u5199\u5e73\u53f0\u540d\u79f0"));
        source.setOfficialDomain(normalizeDomain(request.getOfficialDomain()));
        String sourceTier = required(request.getSourceTier(), "请选择信息源层级").toUpperCase(Locale.ROOT);
        if (!SOURCE_TIERS.contains(sourceTier)) {
            throw new ClientException("信息源层级仅支持 GOV 或 TICKETING");
        }
        source.setSourceTier(sourceTier);
        source.setPublicAccessible(requiredBoolean(request.getPublicAccessible(), "\u8bf7\u8bb0\u5f55\u9875\u9762\u53ef\u8bbf\u95ee\u6027"));
        source.setActivityFieldsAvailable(requiredBoolean(request.getActivityFieldsAvailable(), "\u8bf7\u8bb0\u5f55\u6d3b\u52a8/\u573a\u6b21\u5b57\u6bb5\u53ef\u5f97\u6027"));
        source.setTicketTimeAvailable(requiredBoolean(request.getTicketTimeAvailable(), "\u8bf7\u8bb0\u5f55\u5f00\u7968\u65f6\u95f4\u5b57\u6bb5\u53ef\u5f97\u6027"));
        source.setTicketStatusAvailable(requiredBoolean(request.getTicketStatusAvailable(), "\u8bf7\u8bb0\u5f55\u5f00\u7968\u72b6\u6001\u5b57\u6bb5\u53ef\u5f97\u6027"));
        String stability = required(request.getFieldStability(), "\u8bf7\u9009\u62e9\u5b57\u6bb5\u7a33\u5b9a\u6027").toUpperCase(Locale.ROOT);
        if (!STABILITY_VALUES.contains(stability)) throw new ClientException("\u5b57\u6bb5\u7a33\u5b9a\u6027\u4ec5\u652f\u6301\u7a33\u5b9a\u3001\u90e8\u5206\u7a33\u5b9a\u6216\u4e0d\u7a33\u5b9a");
        source.setFieldStability(stability);
        source.setOfficialIdentityEvidence(required(request.getOfficialIdentityEvidence(), "\u8bf7\u586b\u5199\u5b98\u65b9\u8eab\u4efd\u4f9d\u636e"));
        source.setUpdateFrequency(required(request.getUpdateFrequency(), "\u8bf7\u586b\u5199\u66f4\u65b0\u9891\u7387"));
        source.setAccessBoundary(required(request.getAccessBoundary(), "\u8bf7\u586b\u5199\u8bbf\u95ee\u6216\u6388\u6743\u8fb9\u754c"));
        return source;
    }

    private void ensureDomainAvailable(String domain, String currentId) {
        var query = Wrappers.<StarlinePlatformSourceDO>lambdaQuery()
                .eq(StarlinePlatformSourceDO::getOfficialDomain, domain);
        if (currentId != null) query.ne(StarlinePlatformSourceDO::getId, currentId);
        if (sourceMapper.selectCount(query) > 0) throw new ClientException("\u8be5\u57df\u540d\u5df2\u5efa\u7acb\u5e73\u53f0\u6765\u6e90\u8bb0\u5f55");
    }

    private static String normalizeDomain(String value) {
        String raw = required(value, "\u8bf7\u586b\u5199\u5b98\u65b9\u57df\u540d");
        try {
            URI uri = URI.create(raw.contains("://") ? raw : "https://" + raw);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new ClientException("\u8bf7\u586b\u5199\u57df\u540d\uff0c\u4e0d\u8981\u5305\u542b\u8def\u5f84\u3001\u7aef\u53e3\u6216\u67e5\u8be2\u53c2\u6570");
            }
            String domain = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return domain.startsWith("www.") ? domain.substring(4) : domain;
        } catch (IllegalArgumentException exception) {
            throw new ClientException("\u5b98\u65b9\u57df\u540d\u683c\u5f0f\u4e0d\u6b63\u786e");
        }
    }

    private static Boolean requiredBoolean(Boolean value, String message) {
        if (value == null) throw new ClientException(message);
        return value;
    }

    private static String required(String value, String message) {
        String result = trim(value);
        if (result == null) throw new ClientException(message);
        return result;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    private static StarlinePlatformSourceVO toVO(StarlinePlatformSourceDO source) {
        return new StarlinePlatformSourceVO(source.getId(), source.getPlatformName(), source.getOfficialDomain(),
                source.getSourceTier(),
                source.getPublicAccessible(), source.getActivityFieldsAvailable(), source.getTicketTimeAvailable(),
                source.getTicketStatusAvailable(), source.getFieldStability(), source.getOfficialIdentityEvidence(),
                source.getUpdateFrequency(), source.getAccessBoundary(), source.getStatus(),
                source.getCreatedTime(), source.getUpdateTime());
    }

    public record EnabledSource(String platformName, String domain, String tier) {
    }
}
