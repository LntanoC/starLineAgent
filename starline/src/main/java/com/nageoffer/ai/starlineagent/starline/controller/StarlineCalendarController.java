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

package com.nageoffer.ai.starlineagent.starline.controller;

import com.nageoffer.ai.starlineagent.framework.convention.Result;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.web.Results;
import com.nageoffer.ai.starlineagent.starline.dao.entity.StarlineEventDO;
import com.nageoffer.ai.starlineagent.starline.service.StarlineCalendarTokenService;
import com.nageoffer.ai.starlineagent.starline.service.StarlineEventService;
import com.nageoffer.ai.starlineagent.starline.service.StarlineIcsBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/starline")
@RequiredArgsConstructor
public class StarlineCalendarController {
    private static final MediaType CALENDAR_TYPE = MediaType.parseMediaType("text/calendar;charset=UTF-8");
    private static final CacheControl FEED_CACHE = CacheControl.noCache().cachePrivate().mustRevalidate();

    private final StarlineCalendarTokenService tokenService;
    private final StarlineEventService eventService;

    @GetMapping("/calendar-token")
    public Result<String> getToken() {
        String userId = UserContext.requireUser().getUserId();
        return Results.success(tokenService.ensureToken(userId));
    }

    @PostMapping("/calendar-token/reset")
    public Result<String> resetToken() {
        String userId = UserContext.requireUser().getUserId();
        return Results.success(tokenService.reset(userId));
    }

    /** The scoped token grants only read access to one user's calendar feed. */
    @GetMapping(value = "/calendar.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> calendar(
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String userId = tokenService.resolveUserId(token);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.TEXT_PLAIN)
                    .cacheControl(CacheControl.noStore())
                    .body("订阅链接无效或已重置，请回到星序重新获取");
        }

        List<StarlineEventDO> events = eventService.findCalendarEvents(userId);
        String ics = StarlineIcsBuilder.build(events);
        String etag = "\"" + sha256(ics) + "\"";
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(FEED_CACHE)
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(CALENDAR_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"starline.ics\"")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(FEED_CACHE)
                .eTag(etag)
                .body(ics);
    }

    private static boolean matches(String requestHeader, String etag) {
        if (requestHeader == null || requestHeader.isBlank()) {
            return false;
        }
        for (String candidate : requestHeader.split(",")) {
            String value = candidate.trim();
            if (value.equals("*") || value.equals(etag) || value.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate calendar feed ETag", exception);
        }
    }
}
