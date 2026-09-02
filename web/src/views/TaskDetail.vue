<template>
  <div v-if="detail">
    <el-page-header @back="$router.back()" :content="`任务详情: ${detail.task.name}`" style="margin-bottom: 16px" />

    <el-card>
      <template #header>
        设备执行状态
        <el-button size="small" type="primary" style="float: right" @click="act('start')">全部启动</el-button>
      </template>
      <el-table :data="detail.taskDevices" stripe>
        <el-table-column prop="deviceSn" label="设备" width="140" />
        <el-table-column prop="deviceName" label="名称" min-width="110" />
        <el-table-column label="在线" width="80">
          <template #default="{ row }">
            <el-tag :type="row.online === 1 ? 'success' : 'info'" size="small">{{ row.online === 1 ? '在线' : '离线' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="successCount" label="成功数" width="90" />
        <el-table-column prop="failCount" label="失败数" width="90" />
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column prop="lastRunAt" label="最近执行" width="170">
          <template #default="{ row }">{{ fmt(row.lastRunAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" @click="deviceCmd(row.deviceId, 'start')">启动</el-button>
            <el-button size="small" @click="deviceCmd(row.deviceId, 'stop')">停止</el-button>
            <el-button size="small" @click="deviceCmd(row.deviceId, 'restart')">重启</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>最近执行记录</template>
      <el-table :data="detail.executions" stripe size="small">
        <el-table-column prop="deviceId" label="设备ID" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="successCount" label="成功" width="80" />
        <el-table-column prop="failCount" label="失败" width="80" />
        <el-table-column label="耗时" width="110">
          <template #default="{ row }">{{ row.durationMs == null ? '-' : (row.durationMs / 1000).toFixed(1) + 's' }}</template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误信息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { taskDetail, taskAction, deviceCommand } from '../api'
import { connectStomp, subscribe, disconnectStomp } from '../ws/stomp'

const route = useRoute()
const taskId = () => Number(route.params.id)
const detail = ref<any>(null)
let sub: any = null
let debounceTimer: ReturnType<typeof setTimeout> | null = null

const fmt = (t: string) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')
const statusType = (s: string) =>
  s === 'RUNNING' ? 'primary' : s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'danger' : s === 'PAUSED' ? 'warning' : 'info'

let reqId = 0
const load = async () => {
  const id = taskId()
  const current = ++reqId
  const d = await taskDetail(id)
  // 组件复用快速切换任务时丢弃过期响应，防止旧任务数据覆盖新任务
  if (current !== reqId || id !== taskId()) return
  detail.value = d
}

const loadDebounced = () => {
  // 多设备高频推送时避免请求风暴
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(load, 500)
}

const act = async (action: string) => {
  await taskAction(taskId(), action)
  ElMessage.success('操作已下发')
}

const deviceCmd = async (deviceId: number, action: string) => {
  await deviceCommand(deviceId, { taskId: taskId(), action })
  ElMessage.success('指令已下发')
}

onMounted(() => {
  load()
  connectStomp(() => {
    sub = subscribe(`/topic/task/${taskId()}/status`, loadDebounced)
  })
})

// 路由参数变化时（前进/后退）组件被复用，重新加载并重订 topic
watch(() => route.params.id, (newId, oldId) => {
  if (!route.path.startsWith('/tasks/') || newId === oldId) return
  if (sub) {
    sub.unsubscribe()
    sub = null
  }
  load()
  sub = subscribe(`/topic/task/${taskId()}/status`, loadDebounced)
})

onUnmounted(() => {
  if (sub) sub.unsubscribe()
  if (debounceTimer) clearTimeout(debounceTimer)
  disconnectStomp()
})
</script>
