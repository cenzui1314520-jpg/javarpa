<template>
  <div>
    <div class="page-toolbar">
      <span style="font-weight: 600; color: #1e2438">任务统计</span>
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 260px"
      />
      <el-button type="primary" @click="load">查询</el-button>
      <span style="color: #9aa3b8; font-size: 12px">默认最近 30 天</span>
    </div>

    <div class="stat-grid">
      <StatCard title="执行总次数" :value="totals.total" sub="区间内任务执行" :icon="VideoPlay" gradient="grad-indigo" />
      <StatCard title="成功次数" :value="totals.success" sub="任务级成功" :icon="CircleCheck" gradient="grad-green" />
      <StatCard title="失败次数" :value="totals.failed" sub="任务级失败" :icon="CircleClose" gradient="grad-rose" />
      <StatCard title="综合成功率" :value="totals.rate" :sub="`业务成功 ${totals.opSuccess} · 业务失败 ${totals.opFail}`" :icon="TrendCharts" gradient="grad-orange" />
    </div>

    <el-card class="table-card" shadow="never">
      <el-table :data="rows" stripe>
      <el-table-column prop="taskId" label="任务ID" width="80" />
      <el-table-column prop="taskName" label="任务名" min-width="140" />
      <el-table-column prop="scriptName" label="脚本" min-width="120">
        <template #default="{ row }">{{ row.scriptName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="total" label="执行次数" width="100" />
      <el-table-column prop="success" label="成功次数" width="100" />
      <el-table-column prop="failed" label="失败次数" width="100" />
      <el-table-column label="成功率" width="100">
        <template #default="{ row }">{{ row.successRate == null ? '-' : row.successRate + '%' }}</template>
      </el-table-column>
      <el-table-column prop="opSuccess" label="业务成功数" width="110" />
      <el-table-column prop="opFail" label="业务失败数" width="110" />
    </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { VideoPlay, CircleCheck, CircleClose, TrendCharts } from '@element-plus/icons-vue'
import StatCard from '../components/StatCard.vue'
import { statsByTask } from '../api'

const range = ref<string[]>([])
const rows = ref<any[]>([])

const totals = computed(() => {
  const t = rows.value.reduce((a, r) => ({
    total: a.total + r.total, success: a.success + r.success, failed: a.failed + r.failed,
    opSuccess: a.opSuccess + r.opSuccess, opFail: a.opFail + r.opFail
  }), { total: 0, success: 0, failed: 0, opSuccess: 0, opFail: 0 })
  return {
    ...t,
    rate: t.total ? Math.round((t.success / t.total) * 1000) / 10 + '%' : '-'
  }
})

const load = async () => {
  const end = new Date()
  const start = new Date(Date.now() - 29 * 86400_000)
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  const s = range.value?.[0] || fmt(start)
  const e = range.value?.[1] || fmt(end)
  rows.value = await statsByTask(s, e)
}

onMounted(load)
</script>
