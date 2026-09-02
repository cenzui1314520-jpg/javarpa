<template>
  <div>
    <div class="stat-grid">
      <StatCard title="设备总数" :value="summary.deviceTotal ?? '-'" sub="已注册设备" :icon="Monitor" gradient="grad-indigo" />
      <StatCard title="在线设备" :value="summary.deviceOnline ?? 0" :sub="`在线率 ${onlineRate}`" :icon="Connection" gradient="grad-cyan" />
      <StatCard title="今日执行" :value="summary.todayExecTotal ?? 0" :sub="`业务成功 ${summary.todayOpSuccess ?? 0} · 失败 ${summary.todayOpFail ?? 0}`" :icon="VideoPlay" gradient="grad-green" />
      <StatCard title="今日成功率" :value="successRate" :sub="`成功 ${summary.todaySuccess ?? 0} / 失败 ${summary.todayFailed ?? 0}`" :icon="TrendCharts" gradient="grad-orange" />
    </div>

    <el-card class="chart-card" shadow="never">
      <template #header>
        <div class="chart-head">
          <span>近 7 天执行趋势</span>
          <el-radio-group v-model="days" size="small" @change="loadTrend">
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="14">14 天</el-radio-button>
            <el-radio-button :value="30">30 天</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <div ref="chartEl" style="height: 330px" />
    </el-card>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :md="14">
        <el-card shadow="never">
          <template #header>快速开始</template>
          <el-steps direction="vertical" :active="4" class="steps">
            <el-step title="添加设备" description="设备管理 → 添加设备，将 deviceSn / 密钥填入手机端 App 并启动引擎、开启无障碍" />
            <el-step title="上传脚本" description="脚本管理 → 新建 → 上传版本 zip（根目录含 main.js 与 config.json）" />
            <el-step title="发布" description="全量 / 灰度百分比 / 指定分组发布，在线设备自动热更新" />
            <el-step title="调度任务" description="任务管理 → 新建任务绑定设备 → 启动，实时查看日志与统计" />
          </el-steps>
        </el-card>
      </el-col>
      <el-col :md="10">
        <el-card shadow="never">
          <template #header>快捷入口</template>
          <div class="quick-grid">
            <div v-for="q in quicks" :key="q.path" class="quick-item" @click="$router.push(q.path)">
              <div class="quick-icon" :class="q.gradient">
                <el-icon :size="20"><component :is="q.icon" /></el-icon>
              </div>
              <span>{{ q.title }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import {
  Monitor, Connection, VideoPlay, TrendCharts, Files, List, Notebook, DataAnalysis, Grid
} from '@element-plus/icons-vue'
import StatCard from '../components/StatCard.vue'
import { statsSummary, statsTrend } from '../api'

const summary = ref<any>({})
const trend = ref<any[]>([])
const days = ref(7)
const chartEl = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let trendReqId = 0

const quicks = [
  { path: '/scripts', title: '上传脚本', icon: Files, gradient: 'grad-indigo' },
  { path: '/tasks', title: '创建任务', icon: List, gradient: 'grad-green' },
  { path: '/devices', title: '设备管理', icon: Monitor, gradient: 'grad-cyan' },
  { path: '/groups', title: '设备分组', icon: Grid, gradient: 'grad-violet' },
  { path: '/logs', title: '运行日志', icon: Notebook, gradient: 'grad-orange' },
  { path: '/stats', title: '数据统计', icon: DataAnalysis, gradient: 'grad-rose' }
]

const onlineRate = computed(() => {
  const total = summary.value.deviceTotal || 0
  return total ? Math.round((summary.value.deviceOnline / total) * 100) + '%' : '-'
})
const successRate = computed(() =>
  summary.value.todaySuccessRate == null ? '-' : summary.value.todaySuccessRate + '%'
)

const renderChart = () => {
  if (!alive.value || !chartEl.value) return // 组件已卸载则不再渲染，防止泄漏新实例
  if (!chart) chart = echarts.init(chartEl.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['总数', '成功', '失败'], right: 0, top: 0 },
    grid: { left: 8, right: 12, top: 36, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: trend.value.map((t: any) => t.date.slice(5)),
      axisLine: { lineStyle: { color: '#e5e9f2' } },
      axisLabel: { color: '#9aa3b8' }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#eef1f7' } },
      axisLabel: { color: '#9aa3b8' }
    },
    series: [
      {
        name: '总数', type: 'line', smooth: true, symbolSize: 7,
        lineStyle: { width: 3, color: '#4f6bf5' }, itemStyle: { color: '#4f6bf5' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(79,107,245,0.25)' },
            { offset: 1, color: 'rgba(79,107,245,0)' }
          ])
        },
        data: trend.value.map((t: any) => t.total)
      },
      {
        name: '成功', type: 'line', smooth: true, symbolSize: 7,
        lineStyle: { width: 3, color: '#10b981' }, itemStyle: { color: '#10b981' },
        data: trend.value.map((t: any) => t.success)
      },
      {
        name: '失败', type: 'line', smooth: true, symbolSize: 7,
        lineStyle: { width: 3, color: '#ef4444' }, itemStyle: { color: '#ef4444' },
        data: trend.value.map((t: any) => t.failed)
      }
    ]
  })
}

const loadTrend = async () => {
  const reqId = ++trendReqId
  const data = await statsTrend(days.value)
  if (reqId !== trendReqId) return // 丢弃过期响应，防止快速切换 7/14/30 天时旧数据覆盖
  trend.value = data
  renderChart()
}

const onResize = () => chart?.resize()

onMounted(() => {
  // 同步注册监听，避免 await 期间切走页面导致监听器泄漏
  window.addEventListener('resize', onResize)
  ;(async () => {
    summary.value = await statsSummary()
    if (!alive.value) return
    await loadTrend()
  })().catch(() => {})
})

const alive = ref(true)

onBeforeUnmount(() => {
  alive.value = false
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.chart-card { margin-top: 16px; }
.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.steps { padding: 4px 6px; }
.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px 10px;
  padding: 6px 4px;
}
.quick-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #5b6478;
  font-size: 12.5px;
  transition: color 0.2s;
}
.quick-item:hover { color: var(--rpa-primary); }
.quick-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.2s;
}
.quick-item:hover .quick-icon { transform: translateY(-2px); }
</style>
