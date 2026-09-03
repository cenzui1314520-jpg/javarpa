# JavaRPA WebSocket 消息协议 v1

云端与 Android 设备之间使用原生 WebSocket（`/ws/device`）双向通信，所有消息为 UTF-8 JSON 文本帧。

## 消息信封

```json
{
  "type": "REGISTER",
  "msgId": "客户端生成的唯一ID",
  "ts": 1690000000000,
  "data": { ... }
}
```

## 握手鉴权

连接 URL：`ws(s)://<host>:<port>/ws/device`，需携带请求头：

- `X-Device-Id`: 设备编号（云端预分配的 deviceSn）
- `X-Device-Secret`: 设备密钥

鉴权失败服务端直接关闭连接（close code 4001）。

## 设备 → 云端

| type | data 字段 | 说明 |
|------|-----------|------|
| REGISTER | deviceName, model, brand, androidVersion, sdkInt, appVersion, engineVersion, installedVersions:[{scriptId, versionCode}] | 连接后第一条消息，上报设备信息与已安装脚本版本（须上报**全部**已装版本，多版本共存；云端按"目标版本是否已装"判定是否下发更新，避免回滚后反复重推） |
| HEARTBEAT | taskId, running, successCount, failCount, battery | 每 30s 一次，running 时携带运行进度 |
| LOG | taskId, level(INFO/WARN/ERROR), tag, content, logTime | 脚本运行日志，实时上报 |
| RESULT | taskId, status(RUNNING/SUCCESS/FAILED/STOPPED), successCount, failCount, errorMsg, duration | 任务状态变化及结束时上报 |
| ACK | refMsgId, ok, error | 对 CMD_* 指令的执行确认 |
| DUMP_UI | refMsgId, tree:{roots:[节点...], nodeCount} | UI 树调试上报（响应 CMD_DUMP_UI）。节点字段：text/id/desc/className/rect{x,y,w,h}/clickable/longClickable/scrollable/enabled/visibleToUser/childCount/children；深度与节点数有截断上限 |
| CAPTURE | refMsgId, width, height, image | 屏幕截图调试上报（响应 CMD_CAPTURE）。width/height 为原始屏幕尺寸，image 为压缩 JPEG（宽≤720，质量 60）的 base64，用于管理端按比例叠加控件框 |

## 云端 → 设备

| type | data 字段 | 说明 |
|------|-----------|------|
| REGISTER_ACK | ok, serverTime | 注册确认，随后可能紧跟补发指令 |
| HEARTBEAT_ACK | serverTime | 心跳确认 |
| CMD_START | taskId, scriptId, versionCode, url, sha256, params | 启动任务，url 为脚本 zip 下载地址 |
| CMD_PAUSE | taskId | 暂停任务（协作式，脚本阻塞在 auto.waitIfPaused） |
| CMD_STOP | taskId | 停止任务（中断脚本线程） |
| CMD_RESTART | taskId | 重启任务（= STOP + START） |
| CMD_UPDATE_SCRIPT | scriptId, versionCode, url, sha256 | 热更新脚本包 |
| CMD_DUMP_UI | （无额外字段） | 请求设备上报当前完整控件树（云端 UI 检查器用；结果走 DUMP_UI 上行，不进离线补发队列） |
| CMD_CAPTURE | （无额外字段） | 请求设备上报当前屏幕截图（结果走 CAPTURE 上行，不进离线补发队列；设备需 Android 11+ 且无障碍服务运行中） |

## 指令可靠性

- 设备收到 CMD_* 后必须回 ACK（ok=false 时带 error 说明）。
- 设备离线期间的指令存入云端待发队列，设备 REGISTER 后按序补发；**调试类指令（CMD_DUMP_UI/CMD_CAPTURE）例外**——实时性优先，设备不在线时云端直接报错，不入队补发。
- HEARTBEAT 超过 90s 未收到，云端判定设备离线。

## 大 payload 约定

- 所有消息仍为 UTF-8 JSON **文本帧**，二进制内容（如截图）以 base64 字符串放入 data 字段。
- CAPTURE 的 base64 约 100KB 量级；服务端 WebSocket 容器文本帧缓冲上限已放大到 4MB，若后续新增更大 payload（如视频流）应改走 HTTP 旁路而非继续塞 WS。

## 脚本包规范

zip 包，根目录必须包含 `main.js`（入口）与 `config.json`（元信息：name/version/entry），额外资源文件随包分发。设备按 `scripts/{scriptId}/{versionCode}/` 目录隔离多版本，下载后先校验 sha256 再解压安装。
