<template>
  <div>
    <div class="page-toolbar">
      <el-form-item>
        <el-input v-model="query.keyword" placeholder="搜索设备编号/名称" clearable style="width: 220px" :prefix-icon="Search" @keyup.enter="search" />
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.groupId" placeholder="全部分组" clearable style="width: 150px" @change="search">
          <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.online" placeholder="全部状态" clearable style="width: 120px" @change="search">
          <el-option label="在线" :value="1" />
          <el-option label="离线" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      </el-form-item>
      <div class="toolbar-spacer" />
      <el-form-item>
        <el-button type="primary" plain :icon="Plus" @click="openCreate">添加设备</el-button>
      </el-form-item>
    </div>

    <el-card class="table-card" shadow="never">
      <el-table :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="deviceSn" label="设备编号" width="140" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="groupName" label="分组" width="110">
        <template #default="{ row }">{{ row.groupName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="model" label="机型" width="130">
        <template #default="{ row }">{{ row.model || '-' }}</template>
      </el-table-column>
      <el-table-column prop="androidVersion" label="系统" width="80">
        <template #default="{ row }">{{ row.androidVersion || '-' }}</template>
      </el-table-column>
      <el-table-column label="在线" width="90">
        <template #default="{ row }">
          <span v-if="row.online === 1" class="dot is-on" />
          <span v-else class="dot is-off" />
          {{ row.online === 1 ? '在线' : '离线' }}
        </template>
      </el-table-column>
      <el-table-column prop="lastActiveAt" label="最后活跃" width="170">
        <template #default="{ row }">{{ fmt(row.lastActiveAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openCmd(row)">指令</el-button>
          <el-button size="small" @click="doReset(row)">重置密钥</el-button>
          <el-button size="small" type="danger" @click="doDelete(row)">删除</el-button>
        </template>
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

    <el-dialog v-model="dlg" title="添加设备" width="460">
      <el-form label-width="90px">
        <el-form-item label="设备编号">
          <el-input v-model="form.deviceSn" placeholder="如 SN-001（手机端需填写相同编号）" />
        </el-form-item>
        <el-form-item label="设备名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="分组">
          <el-select v-model="form.groupId" clearable placeholder="不分组" style="width: 100%">
            <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cmdDlg" title="下发指令" width="460">
      <el-form label-width="80px">
        <el-form-item label="任务">
          <el-select v-model="cmdForm.taskId" style="width: 100%">
            <el-option v-for="t in tasks" :key="t.id" :label="`${t.name} (#${t.id})`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作">
          <el-radio-group v-model="cmdForm.action">
            <el-radio-button value="start">启动</el-radio-button>
            <el-radio-button value="pause">暂停</el-radio-button>
            <el-radio-button value="stop">停止</el-radio-button>
            <el-radio-button value="restart">重启</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cmdDlg = false">取消</el-button>
        <el-button type="primary" @click="doCmd">下发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { pageDevices, createDevice, deleteDevice, resetSecret, deviceCommand, listGroups, listTasks } from '../api'

const query = reactive({ keyword: '', groupId: undefined as any, online: undefined as any, page: 1, size: 10 })
const rows = ref<any[]>([])
const total = ref(0)
const groups = ref<any[]>([])
const tasks = ref<any[]>([])
const dlg = ref(false)
const form = reactive({ deviceSn: '', name: '', groupId: undefined as any })
const cmdDlg = ref(false)
const cmdForm = reactive({ deviceId: 0, taskId: undefined as any, action: 'start' })
const submitting = ref(false)

const fmt = (t: string) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

let pageReqId = 0
const load = async () => {
  const reqId = ++pageReqId
  const data: any = await pageDevices(query)
  if (reqId !== pageReqId) return // 丢弃过期响应，防止翻页/搜索时旧数据覆盖
  rows.value = data.list || []
  total.value = data.total || 0
}

// 搜索/筛选入口：新条件必须回到第 1 页
const search = () => {
  query.page = 1
  load()
}

const openCreate = () => {
  form.deviceSn = ''
  form.name = ''
  form.groupId = undefined
  dlg.value = true
}

const doCreate = async () => {
  if (!form.deviceSn.trim()) return ElMessage.warning('请填写设备编号')
  if (submitting.value) return
  submitting.value = true
  let dev: any
  try {
    dev = await createDevice(form)
  } finally {
    submitting.value = false
  }
  dlg.value = false
  // 一次性密钥先展示再做任何可能失败的请求
  try {
    await ElMessageBox.alert(
      `设备创建成功。设备编号: ${dev.deviceSn}，密钥: ${dev.secret}（仅显示一次，请妥善保存）`,
      '设备密钥',
      { confirmButtonText: '已保存' }
    )
  } catch { /* 用户关闭弹窗 */ }
  load()
}

const doReset = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认重置设备 ${row.deviceSn} 的密钥？旧连接将被断开`, '确认')
  } catch {
    return
  }
  const data: any = await resetSecret(row.id)
  try {
    await ElMessageBox.alert(`新密钥: ${data.secret}（仅显示一次）`, '重置成功')
  } catch { /* 用户关闭弹窗 */ }
}

const doDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认删除设备 ${row.deviceSn}？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteDevice(row.id)
  ElMessage.success('已删除')
  // 删除的是当前页最后一条时回退一页，避免停留在超界空页
  if (rows.value.length === 1 && query.page > 1) query.page--
  load()
}

const openCmd = (row: any) => {
  cmdForm.deviceId = row.id
  cmdDlg.value = true
}

const doCmd = async () => {
  if (!cmdForm.taskId) return ElMessage.warning('请选择任务')
  await deviceCommand(cmdForm.deviceId, { taskId: cmdForm.taskId, action: cmdForm.action })
  ElMessage.success('指令已下发')
  cmdDlg.value = false
}

onMounted(async () => {
  await load()
  groups.value = await listGroups()
  tasks.value = await listTasks()
})
</script>
