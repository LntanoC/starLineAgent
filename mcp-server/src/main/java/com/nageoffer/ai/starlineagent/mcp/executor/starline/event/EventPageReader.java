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
import com.nageoffer.ai.starlineagent.mcp.executor.starline.ArtistEventSearchProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads already-allowlisted public HTML pages. Redirects, private addresses and oversized bodies are rejected. */
public class EventPageReader {
    private final CityDictionary cities;
    public EventPageReader() { this(new ArtistEventSearchProperties()); }
    public EventPageReader(ArtistEventSearchProperties properties) { cities = new CityDictionary(properties); }
    private String city(String title, String body) {
        var preferred = cities.find(title).stream().filter(hit -> !hit.unknown()).map(CityDictionary.Hit::city).distinct().toList();
        if (preferred.size() == 1) return preferred.get(0);
        var matches = cities.find(body).stream().filter(hit -> !hit.unknown()).map(CityDictionary.Hit::city).distinct().toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static final Pattern JSON_LD = Pattern.compile("(?is)<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>");
    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<String> read(String url, String artist, String city, long timeoutMillis) {
        try {
            URI uri = URI.create(url);
            String html=fetchHtml(uri,timeoutMillis);
            if (html==null) return Optional.empty();
            String body=extract(html,artist,city);
            return body.isBlank() ? Optional.empty() : Optional.of(body);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public static boolean publicUri(URI uri) throws java.net.UnknownHostException {
        if (uri.getHost() == null || uri.getUserInfo() != null || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme())) || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) return false;
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0) return false;
        for (InetAddress address : addresses) if (!SourceResolver.isPublic(address)) return false;
        return true;
    }

    public String extract(String html, String artist) throws java.io.IOException {
        return extract(html, artist, null);
    }

    public String extract(String html, String artist, String city) throws java.io.IOException {
        StringBuilder structured = new StringBuilder();
        Matcher matcher = JSON_LD.matcher(html);
        while (matcher.find()) {
            try { events(mapper.readTree(matcher.group(1)), artist, city, structured); }
            catch (Exception ignored) { /* Invalid structured data falls back to visible text. */ }
        }
        if (!structured.isEmpty()) return structured.toString();
        StringBuilder visible = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private int hidden;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden++;
                if (tag.isBlock()) visible.append('\n');
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && hidden > 0) hidden--;
                if (tag.isBlock()) visible.append('\n');
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.BR) visible.append('\n');
            }
            @Override public void handleText(char[] data, int pos) {
                if (hidden == 0) visible.append(data).append(' ');
            }
        }, true);
        String text = visible.toString();
        if (!text.contains(artist)) return "";
        // Recommendation blocks often contain other cities and dates; never use them as page evidence.
        for (String marker : List.of("相关推荐", "猜你喜欢", "热门推荐", "其他演出", "巡演推荐")) {
            int index = text.indexOf(marker);
            if (index >= 0) text = text.substring(0, index);
        }
        return text.substring(0, Math.min(24000, text.length()));
    }

    private void events(JsonNode node, String artist, String city, StringBuilder output) {
        if (node == null) return;
        if (node.isArray()) { node.forEach(child -> events(child, artist, city, output)); return; }
        if (!node.isObject()) return;
        String type = node.path("@type").toString();
        String name = node.path("name").asText("");
        String eventCity = city(name, node.path("location").toString());
        if (type.contains("Event") && name.contains(artist) && (city == null || city.equals(eventCity))) {
            String start = node.path("startDate").asText("");
            if (start.matches("20\\d{2}-\\d{2}-\\d{2}.*")) {
                output.append(node.path("name").asText()).append("\n演出日期：").append(start.substring(0, 10)).append('\n');
            }
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isContainerNode()) events(entry.getValue(), artist, city, output);
        });
    }

    private static String fetchHtml(URI uri,long budget) throws Exception {
        if (!publicUri(uri)) return null;
        InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());
        if (addresses.length==0 || java.util.Arrays.stream(addresses).anyMatch(a -> !SourceResolver.isPublic(a))) return null;
        int timeout=(int)Math.max(1,Math.min(1000,budget));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1,budget));
        int port=uri.getPort()>=0?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;
        try (java.net.Socket plain=new java.net.Socket()) {
            plain.connect(new java.net.InetSocketAddress(addresses[0],port),timeout);
            plain.setSoTimeout(timeout);
            java.net.Socket connection=plain;
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                var tls=(javax.net.ssl.SSLSocket)((javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(plain,uri.getHost(),port,true);
                var parameters=tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new javax.net.ssl.SNIHostName(uri.getHost())));
                tls.setSSLParameters(parameters); tls.setSoTimeout(timeout); tls.startHandshake(); connection=tls;
            }
            try (java.net.Socket active=connection) {
                String path=uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
                active.getOutputStream().write(("GET "+path+" HTTP/1.0\r\nHost: "+uri.getHost()+"\r\nAccept: text/html,application/xhtml+xml\r\nConnection: close\r\nUser-Agent: StarlineEventSearch/1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                var input=new java.io.BufferedInputStream(active.getInputStream());
                var headers=new java.io.ByteArrayOutputStream(); int trailer=0,c;
                while (headers.size()<8192 && System.nanoTime()<deadline && (c=input.read())!=-1) {
                    headers.write(c); trailer=(trailer<<8)|c; if (trailer==0x0d0a0d0a) break;
                }
                if (trailer!=0x0d0a0d0a) return null;
                String head=headers.toString(StandardCharsets.ISO_8859_1);
                if (!head.split("\r\n")[0].matches("HTTP/\\d\\.\\d 200(?: .*)?") || !head.toLowerCase(java.util.Locale.ROOT).contains("text/html")) return null;
                if (head.toLowerCase(java.util.Locale.ROOT).contains("content-encoding: gzip")) return null;
                var bytes=new java.io.ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int length;
                while (System.nanoTime()<deadline && !Thread.currentThread().isInterrupted() && (length=input.read(buffer))!=-1) {
                    if (bytes.size()+length>512000) return null; bytes.write(buffer,0,length);
                }
                if (System.nanoTime()>=deadline || Thread.currentThread().isInterrupted()) return null;
                byte[] body=bytes.toByteArray();
                if (head.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) body=decodeChunks(body);
                java.nio.charset.Charset charset=StandardCharsets.UTF_8;
                var encoding=Pattern.compile("(?i)charset\\s*=\\s*([a-zA-Z0-9_-]+)").matcher(head);
                if (encoding.find()) try { charset=java.nio.charset.Charset.forName(encoding.group(1)); } catch (RuntimeException ignored) { }
                return new String(body,charset);
            }
        }
    }

    private static byte[] decodeChunks(byte[] body) throws java.io.IOException {
        var output=new java.io.ByteArrayOutputStream(); int offset=0;
        while (offset<body.length) {
            int end=offset; while (end+1<body.length && !(body[end]==13 && body[end+1]==10)) end++;
            if (end+1>=body.length) throw new java.io.IOException("Invalid chunk");
            int length=Integer.parseInt(new String(body,offset,end-offset,StandardCharsets.US_ASCII).split(";",2)[0].trim(),16);
            if (length==0) return output.toByteArray();
            offset=end+2;
            if (length<0 || length>body.length-offset-2 || body[offset+length]!=13 || body[offset+length+1]!=10) throw new java.io.IOException("Invalid chunk");
            output.write(body,offset,length); offset+=length+2;
        }
        throw new java.io.IOException("Incomplete chunks");
    }
}
