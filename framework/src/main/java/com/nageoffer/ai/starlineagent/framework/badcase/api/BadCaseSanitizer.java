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

package com.nageoffer.ai.starlineagent.framework.badcase.api;
import java.util.*;
import java.util.regex.Pattern;
/** Shared bounded redaction for business evidence, snapshots and model input. */
public final class BadCaseSanitizer {
 private BadCaseSanitizer() {}
 private static final Pattern EMAIL=Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
 private static final Pattern SECRET=Pattern.compile("(?i)(authorization|api[_-]?key|password|passwd|secret|token|credential)([\\s\"':=]+)([^\\s,;\"}]+)");
 public static String text(String value,int max) {
  if(value==null)return null;
  String s=EMAIL.matcher(value).replaceAll("[EMAIL_REDACTED]");
  s=SECRET.matcher(s).replaceAll("$1$2[REDACTED]");
  s=s.replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [REDACTED]")
   .replaceAll("\\bsk-[A-Za-z0-9_-]{8,}","[KEY_REDACTED]")
   .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)","[PHONE_REDACTED]");
  return s.length()>max?s.substring(0,Math.max(0,max-12))+"[TRUNCATED]":s;
 }
 public static Object value(Object v) {
  if(v instanceof Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,x)->out.put(String.valueOf(k),String.valueOf(k).matches("(?i).*(password|secret|token|authorization|api.?key|credential).*")?"[REDACTED]":value(x)));return out;}
  if(v instanceof Collection<?> c)return c.stream().limit(64).map(BadCaseSanitizer::value).toList();
  return v instanceof String s?text(s,4000):v;
 }
}
