/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Executes the complete destructive reset and BitSelection seed workflow. */
public final class InitializeMain {

    private InitializeMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.cleanup(context);
            // 业务库与知识库互不依赖，但必须排在 verify 之前：商品行数与商品详情篇数是同一条约束的两端
            InitializationActions.initializeBizData(context);
            InitializationActions.initializeKnowledgeBases(context);
            InitializationActions.initializeDocuments(context);
            InitializationActions.initializeIntentTree(context);
            InitializationActions.initializeSkills(context);
            // 必须排在 cleanup 之后：cleanup 按 builtin = 0 删行，先建的人设会被它删掉
            InitializationActions.initializeAgentProfile(context);
            InitializationActions.initializeSampleQuestions(context);
            InitializationActions.verify(context);
            InitializationActions.warmup(context);
            System.out.println("[initializer] SUCCESS");
        });
    }
}
