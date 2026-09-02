<template>
  <div>
    <div class="page-toolbar">
      <span style="font-weight: 600; color: #1e2438">设备分组</span>
      <span style="color: #9aa3b8; font-size: 13px">按分组批量发布脚本与调度任务</span>
      <div class="toolbar-spacer" />
      <el-button type="primary" plain :icon="Plus" @click="openDlg()">新建分组</el-button>
    </div>
    <el-card class="table-card" shadow="never">
      <el-table :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="分组名" />
      <el-table-column prop="deviceCount" label="设备数" width="100" />
      <el-table-column prop="remark" label="备注" />
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button size="small" @click="openMembers(row)">管理设备</el-button>
          <el-button size="small" @click="openDlg(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>

    <el-dialog v-model="dlg" :title="form.id ? '编辑分组' : '新建分组'" width="420">
      <el-form label-width="70px">
        <el-form-item label="分组名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="memberDlg" :title="`分组设备: ${current?.name}`" width="560">
      <el-checkbox-group v-model="selected">
        <div v-for="d in devices" :key="d.id" style="padding: 4px 0">
          <el-checkbox :value="d.id">{{ d.deviceSn }} {{ d.name ? `(${d.name})` : '' }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="memberDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSaveMembers">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listGroups, createGroup, updateGroup, deleteGroup, setGroupMembers, deviceOptions, groupDevices } from '../api'

const rows = ref<any[]>([])
const devices = ref<any[]>([])
const dlg = ref(false)
const memberDlg = ref(false)
const current = ref<any>(null)
const selected = ref<number[]>([])
const form = reactive({ id: 0, name: '', remark: '' })
const submitting = ref(false)

const load = async () => (rows.value = await listGroups())

const openDlg = (row?: any) => {
  form.id = row?.id || 0
  form.name = row?.name || ''
  form.remark = row?.remark || ''
  dlg.value = true
}

const doSave = async () => {
  if (!form.name.trim()) return ElMessage.warning('请填写分组名')
  if (submitting.value) return
  submitting.value = true
  try {
    if (form.id) await updateGroup(form.id, form)
    else await createGroup(form)
    dlg.value = false
    ElMessage.success('已保存')
    load()
  } finally {
    submitting.value = false
  }
}

const doDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认删除分组 ${row.name}？组内设备不会被删除`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteGroup(row.id)
  ElMessage.success('已删除')
  load()
}

const openMembers = async (row: any) => {
  current.value = row
  devices.value = await deviceOptions()
  const inGroup: any = await groupDevices(row.id)
  selected.value = inGroup.map((d: any) => d.id)
  memberDlg.value = true
}

const doSaveMembers = async () => {
  if (submitting.value) return
  submitting.value = true
  try {
    await setGroupMembers(current.value.id, selected.value)
    memberDlg.value = false
    ElMessage.success('已保存')
    load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>
