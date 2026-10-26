# 部署资源说明

本目录包含较早版本的部署样例配置。它不是当前服务器 Compose 的权威来源；其中 starline-app.compose.yaml 与当前 MCP/API/Web 拆分配置可能不一致，请勿直接按旧命令部署。

从本地源码构建、准备服务器、配置环境变量、初始化数据库和启动服务，请先阅读[星序快速部署手册](quick-start.md)。

当前服务器配置快照位于 docs/deployment/server-config.md，应用 release 配置位于 resources/docker/server/releases/。全新安装与已有环境升级必须区分；不要在已有数据库上重跑全量初始化 SQL。