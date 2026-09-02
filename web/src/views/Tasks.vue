<template>
  <div>
    <div class="page-toolbar">
      <span style="font-weight: 600; color: #1e2438">任务列表</span>
      <span style="color: #9aa3b8; font-size: 13px">支持手动/CRON 调度与失败自动重试</span>
      <div class="toolbar-spacer" />
      <el-button type="primary" plain :icon="Plus" @click="openDlg()">新建任务</el-button>
    </div>
    <el-card class="table-card" shadow="never">
      <el-table :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="任务名" min-width="130" />
      <el-table-column prop="scriptName" label="脚本" min-width="120" />
      <el-table-column label="版本" width="80">
        <template #default="{ row }">{{ row.versionCode ? 'v' + row.versionCode : '稳定版' }}</template>
      </el-table-column>
      <el-table-column label="调度" width="170">
        <template #default="{ row }">
          {{ row.scheduleType === 'CRON' ? `定时 ${row.cronExpr}` : '手动' }}
        </template>
      </el-table-column>
      <el-table-column prop="deviceCount" label="设备数" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="380" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="act(row, 'start')">启动</el-button>
          <el-button size="small" @click="act(row, 'pause')">暂停</el-button>
          <el-button size="small" @click="act(row, 'stop')">停止</el-button>
          <el-button size="small" @click="act(row, 'restart')">重启</el-button>
          <el-button size="small" @click="$router.push(`/tasks/${row.id}`)">详情</el-button>
          <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'" @click="act(row, row.status === 1 ? 'disable' : 'enable')">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>

    <el-dialog v-model="dlg" :title="form.id ? '编辑任务' : '新建任务'" width="620">
      <el-form label-width="100px">
        <el-form-item label="任务名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="脚本">
          <el-select v-model="form.scriptId" style="width: 100%" @change="onScriptChange">
            <el-option v-for="s in scripts" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-select v-model="form.versionCode" clearable placeholder="跟随稳定版本" style="width: 100%">
            <el-option v-for="v in versions" :key="v.versionCode" :label="`v${v.versionCode} ${v.versionName || ''}`" :value="v.versionCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行设备">
          <el-select v-model="form.deviceIds" multiple filterable style="width: 100%" placeholder="选择设备">
            <el-option v-for="d in devices" :key="d.id" :label="`${d.deviceSn} ${d.name || ''}`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="调度方式">
          <el-radio-group v-model="form.scheduleType">
            <el-radio value="IMMEDIATE">手动/接口触发</el-radio>
            <el-radio value="CRON">定时 CRON</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scheduleType === 'CRON'" label="CRON 表达式">
          <el-input v-model="form.cronExpr" placeholder="如 0 0 9 * * ? 表示每天 9 点" />
        </el-form-item>
        <el-form-item label="失败重试">
          <el-input-number v-model="form.maxRetries" :min="0" :max="10" />
        </el-form-item>
        <el-form-item label="脚本参数">
          <el-input v-model="form.paramsJson" type="textarea" :rows="3" placeholder='JSON 对象，如 {"count": 10}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listTasks, createTask, updateTask, deleteTask, taskAction, taskDetail, listScripts, listVersions, pageDevices } from '../api'

const rows = ref<any[]>([])
const scripts = ref<any[]>([])
const versions = ref<any[]>([])
const devices = ref<any[]>([])
const dlg = ref(false)
const form = reactive({
  id: 0, name: '', scriptId: undefined as any, versionCode: undefined as any,
  deviceIds: [] as number[], scheduleType: 'IMMEDIATE', cronExpr: '', maxRetries: 0, paramsJson: ''
})

const load = async () => (rows.value = await listTasks())

const loadVersions = async () => {
  if (form.scriptId) versions.value = await listVersions(form.scriptId)
  else versions.value = []
}

// 切换脚本必须同步刷新版本列表，否则会把 A 的版本提交给 B
const onScriptChange = async () => {
  form.versionCode = undefined
  await loadVersions()
}

const openDlg = async (row?: any) => {
  form.id = row?.id || 0
  form.name = row?.name || ''
  form.scriptId = row?.scriptId
  form.versionCode = row?.versionCode ?? undefined
  form.scheduleType = row?.scheduleType || 'IMMEDIATE'
  form.cronExpr = row?.cronExpr || ''
  form.maxRetries = row?.maxRetries ?? 0
  form.paramsJson = row?.paramsJson || ''
  form.deviceIds = []
  if (row?.id) {
    // 编辑场景回填任务已绑定的设备
    try {
      const d: any = await taskDetail(row.id)
      form.deviceIds = (d.taskDevices || []).map((td: any) => td.deviceId)
    } catch { /* 详情加载失败不阻塞编辑 */ }
  }
  dlg.value = true
  await loadVersions()
}

const doSave = async () => {
  if (!form.name.trim()) return ElMessage.warning('请填写任务名')
  if (!form.scriptId) return ElMessage.warning('请选择脚本')
  if (form.deviceIds.length === 0) return ElMessage.warning('请选择执行设备')
  if (form.scheduleType === 'CRON' && !form.cronExpr.trim()) return ElMessage.warning('请填写 CRON 表达式')
  const payload = {
    name: form.name, scriptId: form.scriptId, versionCode: form.versionCode || null,
    deviceIds: form.deviceIds, scheduleType: form.scheduleType, cronExpr: form.cronExpr,
    maxRetries: form.maxRetries, paramsJson: form.paramsJson || null
  }
  if (form.id) await updateTask(form.id, payload)
  else await createTask(payload)
  dlg.value = false
  ElMessage.success('已保存')
  load()
}

const act = async (row: any, action: string) => {
  if (action === 'stop' || action === 'restart') {
    try {
      await ElMessageBox.confirm(`确认对任务「${row.name}」执行${action === 'stop' ? '停止' : '重启'}？`, '确认', { type: 'warning' })
    } catch {
      return
    }
  }
  await taskAction(row.id, action)
  ElMessage.success('操作已下发')
  setTimeout(load, 500)
}

onMounted(async () => {
  await load()
  scripts.value = await listScripts()
  const page: any = await pageDevices({ page: 1, size: 500 })
  devices.value = page.list
})
</script>
