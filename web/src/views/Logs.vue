<template>
  <div>
    <div class="page-toolbar">
      <el-form-item>
        <el-select v-model="query.deviceId" placeholder="全部设备" clearable filterable style="width: 220px">
          <el-option v-for="d in devices" :key="d.id" :label="`${d.deviceSn} ${d.name || ''}`" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.level" placeholder="全部级别" clearable style="width: 120px">
          <el-option label="INFO" value="INFO" />
          <el-option label="WARN" value="WARN" />
          <el-option label="ERROR" value="ERROR" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
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
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { pageLogs, pageDevices } from '../api'
import { connectStomp, subscribe, disconnectStomp } from '../ws/stomp'

const query = reactive({ deviceId: undefined as any, level: '', page: 1, size: 50 })
const rows = ref<any[]>([])
const total = ref(0)
const devices = ref<any[]>([])
const live = ref(false)
let sub: any = null

const fmt = (t: string) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

const load = async () => {
  const data: any = await pageLogs(query)
  rows.value = data.list
  total.value = data.total
}

const toggleLive = async (on: boolean) => {
  if (sub) {
    sub.unsubscribe()
    sub = null
  }
  if (on && query.deviceId) {
    connectStomp(() => {
      sub = subscribe(`/topic/device/${query.deviceId}/logs`, (body: any) => {
        rows.value.unshift({
          id: Date.now(), deviceId: body.deviceId, taskId: body.taskId,
          level: body.level || 'INFO', content: body.content, logTime: new Date().toISOString()
        })
        if (rows.value.length > 300) rows.value.pop()
      })
    })
  } else if (on) {
    rows.value.length && rows.value.unshift({ id: -1, content: '请先选择设备再开启实时日志', level: 'WARN', deviceId: '-', logTime: new Date().toISOString() })
    live.value = false
  }
}

import { watch } from 'vue'
watch(live, toggleLive)

onMounted(async () => {
  await load()
  const page: any = await pageDevices({ page: 1, size: 500 })
  devices.value = page.list
})

onUnmounted(() => {
  if (sub) sub.unsubscribe()
  disconnectStomp()
})
</script>
