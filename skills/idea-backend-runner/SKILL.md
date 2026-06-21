---
name: idea-backend-runner
description: 只允许通过 IntelliJ IDEA MCP 启动、停止、重启或验证当前 DataAgent 后端服务。用户要求使用 IDEA MCP、idea、Run Configuration、不要命令行启动后端时使用这个技能。
---

# IDEA 后端启动技能

这个技能只用于当前 DataAgent 项目的后端服务。

## 硬性规则

- 只能使用 IntelliJ IDEA MCP 工具启动、停止或重启后端。
- 不能用 `node_repl` 冒充 IDEA MCP。
- 不能用 PowerShell、`mvnw`、`java`、`Start-Process` 或其他命令行方式启动后端。
- 可以检查 `8065` 端口是否被占用，但不能通过命令行启动后端。
- 如果当前工具列表里没有 IDEA MCP 的具体工具，要直接说明：当前会话无法通过 IDEA MCP 启动后端。

## 执行流程

1. 先检查当前是否真的有 IDEA MCP 工具可用。
2. 如果 IDEA MCP 可用，通过 IDEA MCP 查找后端启动配置，目标通常是 `DataAgentApplication`。
3. 如果 IDEA 里已有运行中的后端实例，优先通过 IDEA MCP 停止它。
4. 通过 IDEA MCP 启动后端 Run Configuration。
5. 优先读取 IDEA MCP 返回的运行输出，确认 Spring Boot 是否启动成功。
6. IDEA 输出显示启动成功后，可以再轻量请求 `http://localhost:8065/api/reactive-demo/ping` 做验证。

## 成功标准

只有同时满足这些条件时，才能说“启动成功”：

- 后端确实是通过 IDEA MCP 启动的。
- IDEA 运行输出里出现类似 `Started DataAgentApplication` 的成功启动日志。
- `8065` 端口由这次新启动的后端服务占用。
- `/api/reactive-demo/ping` 这类轻量接口能正常返回。

## 失败处理

如果启动失败，先报告 IDEA 运行输出里的真实错误，不要猜。

常见失败：

```text
Port 8065 was already in use
```

这种情况表示端口 `8065` 被旧进程占用。

处理原则：

- 优先通过 IDEA MCP 停掉旧的运行实例。
- 如果 IDEA MCP 无法停止旧进程，不要擅自用命令行杀进程。
- 需要手动杀外部进程时，先问用户确认。
