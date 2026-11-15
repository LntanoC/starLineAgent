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

import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SeatViewPhotoService {
    private final JdbcTemplate jdbc;
    private final SeatViewService seatViews;
    private final FileStorageService storage;

    public record Photo(String id, String showId, String sectionCode, Integer row, Integer seat,
                        String caption, String createdAt, boolean owned) {}
    public record Content(String key, String type) {}

    public List<Photo> list(String showId, String sectionCode, Integer row, Integer seat, int page) {
        UserContext.requireUser();
        if (page < 0 || page > 10000) throw new ClientException("页码无效");
        return jdbc.query("""
                SELECT p.id, p.show_id, p.section_code, p.row_number, p.seat_number, p.caption,
                       p.created_at::text, p.user_id = ? AS owned
                FROM t_seat_view_photo p JOIN t_seat_view_show s ON s.id = p.show_id
                WHERE p.show_id = ? AND s.document->>'status' = 'PUBLISHED'
                  AND (CAST(? AS text) IS NULL OR p.section_code = ?)
                  AND (CAST(? AS integer) IS NULL OR p.row_number = ?)
                  AND (CAST(? AS integer) IS NULL OR p.seat_number = ?)
                ORDER BY p.created_at DESC, p.id DESC LIMIT 30 OFFSET ?
                """, (rs, n) -> new Photo(rs.getString(1), rs.getString(2), rs.getString(3),
                (Integer) rs.getObject(4), (Integer) rs.getObject(5), rs.getString(6), rs.getString(7), rs.getBoolean(8)),
                UserContext.requireUser().getUserId(), showId, sectionCode, sectionCode, row, row, seat, seat, page * 30);
    }

    public void upload(String showId, String sectionCode, Integer row, Integer seat, String caption, MultipartFile file) throws IOException {
        String userId = UserContext.requireUser().getUserId();
        var document = seatViews.list(false).stream().filter(item -> item.show().id().equals(showId)).findFirst()
                .orElseThrow(() -> new ClientException("演出不存在或未发布"));
        boolean sectionExists = document.show().sections().stream().anyMatch(item -> item.code().equals(sectionCode));
        if (!sectionExists) throw new ClientException("分区不存在");
        if ((row != null && (row < 1 || row > 500)) || (seat != null && (seat < 1 || seat > 1000))) throw new ClientException("排座范围无效");
        if (caption == null) caption = "";
        if (caption.length() > 500) throw new ClientException("照片说明不能超过 500 字");
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new ClientException("图片大小须在 5 MB 以内");
        String type;
        try (InputStream source = file.getInputStream(); ImageInputStream image = ImageIO.createImageInputStream(source)) {
            if (image == null) throw new ClientException("无法读取图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) throw new ClientException("仅支持 JPEG 或 PNG 图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(image, true, true);
                String format = reader.getFormatName();
                if (reader.getWidth(0) > 10000 || reader.getHeight(0) > 10000) throw new ClientException("图片尺寸过大");
                if ("JPEG".equalsIgnoreCase(format)) type = "image/jpeg";
                else if ("PNG".equalsIgnoreCase(format)) type = "image/png";
                else throw new ClientException("仅支持 JPEG 或 PNG 图片");
            } finally { reader.dispose(); }
        }
        String key;
        try (InputStream input = file.getInputStream()) {
            key = storage.upload("seat-view", input, file.getSize(), type.equals("image/png") ? "photo.png" : "photo.jpg", type).getUrl();
        }
        // 数据库写入失败时清除已上传对象，避免重试累积孤儿文件。
        try {
            jdbc.update("""
                    INSERT INTO t_seat_view_photo(id,user_id,show_id,section_code,row_number,seat_number,caption,storage_key,content_type)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), userId, showId, sectionCode, row, seat, caption, key, type);
        } catch (RuntimeException failure) {
            try { storage.deleteByUrl(key); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Content content(String id) {
        UserContext.requireUser();
        return jdbc.query("""
                SELECT p.storage_key,p.content_type FROM t_seat_view_photo p
                JOIN t_seat_view_show s ON s.id=p.show_id
                WHERE p.id=? AND s.document->>'status'='PUBLISHED'
                """, (rs, n) -> new Content(rs.getString(1), rs.getString(2)), id).stream().findFirst()
                .orElseThrow(() -> new ClientException("照片不存在或已下架"));
    }

    public InputStream open(Content content) { return storage.openStream(content.key()); }
}
