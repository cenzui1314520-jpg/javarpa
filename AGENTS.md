# AGENTS.md — JavaRPA 工作区说明

云端统一管理 Android 设备自动化脚本的系统：脚本热更新、任务远程启停、灰度发布/回滚、实时日志与统计。三个可独立构建的模块 + 协议文档。全仓库注释/文档/commit 均为中文，保持一致。

## 模块与目录

| 目录 | 说明 |
|------|------|
| `server/` | 云端（Spring Boot 3.5 / Java 17 / MyBatis-Plus / MySQL / Redis），包结构 `com.rpa.server.{controller,service,mapper,entity,ws,security,config,common}` |
| `android/` | 设备端引擎 App（`com.rpa.engine`，纯 Java 无 Kotlin，compileSdk 33 / minSdk 24 / 源码 Java 11，Rhino 1.7.14 + OkHttp） |
| `web/` | 管理后台（Vue3 + TS + Element Plus + ECharts + STOMP） |
| `docs/` | `protocol.md`（设备 WS 协议）、`script-development.md`（脚本 JS API 手册）、`mcp.md` |
| `tools/` | `e2e.sh`（全链路联调）、`mock-device/MockDevice.java`（单文件 mock 设备）、`mcp-server/mcp-server.mjs`（零依赖 MCP Server，Node ≥18） |
| `examples/demo-script/` | 脚本包样例（zip 根目录须含 `main.js` + `config.json`） |

## 常用命令

```bash
docker compose up -d                          # MySQL8 + Redis7，仅绑 127.0.0.1，Redis 密码 rpa_redis_dev
cd server && RPA_ADMIN_PASSWORD=admin123 mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 8080；首次自动建表，管理员 admin（密码用 RPA_ADMIN_PASSWORD 预设，未预设则随机生成见日志）
cd server && mvn test                         # 单元测试（Jwt/GrayRule/Auth 等）
cd web && npm install && npm run dev          # 5173，/api 代理到 8080
cd web && npm run typecheck                   # vue-tsc --noEmit（build 也会先跑）
cd android && ./gradlew assembleDebug         # APK 输出 app/build/outputs/apk/debug/
bash tools/e2e.sh                             # 需先起 server；自动清理旧 MOCK 数据并跑全链路
```

## 架构边界（改动前必读对应文档）

- **设备协议**：改 `server/ws/` 或 `android/ws/WsClient.java` 前先读 `docs/protocol.md`。原生 WebSocket `/ws/device`，信封 `{type,msgId,ts,data}`；设备收 `CMD_*` 必须回 `ACK`；离线指令走 Redis 待发队列（Redis 不可用时自动降级跳过补发）；心跳 90s 超时判离线。协议是云端与设备端的双向契约，两端必须同步改。
- **Rhino 沙箱**：`android/api/`（AutoApi/NodeApi/ImageApi 等暴露给脚本的 JS API）改动必须同步更新 `docs/script-development.md`（手册声明与源码一一对应）。`SandboxShutter` 用 ClassShutter 禁 `java.*`（含反射逃逸），不得为绕限制放开。
- **server 分层**：controller（返回 `common/R.java`，code=0 成功）→ service → mapper（MyBatis-Plus）。业务异常抛 `ApiException`，由 `GlobalExceptionHandler` 统一转 R。SQL 全参数化，不走拼接。
- **鉴权三套**：管理端 JWT（`JwtInterceptor`）、设备端 deviceSn+secret 握手（`DeviceAuthInterceptor`，失败 close 4001）、开放 API `/open/v1/**` 用 `X-API-Token`（`ApiTokenInterceptor`，库存 SHA-256 哈希）。密钥/密码不明文落库。
- **web 数据流**：`src/api/http.ts` 已解包 `{code,msg,data}` 并直接返回 `data`，401 时统一登出且必须 `disconnectStomp()`（否则旧 token 每 5s 重连风暴——已处理，勿绕过该封装直接用 axios）。实时推送走 `src/ws/stomp.ts`（带重连补订）。

## 关键约束与坑

- **JWT 启动闸门**：未声明 `dev/local/test` profile 且用默认密钥时服务拒绝启动（`JwtUtil.java`）。本地跑 server 必须带 `-Dspring-boot.run.profiles=dev`。生产必须设 `RPA_JWT_SECRET`（≥32 字节）。
- **配置全走环境变量**：`MYSQL_USER/MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RPA_CORS_ORIGINS`、`MYSQL_SSL` 等，见 `server/src/main/resources/application.yml`。
- **脚本存储**：上传的脚本 zip 落在 `server/data/`（已 gitignore，运行时生成）。设备端按 `scripts/{scriptId}/{versionCode}/` 多版本共存，下载校验 SHA-256，解压有 zip-slip 防护——新增文件处理时保持这两道校验。
- **Android release 构建开了 minify + proguard**（防 OkHttp/Rhino 逆向），新增依赖/反射用法需检查 `proguard-rules.pro`。
- **`.vscode/` 整体被 gitignore**（含本地 `mcp.json`，配置 MCP Server 供 AI 助手 deploy/调试脚本，用法见 `docs/mcp.md`），不要把它当共享配置提交。
- **上传限制**：脚本包 ≤50MB（multipart 配置），上传 zip 有结构白名单校验。
- e2e 依赖 `python3`、`curl`、`$JAVA_HOME`（MockDevice.java 以单文件源码方式直跑）。
