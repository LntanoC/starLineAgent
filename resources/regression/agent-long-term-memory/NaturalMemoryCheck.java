/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.file.Path;
import java.util.*;

import static com.nageoffer.ai.ragent.initializer.LongTermMemoryMain.*;

/**
 * 通过四条自然提问观察屏幕偏好的保存与跨会话召回
 * 测试提问不要求整理记忆，也不限制模型调用记忆工具
 * 根据工具事件、抽取台账和记忆表记录，区分主 Agent 整理与后台抽取
 * 准备请求单独记录，不计入四条测试提问
 */
final class NaturalMemoryCheck {
    private static final String[] QUESTIONS = {
        "我一直更喜欢小屏手机，平时单手用着方便。选手机时，屏幕尺寸和握持感一般怎么权衡？今天先聊思路，不推荐具体型号。",
        "只看宣传页上的屏幕英寸数，能判断一部手机拿着顺不顺手吗？",
        "机身厚度和重量会怎样影响握持感？还是只聊一般判断方法。",
        "你知道我选手机时对屏幕大小有什么偏好吗？不知道就直说，不用推荐型号。"
    };
    private static final String OLD_RECALL = "你还记得我选手机时，对屏幕大小有什么偏好吗？如果没保存，就说不知道，不要猜。";

    static void printPlan() {
        System.out.println("准备阶段独立记录：仅在存在已知旧询问时整理一次，要求记忆不变、待处理数归零。");
        for (int i = 0; i < QUESTIONS.length; i++) System.out.println((i + 1) + ". " + (i == 3 ? "新会话：" : "同一会话：") + QUESTIONS[i]);
        System.out.println("逐轮记录工具、待处理数与抽取台账；不强迫后台写入，测后另行通过聊天清理新增条目。");
    }

    static void run(InitializerConfig config, Path output, StarlineHttpClient http, JdbcClient jdbc,
                    String uid, Map<String, Object> baseline) throws Exception {
        List<Map<String, Object>> original = objects(baseline.get("memory"));
        if (active(original).stream().anyMatch(LongTermMemoryMain::target)) throw new IllegalStateException("已有生效屏幕偏好，停止");
        if (processing(baseline)) throw new IllegalStateException("已有在飞抽取，停止");
        if (active(original).stream().mapToInt(m -> text(m,"content").length()).sum() > 1000) throw new IllegalStateException("容量余量不足，停止");
        List<Map<String, Object>> pending = pending(jdbc, uid);
        if (pending.size() > 1 || pending.stream().anyMatch(m -> !OLD_RECALL.equals(text(m,"content")))) throw new IllegalStateException("待处理积压不是已知的旧未知询问，请先人工核对");
        String from = objects(baseline.get("extractions")).stream().map(m -> text(m,"id")).max(String::compareTo).orElse("0");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("scenarioVersion", "phone-natural-v1"); manifest.put("startedAt", now().toString());
        manifest.put("questions", List.of(QUESTIONS)); manifest.put("pendingBeforePreparation", pending);
        manifest.put("baseUrl", http.baseUrl()); manifest.put("sourceSha256", fingerprints());
        manifest.put("observation", "每轮至少观察 3 秒；出现 PROCESSING 或待处理达到三条时，最多观察 30 秒。未触发不等于抽取语义失败。");
        write(output.resolve("natural-run.json"), manifest);
        write(output.resolve("prompts.json"), rows(jdbc, "select a.id,a.name,p.slot_key,p.content from t_agent_profile a join t_agent_prompt p on p.agent_id=a.id where a.active=1 and a.deleted=0 and p.deleted=0 and p.slot_key like 'AGENT_MEMORY_%'"));
        MemorySseClient sse = new MemorySseClient();
        List<Map<String, Object>> turns = new ArrayList<>();
        if (!pending.isEmpty()) {
            System.out.println("准备阶段：处理旧询问（不计自然聊天轮次）");
            String q = "请检查并整理已有的对话记忆，其他关于我的信息保持不变。";
            Map<String, Object> prep = new LinkedHashMap<>(); prep.put("question",q);
            prep.putAll(sse.askObserved(http,q,null,config.getInt("turn.timeout-seconds",180),output.resolve("preparation.sse")));
            String prepRequest = jdbc.queryRows("select reply_to_message_id from t_agent_message where user_id=" + uid + " and id=" + JdbcClient.literal(text(prep,"messageId"))).get(0).get(0);
            if (prepRequest.isBlank()) throw new IllegalStateException("缺少准备请求消息 ID");
            prep.put("requestMessageId",prepRequest);
            write(output.resolve("preparation.json"),prep);
            Map<String, Object> after = observe(jdbc,uid,from,JdbcClient.literal(text(prep,"conversationId")),true);
            write(output.resolve("preparation-after.json"),after);
            if (!Boolean.TRUE.equals(prep.get("replyComplete")) || !original.equals(objects(after.get("memory")))
                    || !objects(after.get("pending")).isEmpty() || processing(after)
                    || covering(objects(after.get("extractions")),prepRequest).stream().noneMatch(e -> "NOOP".equals(e.get("status")))) throw new IllegalStateException("准备阶段未满足 NOOP、记忆不变且待处理归零，停止");
        }
        Map<String, Object> clean = snapshot(jdbc,uid,from,null);
        clean.put("pending",pending(jdbc,uid)); write(output.resolve("natural-baseline.json"),clean);
        if (!objects(clean.get("pending")).isEmpty() || processing(clean) || !original.equals(objects(clean.get("memory")))) throw new IllegalStateException("正式开始前基线发生变化，停止");
        String conversation = null;
        for (int i=0; i<QUESTIONS.length; i++) {
            int n=i+1;
            if(i==3) conversation=null;
            Map<String, Object> turn=new LinkedHashMap<>();
            turn.put("round",n); turn.put("question",QUESTIONS[i]); turn.put("freshConversation",conversation==null);
            turn.put("pendingBefore",pending(jdbc,uid)); turns.add(turn);
            write(output.resolve("natural-turns.json"),turns);
            System.out.println("自然聊天第 " + n + "/4 轮");
            Map<String, Object> response=sse.askObserved(http,QUESTIONS[i],conversation,config.getInt("turn.timeout-seconds",180),output.resolve("turn-"+n+".sse"));
            turn.putAll(response); conversation=text(response,"conversationId");
            String request=jdbc.queryRows("select reply_to_message_id from t_agent_message where user_id="+uid+" and id="+JdbcClient.literal(text(response,"messageId"))).get(0).get(0);
            if(request.isBlank()) throw new IllegalStateException("缺少请求消息 ID");
            turn.put("requestMessageId",request);
            Map<String, Object> after=observe(jdbc,uid,from,JdbcClient.literal(conversation),false);
            write(output.resolve("snapshot-"+n+".json"),after);
            turn.put("pendingAfter",after.get("pending")); turn.put("coveringExtractions",covering(objects(after.get("extractions")),request));
            write(output.resolve("natural-turns.json"),turns);
            System.out.println("第 "+n+" 轮 "+text(response,"messageStatus")+"，待处理 "+objects(after.get("pending")).size()+" 条，工具事件 "+objects(response.get("toolEvents")).size()+" 个");
            if(!Boolean.TRUE.equals(response.get("replyComplete")) || processing(after)) throw new IllegalStateException("回复未完整成功或后台尚未结束，停止追加请求");
            if(objects(after.get("extractions")).stream().anyMatch(e -> List.of("CONFLICT","DROPPED").contains(e.get("status")))) throw new IllegalStateException("出现失败抽取，停止追加请求并保留证据");
            if(original.stream().anyMatch(old -> !objects(after.get("memory")).contains(old))) throw new IllegalStateException("存量记忆变化，停止并人工核对");
        }
        Map<String,Object> end=snapshot(jdbc,uid,from,null); end.put("pending",pending(jdbc,uid));
        write(output.resolve("natural-after.json"),end);
        write(output.resolve("after-full-memory.json"),rows(jdbc,"select * from t_agent_memory where user_id="+uid+" order by id"));
        List<Map<String,Object>> batches=new ArrayList<>();
        for(var e:objects(end.get("extractions"))) {
            Map<String,Object> batch=new LinkedHashMap<>(e);
            batch.put("coveredUserMessages",rows(jdbc,"select id,conversation_id,content from t_agent_message where user_id="+uid+" and role='user' and deleted=0 and id>="+JdbcClient.literal(text(e,"from_message_id"))+" and id<="+JdbcClient.literal(text(e,"to_message_id"))+" order by id"));
            batches.add(batch);
        }
        write(output.resolve("extraction-coverage.json"),batches);
        String ids=String.join(",",turns.stream().map(t -> JdbcClient.literal(text(t,"conversationId"))).distinct().toList());
        write(output.resolve("compactions.json"),rows(jdbc,"select id,conversation_id,generation from t_agent_context_compaction where user_id="+uid+" and conversation_id in ("+ids+")"));
        System.out.println("四轮采集完成；需人工核对保存路径、新会话回答，并通过聊天清理测试条目。");
    }

    private static Map<String,Object> observe(JdbcClient jdbc,String uid,String from,String conv,boolean requireEmpty) throws Exception {
        long start=System.nanoTime(), deadline=start+30_000_000_000L;
        Map<String,Object> snap;
        List<Map<String,Object>> samples=new ArrayList<>();
        do {
            snap=snapshot(jdbc,uid,from,conv);
            var pending=pending(jdbc,uid); snap.put("pending",pending);
            samples.add(Map.of("observedAt",snap.get("observedAt"),"pendingCount",pending.size(),"processing",processing(snap),"extractions",snap.get("extractions")));
            boolean settled=!processing(snap) && (requireEmpty ? pending.isEmpty() : pending.size()<3);
            if(settled && System.nanoTime()-start>=3_000_000_000L) break;
            Thread.sleep(500);
        } while(System.nanoTime()<deadline);
        snap.put("observationSamples",samples);
        return snap;
    }
    private static boolean processing(Map<String,Object> snap) { return objects(snap.get("extractions")).stream().anyMatch(e -> "PROCESSING".equals(e.get("status"))); }
    private static List<Map<String,Object>> pending(JdbcClient jdbc,String uid) throws Exception {
        return rows(jdbc,"select m.id,m.conversation_id,m.content from t_agent_message m join t_agent_memory_control c on c.user_id=m.user_id where m.user_id="+uid
            +" and m.role='user' and m.deleted=0 and m.create_time>=c.create_time and m.id>coalesce((select max(to_message_id) from t_agent_memory_extraction where user_id="+uid+" and status in ('WRITTEN','NOOP','DROPPED')),'0') order by m.id");
    }
}
