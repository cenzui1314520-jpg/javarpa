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

## 云端 → 设备

| type | data 字段 | 说明 |
|------|-----------|------|
| REGISTER_ACK | ok, serverTime | 注册确认，随后可能紧跟补发指令 |
| HEARTBEAT_ACK | serverTime | 心跳确认 |
| CMD_START | taskId, scriptId, versionCode, url, md5, params | 启动任务，url 为脚本 zip 下载地址 |
| CMD_PAUSE | taskId | 暂停任务（协作式，脚本阻塞在 auto.waitIfPaused） |
| CMD_STOP | taskId | 停止任务（中断脚本线程） |
| CMD_RESTART | taskId | 重启任务（= STOP + START） |
| CMD_UPDATE_SCRIPT | scriptId, versionCode, url, md5 | 热更新脚本包 |

## 指令可靠性

- 设备收到 CMD_* 后必须回 ACK（ok=false 时带 error 说明）。
- 设备离线期间的指令存入云端待发队列，设备 REGISTER 后按序补发。
- HEARTBEAT 超过 90s 未收到，云端判定设备离线。

## 脚本包规范

zip 包，根目录必须包含 `main.js`（入口）与 `config.json`（元信息：name/version/entry），额外资源文件随包分发。设备按 `scripts/{scriptId}/{versionCode}/` 目录隔离多版本，下载后先校验 md5 再解压安装。
