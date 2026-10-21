/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Executes the complete destructive reset and enterprise knowledge-base seed workflow. */
public final class InitializeMain {

    private InitializeMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.cleanup(context);
            InitializationActions.initializeKnowledgeBases(context);
            InitializationActions.initializeDocuments(context);
            InitializationActions.initializeIntentTree(context);
            InitializationActions.initializeSkills(context);
            InitializationActions.initializeSampleQuestions(context);
            InitializationActions.verify(context);
            InitializationActions.warmup(context);
            System.out.println("[initializer] SUCCESS");
        });
    }
}
