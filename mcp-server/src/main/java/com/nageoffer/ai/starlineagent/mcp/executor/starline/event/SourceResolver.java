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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event;

import com.nageoffer.ai.starlineagent.mcp.executor.starline.OfficialSourceRegistryClient.OfficialSource;
import org.springframework.stereotype.Component;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** Resolve only public HTTP(S) redirects. Each connection is pinned to the validated DNS address. */
@Component
public class SourceResolver {
    public record Resolution(String url, OfficialSource source, String reason) {}
    public record Redirect(int status, String location) {}
    @FunctionalInterface public interface Transport { Redirect head(URI uri) throws Exception; }
    private final Transport transport;
    public SourceResolver() { this(SourceResolver::publicHead); }
    public SourceResolver(Transport transport) { this.transport = transport; }
    public Resolution resolve(String url, List<OfficialSource> sources, boolean allowNetwork) {
        try {
            URI current = normalize(url);
            for (int hop = 0; hop <= 3; hop++) {
                String host = host(current.getHost());
                OfficialSource source = sources.stream().filter(s -> host.equals(host(s.domain())) || host.endsWith("." + host(s.domain())))
                        .max(Comparator.comparingInt(s -> s.domain().length())).orElse(null);
                if (source != null) return new Resolution(current.toString(), source, null);
                if (!allowNetwork) return new Resolution(null, null, "REDIRECT_UNRESOLVED");
                if (hop == 3) break;
                Redirect response = transport.head(current);
                if (response.status() < 300 || response.status() > 399) return new Resolution(null, null, "NOT_WHITELISTED");
                if (response.location() == null) break;
                current = normalize(current.resolve(response.location()).toString());
            }
            return new Resolution(null, null, "REDIRECT_UNRESOLVED");
        } catch (IllegalArgumentException e) { return new Resolution(null, null, "INVALID_URL"); }
        catch (Exception e) { return new Resolution(null, null, "REDIRECT_UNRESOLVED"); }
    }
    public static URI normalize(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null && uri.getRawAuthority() != null && !uri.getRawAuthority().contains("@") && !uri.getRawAuthority().contains(":")) {
            String asciiHost = host(uri.getRawAuthority());
            uri = URI.create(uri.getScheme() + "://" + asciiHost + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) || uri.getHost() == null)
            throw new IllegalArgumentException("Invalid URL");
        String query = uri.getRawQuery();
        if (query != null) query = Arrays.stream(query.split("&")).filter(part -> {
            String key = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return !Set.of("spm", "from", "sqm", "ref", "source").contains(key) && !key.startsWith("utm_");
        }).sorted().collect(java.util.stream.Collectors.joining("&"));
        // Assemble raw components without double-encoding event IDs or percent-encoded slugs.
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + host(uri.getHost())
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (query == null || query.isEmpty() ? "" : "?" + query));
    }
    private static String host(String host) {
        return IDN.toASCII(host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").replaceFirst("\\.$", ""));
    }
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 16) return (b[0] & 0xfe) != 0xfc && (b[0] & 0xff) != 0;
        int first = b[0] & 255, second = b[1] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254) && !(first == 198 && (second == 18 || second == 19));
    }
    private static Redirect publicHead(URI uri) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) throw new IOException("Private redirect blocked");
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(addresses[0], port), 500);
            plain.setSoTimeout(500);
            Socket connection = plain;
            if ("https".equals(uri.getScheme())) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, uri.getHost(), port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(500);
                tls.startHandshake();
                connection = tls;
            }
            try (Socket active = connection) {
                String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                active.getOutputStream().write(("HEAD " + path + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nConnection: close\r\nUser-Agent: StarlineEventSearch/1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int c, trailer = 0;
                long readDeadline = System.nanoTime() + 500_000_000L;
                while (bytes.size() < 8192 && System.nanoTime() < readDeadline && (c = active.getInputStream().read()) != -1) {
                    bytes.write(c);
                    trailer = (trailer << 8) | c;
                    if (trailer == 0x0d0a0d0a) break;
                }
                if (trailer != 0x0d0a0d0a) throw new IOException("Redirect header budget exhausted");
                String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
                int status = Integer.parseInt(lines[0].split(" ")[1]);
                String location = Arrays.stream(lines).filter(line -> line.toLowerCase(Locale.ROOT).startsWith("location:"))
                        .map(line -> line.substring(9).trim()).findFirst().orElse(null);
                return new Redirect(status, location);
            }
        }
    }
}
