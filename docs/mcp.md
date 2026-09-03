# JavaRPA MCP Server

让 AI 编程助手（VS Code Copilot / Claude Desktop / Cursor）通过 [MCP](https://modelcontextprotocol.io) 直接**编写、部署、调试**设备端脚本，替代手动 zip 打包 + 后台上传的流程。

零依赖，仅需 Node ≥ 18：`tools/mcp-server/mcp-server.mjs`

## 工作流闭环

```
AI 本地写脚本(main.js/config.json)
   │ deploy_script  ← 打包+上传+发布 一条龙（自动 versionCode 递增）
   ▼
task_action(start) ──→ 设备热更新执行
   │ get_logs        ← 脚本 log() 实时回传
   ▼
发现问题 → 改代码 → 再 deploy_script …… 循环
```

## 工具一览

| 工具 | 用途 |
|------|------|
| `get_script_api_docs` | 取回脚本 JS API 开发文档（写脚本前先调它） |
| `list_scripts` / `create_script` | 脚本管理 |
| `upload_script_version` | 打包上传新版本（不发布） |
| `deploy_script` | **上传 + 发布一条龙，调试主力**；支持 `dir`（本地目录）或 `mainJs`（内联代码） |
| `publish_script` | 发布/灰度/回滚指定版本 |
| `read_script_version` | 读回线上某版本的脚本内容做 diff |
| `list_devices` | 设备与在线状态 |
| `list_tasks` / `create_task` / `task_action` / `get_task_detail` | 任务全生命周期 |
| `get_logs` | 查脚本运行日志（可按设备/任务/级别过滤） |
| `dump_ui_tree` | **让在线设备 dump 当前控件树**（className/text/id/rect/clickable），写选择器前定位控件用 |
| `capture_screen` | 让在线设备截屏并保存本地 JPEG，配合控件 rect 坐标核对位置 |

## 配置

### VS Code（本仓库已内置 [.vscode/mcp.json](../.vscode/mcp.json)）

重载窗口后在「聊天 → 工具」中启用 `javarpa` 即可。

### Claude Desktop / Cursor

```json
{
  "mcpServers": {
    "javarpa": {
      "command": "node",
      "args": ["/path/to/javarpa/tools/mcp-server/mcp-server.mjs"],
      "env": {
        "RPA_SERVER_URL": "http://localhost:8080",
        "RPA_ADMIN_USER": "admin",
        "RPA_ADMIN_PASSWORD": "admin123",
        "RPA_DATA_DIR": "/path/to/javarpa/server/data/scripts"
      }
    }
  }
}
```

### 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `RPA_SERVER_URL` | `http://localhost:8080` | 云端地址 |
| `RPA_ADMIN_USER` / `RPA_ADMIN_PASSWORD` | `admin` / `admin123` | 管理员账号（JWT 自动登录续期）。密码须与服务端启动时 `RPA_ADMIN_PASSWORD` 预设值一致；服务端未预设时为随机密码（见其启动日志） |
| `RPA_DATA_DIR` | `<repo>/server/data/scripts` | 脚本 zip 落盘目录，`read_script_version` 用（需与云端同机） |

## 典型调试会话示例

> 用户：帮我在计算器上自动算 3+5
>
> AI 动作序列：
> 1. `get_script_api_docs` → 获取 auto API
> 2. `create_script(name="计算器加法", pkgName="demo.add")`（已存在则跳过）
> 3. 本地写 `main.js`（`auto.launch(...)`、`auto.clickText(...)`、关键步骤 `log()`）
> 4. `deploy_script(pkgName="demo.add", dir="scripts/demo.add")`
> 5. `list_devices` → `create_task(name="调试", scriptId, deviceIds=[..])`
> 6. `task_action(taskId, "start")` → 等几秒 → `get_logs(deviceId)`
> 7. 日志报错/没点中 → 改 main.js → 回到 4

## 手动自检（不依赖任何客户端）

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_scripts","arguments":{}}}' \
  | node tools/mcp-server/mcp-server.mjs
```
