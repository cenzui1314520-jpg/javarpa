#!/usr/bin/env node
/**
 * JavaRPA MCP Server (stdio, 零依赖, Node >= 18)
 *
 * 让 AI 编程助手直接完成脚本「编写 → 打包上传 → 发布 → 运行 → 看日志」闭环，
 * 无需手动 zip 打包再进后台上传。
 *
 * 配置(环境变量):
 *   RPA_SERVER_URL     云端地址，默认 http://localhost:8080
 *   RPA_ADMIN_USER     管理员账号，默认 admin
 *   RPA_ADMIN_PASSWORD 管理员密码，默认 admin123（须与服务端启动时 RPA_ADMIN_PASSWORD
 *                      预设值一致；服务端未预设时密码随机生成，见其启动日志）
 *   RPA_DATA_DIR       脚本 zip 落盘目录（读回线上版本用），默认 <repo>/server/data/scripts
 *
 * 用法: node tools/mcp-server/mcp-server.mjs
 */
import readline from 'node:readline';
import zlib from 'node:zlib';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CFG = {
  baseUrl: (process.env.RPA_SERVER_URL || 'http://localhost:8080').replace(/\/+$/, ''),
  user: process.env.RPA_ADMIN_USER || 'admin',
  password: process.env.RPA_ADMIN_PASSWORD || 'admin123',
  dataDir: process.env.RPA_DATA_DIR || path.resolve(__dirname, '../../server/data/scripts'),
};
const MAX_PACK_BYTES = 50 * 1024 * 1024;

/* ---------------- zip 构建/解析（DEFLATE, PKZIP 规范） ---------------- */

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function dosDateTime(d = new Date()) {
  const time = ((d.getHours() & 0x1f) << 11) | ((d.getMinutes() & 0x3f) << 5) | ((d.getSeconds() / 2) & 0x1f);
  const date = (((d.getFullYear() - 1980) & 0x7f) << 9) | (((d.getMonth() + 1) & 0xf) << 5) | (d.getDate() & 0x1f);
  return { time, date };
}

/** entries: [{name, data:Buffer}] -> zip Buffer */
export function zipBuild(entries) {
  const { time, date } = dosDateTime();
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const e of entries) {
    const nameBuf = Buffer.from(e.name, 'utf8');
    const crc = crc32(e.data);
    const comp = zlib.deflateRawSync(e.data, { level: 9 });
    const useDeflate = comp.length < e.data.length;
    const stored = useDeflate ? comp : e.data;
    const method = useDeflate ? 8 : 0;

    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4);          // version needed
    lh.writeUInt16LE(0, 6);           // flags
    lh.writeUInt16LE(method, 8);
    lh.writeUInt16LE(time, 10);
    lh.writeUInt16LE(date, 12);
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(stored.length, 18);
    lh.writeUInt32LE(e.data.length, 22);
    lh.writeUInt16LE(nameBuf.length, 26);
    lh.writeUInt16LE(0, 28);          // extra len
    locals.push(lh, nameBuf, stored);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4);          // version made by
    ch.writeUInt16LE(20, 6);          // version needed
    ch.writeUInt16LE(0, 8);
    ch.writeUInt16LE(method, 10);
    ch.writeUInt16LE(time, 12);
    ch.writeUInt16LE(date, 14);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(stored.length, 20);
    ch.writeUInt32LE(e.data.length, 24);
    ch.writeUInt16LE(nameBuf.length, 28);
    ch.writeUInt16LE(0, 30);          // extra
    ch.writeUInt16LE(0, 32);          // comment
    ch.writeUInt16LE(0, 34);          // disk start
    ch.writeUInt16LE(0, 36);          // internal attrs
    ch.writeUInt32LE(0, 38);          // external attrs
    ch.writeUInt32LE(offset, 42);
    centrals.push(ch, nameBuf);

    offset += 30 + nameBuf.length + stored.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, eocd]);
}

/** 解析 zip Buffer -> [{name, data:Buffer}] */
export function zipParse(buf) {
  // 从尾部找 EOCD
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65558); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('zip EOCD 未找到(文件损坏?)');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('central directory 损坏');
    const method = buf.readUInt16LE(p + 10);
    const cSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOff = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    // local header 的 extra 长度可能与 central 不同，需按 local 实际值定位数据
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lNameLen + lExtraLen;
    const raw = buf.subarray(dataStart, dataStart + cSize);
    out.push({ name, data: method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw) });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

/* ---------------- 云端 REST 客户端（JWT 自动续期） ---------------- */

let token = null;

async function login() {
  const res = await fetch(`${CFG.baseUrl}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: CFG.user, password: CFG.password }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || body.code !== 0) throw new Error(`登录失败(${res.status}): ${body.msg || res.statusText}`);
  token = body.data.token;
  return token;
}

async function api(method, urlPath, { json, form, raw = false } = {}) {
  const doFetch = async () => {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    let body;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    } else if (form !== undefined) {
      body = form; // FormData, undici 自动补 content-type/boundary
    }
    return fetch(CFG.baseUrl + urlPath, { method, headers, body });
  };
  let res = await doFetch();
  if (res.status === 401 && !urlPath.startsWith('/auth')) { // token 过期重登一次
    await login();
    res = await doFetch();
  }
  if (raw) return res;
  const body = await res.json().catch(() => ({}));
  if (body.code !== 0) throw new Error(`云端返回错误: ${body.msg || res.status}`);
  return body.data;
}

/* ---------------- 工具实现辅助 ---------------- */

function pretty(v) {
  return typeof v === 'string' ? v : JSON.stringify(v, null, 2);
}

async function resolveScript(arg) {
  const id = arg.scriptId ?? arg.id;
  if (id != null) return Number(id);
  if (!arg.pkgName) throw new Error('需要提供 scriptId 或 pkgName');
  const list = await api('GET', '/scripts');
  const s = list.find(x => x.pkgName === arg.pkgName);
  if (!s) throw new Error(`未找到 pkgName=${arg.pkgName} 的脚本，请先 create_script`);
  return s.id;
}

/** 收集脚本包文件：目录模式(dir) 或 内联模式(mainJs/configJson/resFiles) */
function collectFiles(arg) {
  const files = new Map();
  const add = (name, data) => {
    if (files.has(name)) throw new Error(`重复文件: ${name}`);
    files.set(name, data);
  };
  if (arg.dir) {
    const root = path.resolve(arg.dir);
    const stat = fs.statSync(root);
    if (!stat.isDirectory()) throw new Error(`dir 不是目录: ${root}`);
    const walk = (abs, rel) => {
      for (const ent of fs.readdirSync(abs, { withFileTypes: true })) {
        if (ent.name === '.DS_Store' || ent.name === '.git' || ent.name === 'node_modules') continue;
        const childAbs = path.join(abs, ent.name);
        const childRel = rel ? `${rel}/${ent.name}` : ent.name;
        if (ent.isDirectory()) walk(childAbs, childRel);
        else add(childRel, fs.readFileSync(childAbs));
      }
    };
    walk(root, '');
  } else {
    if (!arg.mainJs) throw new Error('内联模式必须提供 mainJs（或改用 dir 指定本地脚本目录）');
    add('main.js', Buffer.from(arg.mainJs, 'utf8'));
    let cfg;
    if (arg.configJson) {
      cfg = JSON.parse(arg.configJson); // 语法校验
    } else {
      cfg = { name: arg.name || 'script', version: '1.0.0', entry: 'main.js' };
    }
    add('config.json', Buffer.from(JSON.stringify(cfg, null, 2), 'utf8'));
    for (const [name, content] of Object.entries(arg.resFiles || {})) {
      add(name.startsWith('res/') ? name : `res/${name}`, Buffer.from(content, 'utf8'));
    }
  }
  if (!files.has('main.js')) throw new Error('脚本包缺少 main.js');
  if (!files.has('config.json')) throw new Error('脚本包缺少 config.json');
  let total = 0;
  for (const d of files.values()) total += d.length;
  if (total > MAX_PACK_BYTES) throw new Error(`包体积 ${(total / 1048576).toFixed(1)}MB 超过 50MB 限制`);
  return files;
}

async function uploadVersion(scriptId, arg) {
  const files = collectFiles(arg);
  const versions = await api('GET', `/scripts/${scriptId}/versions`);
  const maxVc = versions.reduce((m, v) => Math.max(m, v.versionCode), 0);
  const vc = arg.versionCode ?? maxVc + 1;
  if (versions.some(v => v.versionCode === vc)) throw new Error(`versionCode=${vc} 已存在（当前最大 ${maxVc}），可省略 versionCode 自动递增`);
  const cfg = JSON.parse(files.get('config.json').toString('utf8'));
  const zip = zipBuild([...files.entries()].map(([name, data]) => ({ name, data })));
  const fd = new FormData();
  fd.append('file', new Blob([zip], { type: 'application/zip' }), `${vc}.zip`);
  fd.append('versionCode', String(vc));
  fd.append('versionName', arg.versionName || cfg.version || String(vc));
  if (arg.changelog) fd.append('changelog', arg.changelog);
  const ver = await api('POST', `/scripts/${scriptId}/versions`, { form: fd });
  return { ver, files: [...files.keys()], zipBytes: zip.length };
}

/* ---------------- MCP 工具定义 ---------------- */

/** 触发设备调试上报并轮询最近结果（设备须在线；返回 {type, ts, data}）。 */
async function debugFetch(deviceId, kind) {
  const before = await api('GET', `/devices/${deviceId}/debug/latest?type=${kind}`).catch(() => null);
  const beforeTs = before?.ts || 0;
  await api('POST', `/devices/${deviceId}/debug/${kind}`);
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 500));
    const cur = await api('GET', `/devices/${deviceId}/debug/latest?type=${kind}`).catch(() => null);
    if (cur && cur.ts > beforeTs) return cur;
  }
  throw new Error('设备调试上报超时（设备离线，或引擎为不支持调试指令的旧版本）');
}

/** 控件树转紧凑缩进文本，供 LLM 直接读；行数超限时截断。 */
function treeToText(roots, maxLines = 600) {
  const lines = [];
  const walk = (n, depth) => {
    if (lines.length >= maxLines) return;
    const r = n.rect ? ` rect=(${n.rect.x},${n.rect.y} ${n.rect.w}x${n.rect.h})` : '';
    const flags = [n.clickable ? 'clickable' : '', n.scrollable ? 'scrollable' : '', n.enabled === false ? 'disabled' : '']
      .filter(Boolean).join(',');
    const hints = [
      n.text ? `text="${String(n.text).slice(0, 40)}"` : '',
      n.id ? `id=${n.id}` : '',
      n.desc ? `desc="${String(n.desc).slice(0, 30)}"` : '',
    ].filter(Boolean).join(' ');
    lines.push(`${'  '.repeat(depth)}- ${(n.className || '?').split('.').pop()} ${hints}${r}${flags ? ' [' + flags + ']' : ''}`);
    for (const c of n.children || []) walk(c, depth + 1);
  };
  for (const r of roots) walk(r, 0);
  if (lines.length >= maxLines) lines.push('...[节点过多已截断]');
  return lines.join('\n');
}

const TOOLS = [
  {
    name: 'get_script_api_docs',
    description: '获取 JavaRPA 脚本(Rhino JS 沙箱)的 API 开发文档：auto 选择器/手势/截图找色找图、log、report、生命周期等。写脚本前先调用它。',
    inputSchema: { type: 'object', properties: {} },
    handler: async () => {
      const doc = path.resolve(__dirname, '../../docs/script-development.md');
      let text = '';
      try {
        const full = fs.readFileSync(doc, 'utf8');
        const idx = full.indexOf('## 5. API 完整参考');
        text = idx > 0 ? full.slice(idx) : full;
      } catch {
        text = null;
      }
      if (!text) throw new Error(`未找到脚本文档: ${doc}`);
      return `【脚本开发文档（节选自 docs/script-development.md）】\n${text}`;
    },
  },
  {
    name: 'list_scripts',
    description: '列出云端全部脚本及各版本，返回 id/pkgName/版本号/changelog 等。',
    inputSchema: { type: 'object', properties: {} },
    handler: async () => {
      const scripts = await api('GET', '/scripts');
      const out = [];
      for (const s of scripts) {
        const versions = await api('GET', `/scripts/${s.id}/versions`);
        out.push({ ...s, versions: versions.map(v => ({ versionCode: v.versionCode, versionName: v.versionName, changelog: v.changelog, createdAt: v.createdAt })) });
      }
      return pretty(out);
    },
  },
  {
    name: 'create_script',
    description: '在云端新建脚本记录。pkgName 是唯一标识（如 demo.calculator），之后上传版本都用它关联。',
    inputSchema: {
      type: 'object',
      required: ['name', 'pkgName'],
      properties: {
        name: { type: 'string', description: '展示名，如"计算器演示"' },
        pkgName: { type: 'string', description: '包名唯一标识，建议 xx.yy 格式' },
        description: { type: 'string' },
      },
    },
    handler: async (a) => pretty(await api('POST', '/scripts', { json: { name: a.name, pkgName: a.pkgName, description: a.description || '' } })),
  },
  {
    name: 'upload_script_version',
    description: '把脚本打包成 zip 并上传为新版本（自动校验 main.js/config.json，versionCode 省略则自动递增）。不发布。通常直接用 deploy_script 一步完成上传+发布。',
    inputSchema: {
      type: 'object',
      properties: {
        scriptId: { type: 'number' },
        pkgName: { type: 'string', description: '与 scriptId 二选一' },
        dir: { type: 'string', description: '本地脚本目录（含 main.js/config.json，res/ 会整体打包）' },
        mainJs: { type: 'string', description: '内联模式：main.js 全文（与 dir 二选一）' },
        configJson: { type: 'string', description: '内联模式：config.json 内容，可选' },
        resFiles: { type: 'object', description: '内联模式：附加文本资源 {"btn.txt": "..."}，自动放 res/ 下', additionalProperties: { type: 'string' } },
        versionCode: { type: 'number', description: '省略则自动 = 最大版本+1' },
        versionName: { type: 'string' },
        changelog: { type: 'string' },
      },
    },
    handler: async (a) => {
      const scriptId = await resolveScript(a);
      const { ver, files } = await uploadVersion(scriptId, a);
      return pretty({ ok: true, scriptId, uploaded: ver, files });
    },
  },
  {
    name: 'deploy_script',
    description: '调试主工具：打包+上传新版本+发布 一条龙。AI 改完脚本调用它即可让设备热更新，随后用 task_action(start) 运行、get_logs 看输出。',
    inputSchema: {
      type: 'object',
      properties: {
        scriptId: { type: 'number' },
        pkgName: { type: 'string', description: '与 scriptId 二选一' },
        dir: { type: 'string', description: '本地脚本目录' },
        mainJs: { type: 'string', description: '内联模式：main.js 全文' },
        configJson: { type: 'string' },
        resFiles: { type: 'object', additionalProperties: { type: 'string' } },
        versionCode: { type: 'number' },
        versionName: { type: 'string' },
        changelog: { type: 'string' },
        targetType: { type: 'string', enum: ['ALL', 'GROUP', 'PERCENT'], description: '发布目标，默认 ALL' },
        targetValue: { type: 'string', description: 'GROUP=分组id，PERCENT=百分比数值' },
      },
    },
    handler: async (a) => {
      const scriptId = await resolveScript(a);
      const { ver, files } = await uploadVersion(scriptId, a);
      await api('POST', `/scripts/${scriptId}/publish`, {
        json: { versionCode: ver.versionCode, targetType: a.targetType || 'ALL', targetValue: a.targetValue },
      });
      return pretty({ ok: true, scriptId, versionCode: ver.versionCode, published: (a.targetType || 'ALL'), files, next: '用 task_action 启动任务，get_logs 查看输出' });
    },
  },
  {
    name: 'publish_script',
    description: '发布指定版本到设备（ALL 全量 / GROUP 分组 / PERCENT 灰度百分比）。',
    inputSchema: {
      type: 'object',
      required: ['scriptId', 'versionCode'],
      properties: {
        scriptId: { type: 'number' },
        versionCode: { type: 'number' },
        targetType: { type: 'string', enum: ['ALL', 'GROUP', 'PERCENT'] },
        targetValue: { type: 'string' },
      },
    },
    handler: async (a) => {
      await api('POST', `/scripts/${a.scriptId}/publish`, { json: { versionCode: a.versionCode, targetType: a.targetType || 'ALL', targetValue: a.targetValue } });
      return `已发布 scriptId=${a.scriptId} versionCode=${a.versionCode}`;
    },
  },
  {
    name: 'read_script_version',
    description: '读回云端某版本的脚本包内容（main.js/config.json 等），用于对比线上与本地差异。需 MCP 与云端同机（读取 server data 目录）。',
    inputSchema: {
      type: 'object',
      required: ['scriptId', 'versionCode'],
      properties: { scriptId: { type: 'number' }, versionCode: { type: 'number' } },
    },
    handler: async (a) => {
      const scripts = await api('GET', '/scripts');
      const s = scripts.find(x => x.id === Number(a.scriptId));
      if (!s) throw new Error(`脚本不存在: ${a.scriptId}`);
      const zipPath = path.join(CFG.dataDir, s.pkgName, `${a.versionCode}.zip`);
      if (!fs.existsSync(zipPath)) throw new Error(`本地未找到脚本包: ${zipPath}（若云端在远端机器，请在同机运行 MCP 或检查 RPA_DATA_DIR）`);
      const entries = zipParse(fs.readFileSync(zipPath));
      const parts = [`scriptId=${a.scriptId} pkgName=${s.pkgName} versionCode=${a.versionCode} (${entries.length} 个文件)\n`];
      for (const e of entries) {
        if (/\.(png|jpg|jpeg|webp|gif)$/i.test(e.name)) {
          parts.push(`--- ${e.name} --- [二进制 ${e.data.length} 字节]\n`);
        } else {
          let text = e.data.toString('utf8');
          if (text.length > 100_000) text = text.slice(0, 100_000) + '\n...[截断]';
          parts.push(`--- ${e.name} ---\n${text}\n`);
        }
      }
      return parts.join('');
    },
  },
  {
    name: 'list_devices',
    description: '列出全部设备与在线状态（online=1 在线）。创建任务需要 deviceIds。',
    inputSchema: { type: 'object', properties: {} },
    handler: async () => {
      const d = await api('GET', '/devices/page?page=1&size=200');
      return pretty((d.list || []).map(x => ({ id: x.id, deviceSn: x.deviceSn, name: x.name, online: x.online, status: x.status, model: x.model, engineVersion: x.engineVersion, lastActiveAt: x.lastActiveAt })));
    },
  },
  {
    name: 'list_tasks',
    description: '列出全部任务（含绑定脚本、参数、调度方式、设备数）。',
    inputSchema: { type: 'object', properties: {} },
    handler: async () => pretty(await api('GET', '/tasks')),
  },
  {
    name: 'create_task',
    description: '创建任务：绑定脚本版本与设备。调试场景常用 scheduleType=IMMEDIATE，然后 task_action(start) 立即跑。',
    inputSchema: {
      type: 'object',
      required: ['name', 'scriptId', 'deviceIds'],
      properties: {
        name: { type: 'string' },
        scriptId: { type: 'number' },
        versionCode: { type: 'number', description: '省略则用脚本当前发布版本' },
        paramsJson: { type: 'string', description: '脚本内以全局 params 对象读取，如 {"pkg":"com.xxx"}' },
        scheduleType: { type: 'string', enum: ['IMMEDIATE', 'CRON'] },
        cronExpr: { type: 'string' },
        maxRetries: { type: 'number' },
        deviceIds: { type: 'array', items: { type: 'number' } },
      },
    },
    handler: async (a) => pretty(await api('POST', '/tasks', {
      json: {
        name: a.name, scriptId: a.scriptId, versionCode: a.versionCode,
        paramsJson: a.paramsJson || '{}', scheduleType: a.scheduleType || 'IMMEDIATE',
        cronExpr: a.cronExpr, maxRetries: a.maxRetries ?? 0, deviceIds: a.deviceIds,
      },
    })),
  },
  {
    name: 'task_action',
    description: '任务控制：start 启动 / stop 停止 / pause 暂停 / restart 重启 / enable / disable。',
    inputSchema: {
      type: 'object',
      required: ['taskId', 'action'],
      properties: { taskId: { type: 'number' }, action: { type: 'string', enum: ['start', 'stop', 'pause', 'restart', 'enable', 'disable'] } },
    },
    handler: async (a) => {
      await api('POST', `/tasks/${a.taskId}/actions`, { json: { action: a.action } });
      return `任务 ${a.taskId} 已执行 ${a.action}`;
    },
  },
  {
    name: 'get_task_detail',
    description: '任务详情：每台设备的运行状态、成功/失败计数、最近执行记录（含异常信息）。',
    inputSchema: { type: 'object', required: ['taskId'], properties: { taskId: { type: 'number' } } },
    handler: async (a) => pretty(await api('GET', `/tasks/${a.taskId}`)),
  },
  {
    name: 'get_logs',
    description: '查询设备实时日志（脚本 log() 输出会回传云端）。返回最近 N 条并按时间正序排列，调试必用。可按 deviceId/taskId/level 过滤。',
    inputSchema: {
      type: 'object',
      properties: {
        deviceId: { type: 'number' },
        taskId: { type: 'number' },
        level: { type: 'string', enum: ['INFO', 'WARN', 'ERROR'] },
        limit: { type: 'number', description: '默认 100，最大 500' },
      },
    },
    handler: async (a) => {
      const limit = Math.min(Math.max(a.limit ?? 100, 1), 500);
      const q = new URLSearchParams({ page: '1', size: String(limit) });
      if (a.deviceId != null) q.set('deviceId', a.deviceId);
      if (a.taskId != null) q.set('taskId', a.taskId);
      if (a.level) q.set('level', a.level);
      const d = await api('GET', `/logs?${q}`);
      const list = (d.list || []).slice().reverse(); // 服务端 id 倒序 -> 正序展示
      if (!list.length) return '暂无日志（设备可能离线，或脚本还没执行到 log()）';
      return list.map(l => `[${l.createdAt}] [${l.level}] ${l.content}`).join('\n');
    },
  },
  {
    name: 'dump_ui_tree',
    description: '让在线设备上报当前屏幕的完整控件树（无障碍 dump）：每行一个节点，含 className/text/id/desc/rect(屏幕坐标)/clickable 等属性。写选择器 auto.text()/id() 前先用它定位目标控件。',
    inputSchema: {
      type: 'object',
      required: ['deviceId'],
      properties: {
        deviceId: { type: 'number', description: '设备 ID（list_devices 可查）' },
      },
    },
    handler: async (a) => {
      const cur = await debugFetch(Number(a.deviceId), 'dump');
      const tree = cur.data?.tree || {};
      return `nodeCount=${tree.nodeCount ?? 0} 抓取时间=${new Date(cur.ts).toLocaleString('zh-CN')}\n${treeToText(tree.roots || [])}`;
    },
  },
  {
    name: 'capture_screen',
    description: '让在线设备截屏（Android 11+ 引擎），保存为本地 JPEG 并返回路径与屏幕尺寸，配合 dump_ui_tree 的 rect 坐标核对控件位置。',
    inputSchema: {
      type: 'object',
      required: ['deviceId'],
      properties: {
        deviceId: { type: 'number', description: '设备 ID（list_devices 可查）' },
      },
    },
    handler: async (a) => {
      const cur = await debugFetch(Number(a.deviceId), 'capture');
      const d = cur.data || {};
      if (!d.image) throw new Error('设备未返回截图数据');
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'javarpa-debug-'));
      const file = path.join(dir, `device-${a.deviceId}-capture.jpg`);
      fs.writeFileSync(file, Buffer.from(d.image, 'base64'));
      return `已保存: ${file}\n屏幕尺寸: ${d.width}x${d.height}（rect 坐标即此坐标系）`;
    },
  },
];

/* ---------------- MCP stdio 协议（newline-delimited JSON-RPC） ---------------- */

const KNOWN_PROTOCOLS = ['2024-11-05', '2025-03-26', '2025-06-18'];

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

function rpcResult(id, result) { send({ jsonrpc: '2.0', id, result }); }
function rpcError(id, code, message) { send({ jsonrpc: '2.0', id, error: { code, message } }); }

async function handleMessage(msg) {
  if (msg.jsonrpc !== '2.0' || !msg.method) return; // 通知/未知消息忽略
  const { id, method, params } = msg;
  const isRequest = id !== undefined && id !== null;
  try {
    switch (method) {
      case 'initialize':
        rpcResult(id, {
          protocolVersion: KNOWN_PROTOCOLS.includes(params?.protocolVersion) ? params.protocolVersion : '2025-06-18',
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'javarpa-mcp', version: '1.0.0' },
        });
        break;
      case 'notifications/initialized':
      case 'notifications/cancelled':
        break;
      case 'ping':
        rpcResult(id, {});
        break;
      case 'tools/list':
        rpcResult(id, {
          tools: TOOLS.map(t => ({
            name: t.name,
            description: t.description,
            inputSchema: t.inputSchema,
          })),
        });
        break;
      case 'tools/call': {
        const name = params?.name;
        const tool = TOOLS.find(t => t.name === name);
        if (!tool) {
          rpcResult(id, { content: [{ type: 'text', text: `未知工具: ${name}` }], isError: true });
          break;
        }
        try {
          const text = await tool.handler(params?.arguments || {});
          rpcResult(id, { content: [{ type: 'text', text }], isError: false });
        } catch (e) {
          rpcResult(id, { content: [{ type: 'text', text: `执行失败: ${e.message}` }], isError: true });
        }
        break;
      }
      case 'resources/list':
        rpcResult(id, { resources: [] });
        break;
      default:
        if (isRequest) rpcError(id, -32601, `method not found: ${method}`);
    }
  } catch (e) {
    if (isRequest) rpcError(id, -32603, e.message);
  }
}

async function main() {
  process.stderr.write(`[javarpa-mcp] server=${CFG.baseUrl} user=${CFG.user} dataDir=${CFG.dataDir}\n`);
  let pending = 0;
  let closed = false;
  const maybeExit = () => { if (closed && pending === 0) process.exit(0); };
  const rl = readline.createInterface({ input: process.stdin, terminal: false });
  rl.on('line', (line) => {
    const s = line.trim();
    if (!s) return;
    let msg;
    try { msg = JSON.parse(s); } catch { return; }
    pending++;
    handleMessage(msg)
      .catch((e) => process.stderr.write(`[javarpa-mcp] handler error: ${e.stack}\n`))
      .finally(() => { pending--; maybeExit(); });
  });
  rl.on('close', maybeExit);
}

// 直接运行时启动 server；被 import 时不产生副作用（便于单测 zip 函数）
const isMain = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isMain) main();
