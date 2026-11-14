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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event.crawl;

import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.SourceResolver;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** Fixed hosts, public DNS pinned to the connection, TLS verification and no redirects. */
public class CrawlHttp {
    public static final Set<String> HOSTS = Set.of("banshi.whlyj.beijing.gov.cn", "wglj.gz.gov.cn", "search.damai.cn");
    public record Response(byte[] body, String contentType) {
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }
    public static class Failure extends IOException {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
    @FunctionalInterface public interface Fetch { Response get(String url) throws Exception; }
    private long last;
    public static URI validate(String value) throws Failure {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new Failure("URL_REJECTED");
            return uri;
        } catch (IllegalArgumentException e) { throw new Failure("URL_REJECTED"); }
    }
    public synchronized Response get(String value) throws Exception {
        URI uri = validate(value);
        long wait = 1000 - (System.currentTimeMillis() - last);
        if (wait > 0) Thread.sleep(wait);
        last = System.currentTimeMillis();
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !SourceResolver.isPublic(a))) throw new Failure("PRIVATE_ADDRESS");
        long deadline = System.nanoTime() + 8_000_000_000L;
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(addresses[0], 443), 4000);
            plain.setSoTimeout(4000);
            try (SSLSocket socket = (SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain, uri.getHost(), 443, true)) {
                SSLParameters p = socket.getSSLParameters(); p.setEndpointIdentificationAlgorithm("HTTPS");
                p.setServerNames(List.of(new SNIHostName(uri.getHost()))); socket.setSSLParameters(p);
                socket.setSoTimeout(4000); socket.startHandshake();
                String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                socket.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: " + uri.getHost()
                        + "\r\nUser-Agent: StarLineAgent-EventCrawler/1.0\r\nAccept: text/html,application/pdf,application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                var input = new BufferedInputStream(socket.getInputStream());
                var header = new ByteArrayOutputStream(); int c, tail = 0;
                while (header.size() < 16384 && System.nanoTime() < deadline && (c = input.read()) != -1) {
                    header.write(c); tail = tail << 8 | c; if (tail == 0x0d0a0d0a) break;
                }
                if (tail != 0x0d0a0d0a) throw new Failure("INVALID_HEADERS");
                String headers = header.toString(StandardCharsets.ISO_8859_1);
                String status = headers.split("\r\n")[0];
                if (!status.matches("HTTP/\\d\\.\\d 200(?: .*)?")) {
                    String code = status.split(" ").length > 1 ? status.split(" ")[1] : "INVALID";
                    throw new Failure("HTTP_" + code);
                }
                String type = headers.lines().filter(l -> l.toLowerCase(Locale.ROOT).startsWith("content-type:"))
                        .map(l -> l.substring(13).trim()).findFirst().orElse("");
                String lower = headers.toLowerCase(Locale.ROOT);
                if (lower.contains("content-encoding:") && !lower.contains("content-encoding: identity")) throw new Failure("UNSUPPORTED_ENCODING");
                var out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted() && (n = input.read(buffer)) != -1) {
                    if (out.size() + n > 5_000_000) throw new Failure("BODY_TOO_LARGE"); out.write(buffer, 0, n);
                }
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new Failure("DEADLINE");
                byte[] body = out.toByteArray();
                if (lower.contains("transfer-encoding: chunked")) body = chunks(body);
                return new Response(body, type);
            }
        }
    }
    static byte[] chunks(byte[] input) throws IOException {
        var output = new ByteArrayOutputStream(); int offset = 0;
        while (offset < input.length) {
            int end = offset; while (end + 1 < input.length && !(input[end] == 13 && input[end + 1] == 10)) end++;
            if (end + 1 >= input.length) throw new Failure("INVALID_CHUNK");
            int length;
            try { length = Integer.parseInt(new String(input, offset, end-offset, StandardCharsets.US_ASCII).split(";",2)[0].trim(),16); }
            catch (RuntimeException e) { throw new Failure("INVALID_CHUNK"); }
            if (length == 0) return output.toByteArray();
            offset = end + 2;
            if (length < 0 || length > input.length-offset-2 || input[offset+length] != 13 || input[offset+length+1] != 10) throw new Failure("INVALID_CHUNK");
            output.write(input,offset,length); offset += length+2;
        }
        throw new Failure("INVALID_CHUNK");
    }
}
