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

package com.nageoffer.ai.starlineagent.starline.seatview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.nageoffer.ai.starlineagent.starline.seatview.SeatViewDocument.*;

@Service
@RequiredArgsConstructor
public class SeatViewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public List<SeatViewDocument> list(boolean admin) {
        return jdbc.query("""
                SELECT v.document::text AS venue, s.document::text AS show
                FROM t_seat_view_show s JOIN t_seat_view_venue v ON v.id = s.venue_id
                WHERE (? OR s.document->>'status' = 'PUBLISHED')
                ORDER BY v.document->>'name', s.document->>'startsAt' NULLS LAST, s.id
                """, (rs, row) -> new SeatViewDocument(read(rs.getString("venue"), Venue.class),
                read(rs.getString("show"), Show.class)), admin);
    }

    @Transactional
    public void save(SeatViewDocument document) {
        SeatViewValidation.validate(document);
        jdbc.update("""
                INSERT INTO t_seat_view_venue(id, document) VALUES (?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET document = EXCLUDED.document, updated_at = now()
                """, document.venue().id(), json(document.venue()));
        // 演出的稳定 ID 不能被重新挂到别的场馆，旧分享链接也因此保持语义。
        int saved = jdbc.update("""
                INSERT INTO t_seat_view_show(id, venue_id, document) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET document = EXCLUDED.document, updated_at = now()
                WHERE t_seat_view_show.venue_id = EXCLUDED.venue_id
                """, document.show().id(), document.venue().id(), json(document.show()));
        if (saved == 0) throw new ClientException("演出编号已属于其他场馆，请使用新编号");
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("座位图序列化失败", e); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("座位图数据损坏", e); }
    }
}
