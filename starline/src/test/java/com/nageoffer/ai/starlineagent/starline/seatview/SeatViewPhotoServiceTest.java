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

import com.nageoffer.ai.starlineagent.framework.context.LoginUser;
import com.nageoffer.ai.starlineagent.framework.context.UserContext;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.starlineagent.rag.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;

import static com.nageoffer.ai.starlineagent.starline.seatview.SeatViewDocument.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeatViewPhotoServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SeatViewService seatViews = mock(SeatViewService.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final SeatViewPhotoService service = new SeatViewPhotoService(jdbc, seatViews, storage);

    @BeforeEach void setup() {
        UserContext.set(LoginUser.builder().userId("user-1").build());
        Section section = new Section("a", "A区", 2, 880,
                List.of(List.of(0., 0.), List.of(10., 0.), List.of(10., 10.), List.of(0., 10.)), 0);
        Show show = new Show("show", "测试", null, "PUBLISHED", "DEMO", "https://example.com", "示意", "CENTER",
                new Stage(0, 0, 1, 1, 0), List.of(section));
        when(seatViews.list(false)).thenReturn(List.of(new SeatViewDocument(new Venue("venue", "测试", "贵阳", List.of()), show)));
    }

    @AfterEach void clear() { UserContext.clear(); }

    @Test void rejectsNonImagesAndUnknownSectionsBeforeStorage() {
        var file = new MockMultipartFile("file", "fake.png", "image/png", "not a picture".getBytes());
        assertThrows(ClientException.class, () -> service.upload("show", "a", null, null, "", file));
        assertThrows(ClientException.class, () -> service.upload("show", "unknown", null, null, "", file));
        verifyNoInteractions(storage);
    }

    @Test void removesStoredFileWhenDatabaseInsertFails() throws Exception {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var file = new MockMultipartFile("file", "photo.png", "image/png", png);
        when(storage.upload(eq("seat-view"), any(InputStream.class), anyLong(), eq("photo.png"), eq("image/png")))
                .thenReturn(StoredFileDTO.builder().url("seat-view/test.png").build());
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("database failure"));
        assertThrows(IllegalStateException.class, () -> service.upload("show", "a", 1, 1, "", file));
        verify(storage).deleteByUrl("seat-view/test.png");
    }
}
