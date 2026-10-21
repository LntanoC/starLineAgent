/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
package com.nageoffer.ai.ragent.initializer;

/** Validates only local metadata, prompts, documents and checksums; no network connection is made. */
public final class ValidateDatasetMain {
    private ValidateDatasetMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            context.dataset().verifyChecksums();
            System.out.printf("[validate] SUCCESS: knowledgeBases=%d, documents=%d, intents=%d, questions=%d, skills=%d%n",
                    context.dataset().knowledgeBases().size(), context.dataset().documentCount(),
                    context.dataset().intents().size(), context.dataset().questions().size(),
                    context.dataset().skills().size());
        });
    }
}
