<template>
  <div>
    <div class="page-toolbar">
      <el-form-item>
        <el-select v-model="query.deviceId" placeholder="全部设备" clearable filterable style="width: 220px" :disabled="live">
          <el-option v-for="d in devices" :key="d.id" :label="`${d.deviceSn} ${d.name || ''}`" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.level" placeholder="全部级别" clearable style="width: 120px" @change="onLevelChange">
          <el-option label="INFO" value="INFO" />
          <el-option label="WARN" value="WARN" />
          <el-option label="ERROR" value="ERROR" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :disabled="live" @click="search">查询</el-button>
      </el-form-item>
      <div class="toolbar-spacer" />
      <el-form-item>
        <el-switch v-model="live" active-text="实时日志" />
      </el-form-item>
    </div>

    <el-card class="table-card" shadow="never">
      <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="deviceId" label="设备ID" width="90" />
      <el-table-column prop="taskId" label="任务ID" width="90">
        <template #default="{ row }">{{ row.taskId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag :type="row.level === 'ERROR' ? 'danger' : row.level === 'WARN' ? 'warning' : 'info'" size="small">
            {{ row.level }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
      <el-table-column prop="logTime" label="时间" width="180">
        <template #default="{ row }">{{ fmt(row.logTime) }}</template>
      </el-table-column>
    </el-table>
    </el-card>
    <div class="page-footer">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        v-model:current-page="query.page"
        :disabled="live"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageLogs, deviceOptions } from '../api'
import { connectStomp, subscribe, disconnectStomp } from '../ws/stomp'

const query = reactive({ deviceId: undefined as any, level: '', page: 1, size: 50 })
const rows = ref<any[]>([])
const total = ref(0)
const devices = ref<any[]>([])
const live = ref(false)
let sub: any = null
let pageReqId = 0

const fmt = (t: string) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

// 任何新查询条件都回到第 1 页，避免停留在超界空页误判"无数据"
const search = () => {
  query.page = 1
  load()
}

const load = async () => {
  const reqId = ++pageReqId
  const data: any = await pageLogs(query)
  if (reqId !== pageReqId) return // 丢弃过期响应，防止翻页/筛选时旧数据覆盖
  rows.value = data.list || []
  total.value = data.total || 0
}

const unsubscribe = () => {
  if (sub) {
    sub.unsubscribe()
    sub = null
  }
}

const resubscribe = () => {
  if (!live.value || !query.deviceId) return
  unsubscribe()
  sub = subscribe(`/topic/device/${query.deviceId}/logs`, (body: any) => {
    // 实时推送同样按已选级别过滤，与查询口径一致
    if (query.level && (body.level || 'INFO') !== query.level) return
    rows.value.unshift({
      id: Date.now(), deviceId: body.deviceId, taskId: body.taskId,
      level: body.level || 'INFO', content: body.content, logTime: new Date().toISOString()
    })
    if (rows.value.length > 300) rows.value.pop()
  })
}

// 实时模式下切换级别：清掉旧数据避免新旧口径混杂；非实时走正常查询
const onLevelChange = () => {
  if (live.value) {
    rows.value = []
  } else {
    search()
  }
}

watch(live, on => {
  if (on && !query.deviceId) {
    ElMessage.warning('请先选择设备再开启实时日志')
    live.value = false
    return
  }
  if (on) {
    connectStomp(resubscribe)
  } else {
    unsubscribe()
    disconnectStomp()
  }
})

watch(() => query.deviceId, () => {
  query.page = 1
  load()
  if (live.value) connectStomp(resubscribe) // 实时期间切换设备需重新订阅
})

onMounted(async () => {
  await load()
  devices.value = await deviceOptions()
})

onUnmounted(() => {
  unsubscribe()
  disconnectStomp()
})
</script>
