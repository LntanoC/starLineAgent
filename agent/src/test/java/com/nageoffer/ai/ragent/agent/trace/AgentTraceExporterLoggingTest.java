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

package com.nageoffer.ai.ragent.agent.trace;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTel SDK 内部走 java.util.logging，不桥到 SLF4J 就意味着上报一直失败而应用日志一片安静
 * 桥接器由 Spring Boot 的日志系统在启动期装，这里只验运行时确实通了，不新增日志依赖
 */
class AgentTraceExporterLoggingTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;
    private boolean bridgeInstalledHere;

    @BeforeEach
    void setUp() {
        // 与 Boot 的 LogbackLoggingSystem 同一套装法，测试进程没有它，得自己装一次
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
            bridgeInstalledHere = true;
        }
        appender = new ListAppender<>();
        appender.start();
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(appender);
        appender.stop();
        if (bridgeInstalledHere) {
            SLF4JBridgeHandler.uninstall();
        }
    }

    /**
     * 端点打不通时导出失败必须落进应用日志：这是排查「LangFuse 里什么都没有」的唯一线索
     */
    @Test
    void shouldSurfaceExporterFailureThroughApplicationLog() throws Exception {
        String endpoint = "http://127.0.0.1:" + closedPort() + "/v1/traces";
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(2))
                // 默认要重试五次带退避，压到下限只为让这条用例快点走到失败日志
                .setRetryPolicy(RetryPolicy.builder().setMaxAttempts(2).build())
                .build();

        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(exporter).build())
                .build()) {
            Span span = tracerProvider.get("test").spanBuilder("probe").startSpan();
            span.end();
            assertThat(tracerProvider.forceFlush().join(20, TimeUnit.SECONDS).isSuccess()).isFalse();
        }

        // 失败回调在 OkHttp 的分发线程上打日志，与结果码谁先谁后没有保证，轮询到为止
        assertThat(awaitExportFailureLog()).isTrue();
    }

    private boolean awaitExportFailureLog() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<ILoggingEvent> events = List.copyOf(appender.list);
            boolean surfaced = events.stream().anyMatch(event -> event.getLoggerName().startsWith("io.opentelemetry")
                    && event.getFormattedMessage().contains("Failed to export"));
            if (surfaced) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * 取一个刚关掉的端口：绑过再放开，短期内不会被别的进程占走
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
