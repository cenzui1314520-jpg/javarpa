# JavaRPA 脚本开发手册

> 版本 v1.1 · 适用引擎 rhino-1.7.14 · 文中所有 API 与设备端 `com.rpa.engine.api.*` 源码一一对应

---

## 目录

1. [概述](#1-概述)
2. [五分钟上手](#2-五分钟上手)
3. [脚本包规范](#3-脚本包规范)
4. [运行模型与生命周期](#4-运行模型与生命周期)
5. [API 完整参考](#5-api-完整参考)
6. [典型场景配方](#6-典型场景配方)
7. [调试与排错](#7-调试与排错)
8. [发布与版本管理](#8-发布与版本管理)
9. [沙箱边界](#9-沙箱边界)
10. [FAQ](#10-faq)
11. [附录：API 速查表](#11-附录api-速查表)

---

## 1. 概述

### 1.1 脚本是什么

脚本是一个 **JavaScript zip 包**，由云端统一管理版本，通过热更新下发到设备，在内置于 App 的 **Rhino 沙箱引擎**中执行。开发脚本**完全不需要接触 Android / 云端代码**。

```
┌─────────── 云端 ───────────┐          ┌──────────── 设备端 ────────────┐
│ 管理后台                    │          │ RPA 核心引擎 App                │
│  · 脚本版本管理/灰度/回滚    │  发布→    │  · 脚本仓库(多版本并存)          │
│  · 任务调度(启停/CRON/重试)  │ ───────→ │  · Rhino 沙箱 ←── main.js      │
│  · 实时日志/统计/告警        │ ←─────── │    ├ auto 选择器/手势/截图      │
└────────────────────────────┘ 日志/计数 │    └ 无障碍服务(跨App操作)      │
                                        └────────────────────────────────┘
```

### 1.2 能力总览

| 能力类别 | 支持情况 | 脚本入口 |
|----------|---------|---------|
| 控件查找（文本/ID/描述/类名/可点击性） | ✅ | `auto.text().findOne()` 等 |
| 点击、长按路径点击、坐标点击 | ✅ | `node.click()` / `auto.tap()` |
| 文本输入、列表滚动 | ✅ | `node.input()` / `node.scrollForward()` |
| 滑动、返回、Home、启动任意 App | ✅ | `auto.swipe()` 等 |
| 截图、取色、全屏找色、模板找图 | ✅ Android 11+ | `auto.screenshot()` |
| 任务参数下发（JSON） | ✅ | 全局变量 `params` |
| 业务成功/失败计数上报 | ✅ | `auto.report.ok()/fail()` |
| 实时日志上报 | ✅ | `log()` |
| 响应云端 启动/暂停/停止/重启 | ✅ | `auto.waitIfPaused()` 等 |
| 脚本内发 HTTP / 多线程 / 文件系统 | ❌ 沙箱禁止 | — |

### 1.3 适用场景

- 普通 App 自动化：用**控件选择器**（稳定、不受分辨率影响）
- 游戏 / 自绘 / 画布界面（无系统控件）：用**截图 + 找色/找图 + 坐标点击**
- 批量重复作业：循环 + 计数上报 + 云端统计成功率

---

## 2. 五分钟上手

### 2.1 准备

- 设备已安装 RPA 引擎 App，填好服务器地址 / deviceSn / secret，启动引擎
- 系统设置 → 无障碍 → 开启「RPA 自动化服务」

### 2.2 编写

新建目录 `my-first-script/`：

**main.js**

```js
log("hello rpa, 任务参数:", JSON.stringify(params));
log("设备:", device.info.model, "屏幕:", device.width, "x", device.height);

var ok = auto.clickText("登录");
log("点击登录结果:", ok);
```

**config.json**

```json
{ "name": "my-first-script", "version": "1.0.0", "entry": "main.js" }
```

### 2.3 打包上传

```bash
cd my-first-script
zip -r ../v1.zip main.js config.json
```

管理后台 → 脚本管理 → 新建脚本（包名如 `demo.first`）→ 上传新版本（versionCode=1）→ 发布（全量）。

### 2.4 运行与看日志

任务管理 → 新建任务（绑定脚本与设备）→ 启动 → 设备详情页「实时日志」即可看到 `log()` 输出。

---

## 3. 脚本包规范

### 3.1 目录结构

```
my-script.zip          ← zip 根目录就是脚本根目录（不要多包一层文件夹！）
├── main.js            ← 必须：入口脚本
├── config.json        ← 必须：元信息
└── res/               ← 可选：模板图片、文本等资源，随包热更新到设备
    ├── btn_ok.png
    └── words.txt
```

### 3.2 config.json 字段

```json
{
  "name": "demo-calculator",   // 脚本名（展示用）
  "version": "1.0.0",          // 语义化版本名（展示用）
  "entry": "main.js"           // 入口文件名
}
```

> versionCode（整数，云端上传时填写）才是热更新与回滚的依据；`version` 只是给人看的名字。

### 3.3 上传校验规则（拒绝即报错）

| 规则 | 说明 |
|------|------|
| zip 根目录必须含 `main.js` 与 `config.json` | 缺一即拒 |
| 条目名禁止 `..` 与绝对路径 | 防 zip-slip 攻击 |
| 包大小 ≤ 50MB | 服务端限制 |
| versionCode 唯一且递增 | 同一版本号不能重复上传 |

### 3.4 常见打包错误

```bash
# ❌ 错误：把目录整个压进去，zip 根是 my-script/
zip -r v1.zip my-script/

# ✅ 正确：在脚本目录内打包
cd my-script && zip -r ../v1.zip main.js config.json res/
```

---

## 4. 运行模型与生命周期

### 4.1 执行模型

- **一任务一线程**：每台设备同一时刻只运行一个任务；新任务启动会先停止旧任务
- **参数注入**：任务配置的 JSON 参数解析为全局对象 `params`，未配置时为 `{}`
- **标准 JS 环境**：Rhino 1.7.14（ES5 完整支持，`let/const/箭头函数/模板字符串` 等 ES6 特性可用；建议团队统一用 ES5 风格保证兼容）
- **无障碍依赖**：所有控件/手势/截图 API 依赖无障碍服务；未开启时 API 返回 `null/false`，脚本可用 `auto.isAccessibilityOn()` 自检

### 4.2 任务状态机

```
云端下发 CMD_START
        │
        ▼
    RUNNING ──脚本正常结束──────────→ SUCCESS
        │ │
        │ ├──JS抛异常/运行错误──────→ FAILED ──(未超过 maxRetries)──→ 自动重发 START
        │ │
        │ ├──auto.stop() 标记后结束─→ STOPPED
        │ └──云端"停止"/线程中断────→ STOPPED
        └──云端"暂停"→ 脚本在下个 waitIfPaused() 检查点阻塞 → "重启"/"启动"后继续
```

| 结束方式 | 云端结果 | 是否触发失败重试 |
|----------|---------|-----------------|
| 脚本跑完最后一条语句 | `SUCCESS` | 否 |
| 抛出异常（`throw` / 调用错误） | `FAILED`，异常信息进入执行记录 | 是（按任务 `maxRetries` 整脚本重跑） |
| 云端停止 / `auto.stop()` 结束 | `STOPPED` | 否 |
| 设备离线/引擎被杀 | 状态停留在 RUNNING，心跳超时判离线 | 重连后可再下发 |

### 4.3 心跳与进度

脚本运行期间设备每 30s 上报心跳，携带当前 `auto.report` 计数；任务详情页的成功/失败数、统计报表均来自这条链路。

### 4.4 热更新时机

| 动作 | 对设备的影响 |
|------|-------------|
| 发布新版本 | 在线设备立即下载安装（ACK 确认）；离线设备下次连接注册时补装 |
| 对运行中的任务 | **不打断**，当前任务用旧版跑完；下次 START/RESTART 用新版本 |
| 回滚 | 等价于把旧 versionCode 以全量方式重新发布 |

---

## 5. API 完整参考

### 5.1 全局函数与变量

| 全局 | 签名 | 说明 |
|------|------|------|
| `params` | Object | 任务参数（创建任务时填写），示例：`params.username` |
| `auto` | Object | 自动化 API（见 5.2 ~ 5.7） |
| `device` | Object | 设备信息（见 5.6） |
| `log` | `log(a, b, ...)` | 打日志并**实时上报云端**；参数以空格拼接 |
| `toast` | `toast(msg)` | 设备屏幕弹短提示（不阻塞） |
| `sleep` | `sleep(ms)` | 睡眠；期间响应云端暂停/停止，被停止时抛出中断结束 |

```js
log("文本", 123, {a:1});        // → "文本 123 [object Object]"
log("建议对象转字符串:", JSON.stringify(params));
```

### 5.2 控件选择器

**构造（可链式组合）：**

| 调用 | 匹配规则 |
|------|---------|
| `auto.text("登录")` | 文本**完全相等** |
| `auto.textContains("登录")` | 文本**包含**（应对带数字/后缀的动态文本首选） |
| `auto.id("btn_ok")` | viewIdResourceName **后缀匹配**（`com.xx:id/btn_ok` 只需传 `btn_ok`） |
| `auto.desc("返回")` | contentDescription 完全相等 |
| `.type("android.widget.EditText")` | 控件类名相等（链式追加） |
| `.clickable(true)` | 是否可点击（链式追加） |

**取结果：**

| 方法 | 返回 | 行为 |
|------|------|------|
| `.findOne(timeoutMs)` | `Node \| null` | 轮询查找（间隔 200ms），超时返回 null；`findOne(0)` 只查一次 |
| `.find()` | `Node \| null` | 等价 `findOne(0)` |
| `.exists()` | boolean | 是否存在 |
| `.findAll()` | Node 数组 | 返回**全部**匹配项 |

> 查找范围：当前活动窗口 + 全部分屏/悬浮窗的节点树。**没有等待页面加载一说**——一律用带超时的 `findOne`。

**快捷方式：**

| 调用 | 等价于 |
|------|--------|
| `auto.clickText("登录")` | `auto.text("登录").findOne(8000)` 后点击 |
| `auto.clickId("btn_ok")` | `auto.id("btn_ok").findOne(8000)` 后点击 |

**Node 对象：**

| 方法 | 返回 | 说明 |
|------|------|------|
| `node.click()` | boolean | 优先控件点击；控件不可点击时自动**向上最多 5 层**找可点击父级；仍失败则对控件中心**坐标点击** |
| `node.input("文本")` | boolean | **清空后输入**（无障碍 SET_TEXT） |
| `node.scrollForward()` | boolean | 向前滚动该容器 |
| `node.text()` | String | 控件文本（可能为 null） |
| `node.desc()` | String | contentDescription |
| `node.id()` | String | 完整 viewId（如 `com.xx:id/btn_ok`） |
| `node.rect()` | `{x, y, width, height, centerX, centerY}` | 屏幕坐标 |
| `node.exists()` | boolean | 恒可用于判空习惯写法 |

### 5.3 手势与系统操作

| 调用 | 返回 | 说明 |
|------|------|------|
| `auto.tap(x, y)` | boolean | 坐标点击（约 50ms 手势） |
| `auto.swipe(x1, y1, x2, y2)` | boolean | 滑动，默认 300ms |
| `auto.swipe(x1, y1, x2, y2, ms)` | boolean | 指定时长（长滑动/慢速拖拽） |
| `auto.back()` | boolean | 返回键 |
| `auto.home()` | boolean | 回桌面 |
| `auto.launch("com.xxx.yyy")` | boolean | 启动 App（未安装返回 false） |

坐标均基于**屏幕绝对像素**；多分辨率适配用 `device.width/height` 换算（见 6.6）。

### 5.4 截图 / 取色 / 找色 / 找图（Android 11+）

```js
var img = auto.screenshot();
if (!img) throw new Error("截图失败：需 Android 11+ 且无障碍已开启");
```

| 方法 | 返回 | 说明 |
|------|------|------|
| `auto.screenshot()` | `Image \| null` | 截全屏；像素即时拷贝进内存，无需手动释放 |
| `img.width` / `img.height` | int | 截图尺寸 |
| `img.pixel(x, y)` | `"#RRGGBB"` \| null | 指定点颜色，越界返回 null |
| `img.findColor("#FF4444", threshold)` | `{x, y}` \| null | 全屏按行扫描找**第一个**匹配点 |
| `img.findImage("res/btn.png", threshold)` | `{x, y}` \| null | 模板匹配，返回模板**左上角**坐标；模板必须是脚本包内相对路径 |
| `img.save("out/1.png")` | boolean | 保存 PNG 到脚本包目录（排查取证用） |

- `threshold`：颜色容差，`R/G/B` **各分量**允许的偏差，`0` = 严格相等。找图建议 8~16，找色建议 0~10
- 帧率建议：循环截图间隔 ≥ 200ms，避免截图洪泛拖慢设备
- 安全限制：带 `FLAG_SECURE` 的窗口（银行/支付类）截图为黑屏，属系统行为

### 5.5 业务计数上报

| 调用 | 说明 |
|------|------|
| `auto.report.ok()` / `auto.report.fail()` | 成功/失败计数 +1 |
| `auto.report.okN(5)` / `auto.report.failN(2)` | 批量加减 |
| `auto.report.getOk()` / `auto.report.getFail()` | 读当前计数 |

计数随心跳与结果上报 → 任务详情、成功率/失败率报表、成功/失败次数统计。**这是脚本向云端输出业务结果的唯一通道**（除日志外）。

### 5.6 设备信息

| 调用/属性 | 返回 |
|-----------|------|
| `device.info` | `{model, brand, sdkInt, androidVersion, screen:{width,height,density}}` |
| `device.width` / `device.height` | 屏幕宽/高（px） |

### 5.7 生命周期控制

| 调用 | 说明 |
|------|------|
| `auto.waitIfPaused()` | **暂停检查点**：被暂停时阻塞在此，恢复后继续；被停止时抛出中断。长循环内应周期调用 |
| `auto.isPaused()` | 当前是否处于暂停 |
| `auto.stop()` | 标记结束：脚本**继续执行到自然结束**，结果记为 `STOPPED`（不触发重试）。需"立刻中止"请 `throw new Error("...")` 或靠 `sleep` 中断 |
| `auto.isAccessibilityOn()` | 无障碍服务是否已开启（脚本开头自检并 log 提示） |

> `sleep()` 期间可被**停止**（线程中断，在最近的 sleep 点退出）；而**暂停**只会在 `waitIfPaused()` 检查点生效——未写检查点的脚本被暂停时会继续跑到自然结束。因此长循环脚本务必周期调用 `auto.waitIfPaused()`。

---

## 6. 典型场景配方

以下均为完整可运行片段，可直接改造。

### 6.1 登录流程（控件模式）

```js
// 任务参数: {"username":"user01","password":"123456"}
auto.launch("com.example.app");
sleep(3000);

var account = auto.type("android.widget.EditText").findOne(8000);
if (!account) throw new Error("账号输入框未出现");
account.input(params.username);

var pwd = auto.type("android.widget.EditText").findAll()[1];   // 第二个输入框
if (pwd) pwd.input(params.password);

if (auto.clickText("登录")) {
  auto.report.ok();
  log("登录点击成功");
} else {
  auto.report.fail();
  throw new Error("登录按钮未找到");
}
```

### 6.2 列表滑动查找目标（控件模式）

```js
function scrollFind(textPart, maxScroll) {
  for (var i = 0; i < maxScroll; i++) {
    auto.waitIfPaused();
    var hit = auto.textContains(textPart).findOne(0);
    if (hit) return hit;
    auto.swipe(device.width / 2, device.height * 0.7,
               device.width / 2, device.height * 0.3, 400);
    sleep(600);
  }
  return null;
}

var target = scrollFind("张三的订单", 20);
if (target) { target.click(); auto.report.ok(); }
else { log("翻完列表未找到目标"); auto.report.fail(); }
```

### 6.3 等待页面就绪（通用模式）

```js
function waitPage(idSuffix, timeoutMs) {
  return auto.id(idSuffix).findOne(timeoutMs) != null;
}

if (!waitPage("main_content", 10000)) {
  throw new Error("首页 10s 未加载完成");
}
log("进入首页");
```

### 6.4 游戏自绘界面（截图找图状态机）

```js
// 脚本包内准备: res/btn_fight.png（战斗按钮）、res/btn_close.png（弹窗关闭）
var ROUNDS = params.rounds || 10;

for (var i = 0; i < ROUNDS; i++) {
  auto.waitIfPaused();                          // 暂停检查点
  var img = auto.screenshot();
  if (!img) throw new Error("截图失败");

  var close = img.findImage("res/btn_close.png", 10);
  if (close) {                                  // 先清弹窗
    auto.tap(close.x + 30, close.y + 15);
    sleep(800);
    continue;
  }

  var fight = img.findImage("res/btn_fight.png", 10);
  if (fight) {
    auto.tap(fight.x + 40, fight.y + 20);       // 模板内偏移到按钮中心
    auto.report.ok();
    log("第", i + 1, "轮战斗已开启");
  } else {
    auto.report.fail();
    log("未找到战斗按钮");
  }
  sleep(1200);
}
```

### 6.5 弹窗自动防护（复用函数）

```js
function dismissPopups() {
  ["我知道了", "暂不升级", "以后再说", "跳过", "取消", "残忍拒绝"].forEach(function (t) {
    var btn = auto.text(t).findOne(0);
    if (btn) { btn.click(); sleep(300); log("已关闭弹窗:", t); }
  });
}

dismissPopups();        // 每个关键步骤前调用
```

### 6.6 多分辨率坐标适配

```js
// 把 1080x2400 设计稿坐标换算为当前设备坐标
var W = device.width, H = device.height;
function px(x) { return Math.round(x * W / 1080); }
function py(y) { return Math.round(y * H / 2400); }

auto.tap(px(540), py(1800));                    // 设计稿 (540,1800) → 当前设备
auto.swipe(px(540), py(1800), px(540), py(600), 350);
```

### 6.7 批量作业 + 阶段性取证

```js
var TOTAL = params.count || 50;
for (var i = 1; i <= TOTAL; i++) {
  auto.waitIfPaused();
  if (!auto.clickId("btn_next")) { auto.report.fail(); log("第", i, "条失败"); }
  else auto.report.ok();
  sleep(500);

  if (i % 10 === 0) {                           // 每 10 条截一张证
    var shot = auto.screenshot();
    if (shot) shot.save("out/progress-" + i + ".png");
    log("进度:", i + "/" + TOTAL);
  }
}
```

---

## 7. 调试与排错

### 7.1 日志驱动调试法

1. 关键步骤前后 `log()`：进入分支、拿到的值、控件是否存在
2. 坐标问题先 `log(device.width, device.height)` 与 `node.rect()`
3. 找图问题 `img.save()` 落盘比对模板（路径在设备 `/data/data/com.rpa.engine/files/scripts/<scriptId>/<versionCode>/out/`，`adb pull` 取回）
4. 后台「运行日志」按设备/任务检索历史，「设备详情」看实时日志

### 7.2 常见错误对照表

| 现象 | 原因 | 处理 |
|------|------|------|
| `findOne` 永远 null | 无障碍未开启 | 脚本开头 `auto.isAccessibilityOn()` 自检并 log |
| | 文本前后有空格/动态后缀 | 改用 `textContains` |
| | 控件在 WebView 内且 id 为动态串 | 用文本/描述定位，勿用动态 id |
| | 页面还没加载完 | 加大超时，或先 `waitPage()` |
| `click()` 返回 true 但界面无反应 | 命中目标非真实热区 | 用 `node.rect()` 取中心后 `auto.tap()`；或改找父级可点击容器 |
| `screenshot()` 返回 null | 系统 < Android 11 | 升级设备或改用控件模式 |
| | 无障碍未开启 | 同上自检 |
| 截图全黑 | 目标 App 设置了 FLAG_SECURE | 系统限制，无法截图（银行/支付类常见） |
| 找图偶尔命中偶尔不中 | 动画/缩放导致像素抖动 | 提高 threshold 至 10~16；模板截小而特征明显的区域 |
| 执行结果 FAILED 且反复重跑 | 脚本抛异常 | 看执行记录的 errorMsg 定位行号 |
| 任务一直 RUNNING | 脚本死循环无 sleep | 循环体必须 sleep（也保证可暂停/停止） |
| 点击被系统判定为自动化拦截 | 部分App检测无障碍 | 属风控对抗范畴，本系统不内置绕过 |

### 7.3 性能建议

- `findOne` 超时按需设置，不要到处 8000
- 全屏 `findColor`/`findImage` 单次约 100~500ms，状态机轮询间隔 ≥ 200ms
- `findAll()` 在长列表上可能返回大量节点，尽量组合更精确的条件
- 避免在同一截图对象上重复模板匹配多个目标——截图一次，多次 findImage 复用

---

## 8. 发布与版本管理

### 8.1 版本策略

| 项 | 建议 |
|----|------|
| versionCode | 从 1 严格递增，不回收、不复用 |
| versionName | 语义化（1.0.0 → 1.1.0），只影响展示 |
| changelog | 每版必填，回滚时靠它判断"该回到哪一版" |

### 8.2 发布通道（同一版本可选）

| 方式 | 范围 | 典型用法 |
|------|------|---------|
| `PERCENT` 灰度 | 按 deviceSn 哈希**稳定**命中 N% 设备 | 10% 观察 → 50% → 100% |
| `GROUP` 分组 | 指定设备分组（可多选） | 先发"测试机组" |
| `ALL` 全量 | 所有设备，并成为"稳定版本" | 灰度验证后的正式发布 |

命中规则（从最新发布记录向旧回溯）：设备先匹配**更新的**发布记录；灰度未命中的设备自动停留在上一个命中的版本，而非直接跳稳定版。

### 8.3 回滚

脚本详情 → 旧版本行 →「回滚到此版」= 将旧 versionCode 以 `ALL` 重新发布。设备多版本并存，回滚无需重新下载历史包。

### 8.4 生效时机速查

| 你的操作 | 何时生效 |
|----------|---------|
| 上传新版本（未发布） | 设备**不会**变化 |
| 发布 | 在线设备立即下载安装；离线设备上线时补装 |
| 发布后运行中的任务 | 当前跑完仍用旧版；下一次 启动/重启 用新版 |
| 任务里显式指定 versionCode | 永远用指定版本，不跟随稳定版 |
| 任务版本留空 | 每次启动自动用该脚本当前"稳定版本" |

---

## 9. 沙箱边界

| 项 | 状态 | 说明 |
|----|------|------|
| `java.*` / `javax.*` / 反射 / `importClass` / `Packages` | ❌ 禁止 | ClassShutter 白名单拦截，尝试即报错 |
| 新建线程 / 操作系统命令 / 任意文件读写 | ❌ 禁止 | 同上 |
| HTTP / Socket 网络请求 | ❌ 暂不支持 | 参数由云端下发；结果用 report 计数上报（规划中可扩展引擎 API） |
| `log/toast/sleep/JSON/Math/Date/RegExp` 等标准 JS | ✅ | — |
| 脚本包内文件 | 只读资源 + `img.save()` 写出 | 截图/取证输出到脚本目录 |

---

## 10. FAQ

**Q1：脚本怎么拿到外部数据（比如今天要处理的订单号列表）？**
创建任务时把数据放进任务参数 JSON（`params`）。数据量大时建议由你的业务系统通过**开放 API**（`POST /open/v1/tasks`，X-API-Token 鉴权）动态建任务下发。

**Q2：脚本能把结果数据回传到我的业务系统吗？**
当前版本结果通道是 `auto.report` 成功/失败计数 + 日志，可在后台/API 查询统计。逐条明细回传建议在业务侧通过开放 API 拉取日志/统计；脚本直连业务接口属规划中的引擎 API 扩展。

**Q3：一台设备能同时跑几个任务？**
一个。新任务启动会先停掉旧任务（云端也有同样的互斥控制）。

**Q4：为什么 `auto.stop()` 之后脚本还在跑？**
`stop()` 只是标记"结果记为 STOPPED"，不中断执行流；需要立即中止请 `throw`（但会记为 FAILED 且可能触发重试）或由云端下发"停止"（线程中断，在最近的 sleep/等待点退出，记为 STOPPED）。

**Q5：脚本最长能跑多久？**
无硬限制。长驻脚本务必：循环 + `sleep` + `auto.waitIfPaused()`，保证可被云端管控；心跳超时（90s 无心跳）会被判离线。

**Q6：能操作锁屏界面吗？**
部分可以（无障碍能覆盖多数界面），但锁屏、系统安全确认等界面受系统限制；建议脚本运行时设备保持亮屏解锁。

**Q7：不同品牌设备行为差异大吗？**
控件模式基本一致；坐标/找图模式注意分辨率与缩放（用 6.6 的适配函数）；个别厂商对无障碍服务有省电查杀，需在设置中给引擎 App 加电池白名单。

**Q8：怎么在真机上快速试一句 API？**
后台建一个"调试任务"绑定单台测试机，改脚本 → 上传新 versionCode → 发布到该设备分组 → 重启任务 → 看实时日志。整个闭环约 30 秒。

---

## 11. 附录：API 速查表

```js
// ── 全局 ────────────────────────────────
params                    // 任务参数对象
log(a, b, ...)            // 实时日志
toast(msg)                // 屏幕提示
sleep(ms)                 // 睡眠(响应暂停/停止)

// ── auto · 选择器 ───────────────────────
auto.text(s)              // 文本全等
auto.textContains(s)      // 文本包含
auto.id(s)                // viewId 后缀
auto.desc(s)              // 描述全等
  .type(cls)              // + 类名
  .clickable(bool)        // + 可点击
  .findOne(ms) → Node|null  // 轮询查找(0=只查一次)
  .find() / .exists() / .findAll()

// ── auto · 快捷点击 ─────────────────────
auto.clickText(s)         // 找文本并点击(8s)
auto.clickId(s)           // 找 id 并点击(8s)

// ── Node ────────────────────────────────
node.click()              // 点击(自动向上找可点击父级+坐标兜底)
node.input(text)          // 清空并输入
node.scrollForward()
node.text() / .desc() / .id()
node.rect()               // {x,y,width,height,centerX,centerY}

// ── auto · 手势/系统 ────────────────────
auto.tap(x, y)
auto.swipe(x1,y1,x2,y2[,ms])
auto.back() / auto.home()
auto.launch(pkg)

// ── auto · 截图/找图(Android 11+) ───────
var img = auto.screenshot()   // → Image|null
img.width / img.height
img.pixel(x, y)           // "#RRGGBB"
img.findColor(hex, th)    // {x,y}|null
img.findImage(rel, th)    // {x,y}|null (模板在脚本包内)
img.save(relPath)         // 存PNG到脚本包目录

// ── auto · 计数/生命周期 ─────────────────
auto.report.ok() / .fail() / .okN(n) / .failN(n)
auto.report.getOk() / .getFail()
auto.waitIfPaused()       // 暂停检查点
auto.isPaused() / auto.isAccessibilityOn()
auto.stop()               // 标记结束(结果=STOPPED)

// ── device ──────────────────────────────
device.width / device.height
device.info               // {model,brand,sdkInt,androidVersion,screen}
```

---

*相关文档：设备端通信协议 [protocol.md](protocol.md) · 云端 REST 接口 `http://<server>/swagger-ui.html` · 系统总览见仓库 [README](../README.md)*
