<template>
  <div>
    <div class="page-toolbar">
      <el-form-item>
        <el-button :icon="Back" @click="goBack">返回</el-button>
      </el-form-item>
      <el-form-item>
        <span class="dev-title">
          {{ device ? `${device.deviceSn} ${device.name || ''}` : `设备 #${deviceId}` }}
          <el-tag :type="device?.online === 1 ? 'success' : 'info'" size="small" style="margin-left: 8px">
            {{ device?.online === 1 ? '在线' : '离线' }}
          </el-tag>
        </span>
      </el-form-item>
      <div class="toolbar-spacer" />
      <el-form-item>
        <el-switch v-model="autoRefresh" active-text="自动刷新(5s)" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Refresh" :loading="refreshing" @click="refresh">刷新</el-button>
      </el-form-item>
    </div>

    <el-row :gutter="12">
      <el-col :span="11">
        <el-card shadow="never" class="debug-card">
          <template #header>
            <div class="card-head">
              <span>屏幕截图</span>
              <span class="muted">{{ shotInfo }}</span>
            </div>
          </template>
          <div ref="shotWrap" class="shot-wrap">
            <canvas ref="canvasRef" class="shot-canvas" @click="onCanvasClick" />
            <div v-if="!hasShot" class="shot-empty">暂无截图（设备在线后点「刷新」抓取）</div>
          </div>
          <div class="muted" style="margin-top: 6px">点击截图可反查对应控件节点</div>
        </el-card>
      </el-col>
      <el-col :span="13">
        <el-card shadow="never" class="debug-card">
          <template #header>
            <div class="card-head">
              <span>控件树</span>
              <span class="muted">{{ treeInfo }}</span>
            </div>
          </template>
          <el-input v-model="filterText" placeholder="过滤 text / id / desc" clearable size="small" style="margin-bottom: 8px" />
          <div class="tree-scroll">
            <el-tree
              ref="treeRef"
              :data="treeData"
              node-key="key"
              highlight-current
              :expand-on-click-node="false"
              :filter-node-method="filterNode"
              @current-change="onTreeCurrent"
            />
          </div>
          <el-descriptions v-if="selected" :column="1" size="small" border style="margin-top: 8px">
            <el-descriptions-item label="text">{{ selected.text || '-' }}</el-descriptions-item>
            <el-descriptions-item label="id">
              <span class="mono">{{ selected.id || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="desc">{{ selected.desc || '-' }}</el-descriptions-item>
            <el-descriptions-item label="className">
              <span class="mono">{{ selected.className || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="rect">
              x={{ selected.rect?.x }} y={{ selected.rect?.y }} w={{ selected.rect?.w }} h={{ selected.rect?.h }}
            </el-descriptions-item>
            <el-descriptions-item label="属性">
              <el-tag v-if="selected.clickable" size="small">clickable</el-tag>
              <el-tag v-if="selected.scrollable" size="small" type="warning">scrollable</el-tag>
              <el-tag v-if="selected.enabled === false" size="small" type="danger">disabled</el-tag>
              <span v-if="!selected.clickable && !selected.scrollable && selected.enabled !== false">-</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back, Refresh } from '@element-plus/icons-vue'
import {
  deviceOptions, deviceDebugTrigger, deviceDebugLatest,
  type UiTreeNode, type DebugLatest, type DumpData, type CaptureData
} from '../api'
import { connectStomp, subscribe, disconnectStomp } from '../ws/stomp'

interface NormNode extends UiTreeNode {
  key: string
  label: string
  children?: NormNode[]
}

const route = useRoute()
const router = useRouter()
const deviceId = Number(route.params.id)

const device = ref<any>(null)
const treeData = ref<NormNode[]>([])
const selected = ref<UiTreeNode | null>(null)
const filterText = ref('')
const refreshing = ref(false)
const autoRefresh = ref(false)
const hasShot = ref(false)

// 截图状态：img 为解码后的 HTMLImageElement，screenW/H 为原始屏幕尺寸（overlay 坐标系）
let img: HTMLImageElement | null = null
let screenW = 0
let screenH = 0
let dumpAt = 0
let captureAt = 0
let nodeCount = 0

const treeRef = ref<any>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const shotWrap = ref<HTMLElement | null>(null)

let sub: any = null
let timer: any = null

const fmtTime = (ts: number) => (ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '')
const shotInfo = computed(() => (hasShot.value ? `${screenW}x${screenH} · ${fmtTime(captureAt)}` : ''))
const treeInfo = computed(() => (nodeCount ? `${nodeCount} 节点 · ${fmtTime(dumpAt)}` : ''))

const goBack = () => router.push('/devices')

/* ---------------- 树数据 ---------------- */

function labelOf(n: UiTreeNode): string {
  const cls = (n.className || '?').split('.').pop()
  const hint = n.text ? `"${String(n.text).slice(0, 20)}"`
    : n.desc ? `"${String(n.desc).slice(0, 20)}"`
    : n.id ? `#${n.id.split('/').pop()}` : ''
  return hint ? `${cls} ${hint}` : `${cls}`
}

function normalize(roots: UiTreeNode[]): NormNode[] {
  const walk = (n: UiTreeNode, key: string): NormNode => {
    const children = (n.children || []).map((c, i) => walk(c, `${key}-${i}`))
    return { ...n, key, label: labelOf(n), children: children.length ? children : undefined }
  }
  return roots.map((r, i) => walk(r, `${i}`))
}

function applyDump(data: DumpData, ts: number) {
  nodeCount = data.tree?.nodeCount || 0
  dumpAt = ts
  treeData.value = normalize(data.tree?.roots || [])
  selected.value = null
}

/* ---------------- 截图与叠加 ---------------- */

function applyCapture(data: CaptureData, ts: number) {
  captureAt = ts
  screenW = data.width
  screenH = data.height
  const image = new Image()
  image.onload = () => {
    img = image
    hasShot.value = true
    draw()
  }
  image.src = `data:image/jpeg;base64,${data.image}`
}

function draw() {
  const canvas = canvasRef.value
  const wrap = shotWrap.value
  if (!canvas || !wrap || !img || !screenW || !screenH) return
  const w = wrap.clientWidth
  const h = Math.round((w * screenH) / screenW)
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
  }
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, w, h)
  ctx.drawImage(img, 0, 0, w, h)
  const sel = selected.value
  if (sel?.rect && sel.rect.w > 0 && sel.rect.h > 0) {
    const scale = w / screenW
    ctx.strokeStyle = '#409eff'
    ctx.lineWidth = 2
    ctx.strokeRect(sel.rect.x * scale, sel.rect.y * scale, sel.rect.w * scale, sel.rect.h * scale)
    ctx.fillStyle = 'rgba(64, 158, 255, 0.18)'
    ctx.fillRect(sel.rect.x * scale, sel.rect.y * scale, sel.rect.w * scale, sel.rect.h * scale)
  }
}

/** 点击截图反查：取包含该点且面积最小的可见节点（越深越精确） */
function onCanvasClick(e: MouseEvent) {
  const canvas = canvasRef.value
  if (!canvas || !screenW) return
  const rect = canvas.getBoundingClientRect()
  const sx = ((e.clientX - rect.left) / rect.width) * screenW
  const sy = ((e.clientY - rect.top) / rect.height) * screenH
  let best: NormNode | null = null
  let bestArea = Number.MAX_VALUE
  const visit = (nodes: NormNode[]) => {
    for (const n of nodes) {
      const r = n.rect
      if (r && r.w > 0 && r.h > 0 && n.visibleToUser !== false
        && sx >= r.x && sx <= r.x + r.w && sy >= r.y && sy <= r.y + r.h) {
        const area = r.w * r.h
        if (area <= bestArea) {
          best = n
          bestArea = area
        }
      }
      if (n.children) visit(n.children)
    }
  }
  visit(treeData.value)
  if (best) {
    // @ts-ignore best 已在闭包内赋值
    selectNode(best as NormNode)
  }
}

function selectNode(n: NormNode) {
  selected.value = n
  treeRef.value?.setCurrentKey(n.key)
  draw()
  // 选中节点滚进可视区，长列表里点截图也能立刻看到对应树节点
  nextTick(() => {
    document.querySelector('.tree-scroll .el-tree .is-current')
      ?.scrollIntoView({ block: 'nearest' })
  })
}

function onTreeCurrent(data: any) {
  selected.value = data
  draw()
}

/* ---------------- 过滤 ---------------- */

watch(filterText, v => treeRef.value?.filter(v))

function filterNode(value: string, data: any) {
  if (!value) return true
  const v = value.toLowerCase()
  const n: UiTreeNode = data
  return (n.text || '').toLowerCase().includes(v)
    || (n.id || '').toLowerCase().includes(v)
    || (n.desc || '').toLowerCase().includes(v)
}

/* ---------------- 拉取与实时推送 ---------------- */

function applyDebugMsg(body: any) {
  // 服务端推送 {type:'dump'|'capture', ts, data}
  if (body?.type === 'dump' && body.data?.tree) applyDump(body.data as DumpData, body.ts || Date.now())
  if (body?.type === 'capture' && body.data?.image) applyCapture(body.data as CaptureData, body.ts || Date.now())
}

async function loadLatest() {
  // 进页先取最近一份缓存（设备可能刚好离线），再触发新抓取
  const [dump, capture] = await Promise.all([
    deviceDebugLatest(deviceId, 'dump').catch(() => null),
    deviceDebugLatest(deviceId, 'capture').catch(() => null)
  ])
  if (dump?.data?.tree) applyDump(dump.data as DumpData, (dump as DebugLatest).ts)
  if (capture?.data?.image) applyCapture(capture.data as CaptureData, (capture as DebugLatest).ts)
}

async function refresh() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([
      deviceDebugTrigger(deviceId, 'dump'),
      deviceDebugTrigger(deviceId, 'capture')
    ])
  } catch {
    // 拦截器已弹错（设备离线等），这里只负责复位状态
  } finally {
    refreshing.value = false
  }
}

watch(autoRefresh, on => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (on) {
    timer = setInterval(() => {
      if (!document.hidden) refresh()
    }, 5000)
  }
})

const onResize = () => draw()

onMounted(async () => {
  connectStomp()
  sub = subscribe(`/topic/device/${deviceId}/debug`, applyDebugMsg)
  window.addEventListener('resize', onResize)
  deviceOptions().then(list => {
    device.value = (list || []).find((d: any) => d.id === deviceId) || null
  })
  await loadLatest()
  refresh()
})

onUnmounted(() => {
  if (sub) {
    sub.unsubscribe()
    sub = null
  }
  if (timer) clearInterval(timer)
  window.removeEventListener('resize', onResize)
  disconnectStomp()
})
</script>

<style scoped>
.dev-title {
  font-size: 15px;
  font-weight: 600;
}
.debug-card :deep(.el-card__body) {
  padding: 12px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.shot-wrap {
  position: relative;
  width: 100%;
}
.shot-canvas {
  display: block;
  width: 100%;
  cursor: crosshair;
  border-radius: 4px;
  background: #000;
}
.shot-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  min-height: 200px;
}
.tree-scroll {
  max-height: 480px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 4px;
}
.mono {
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
