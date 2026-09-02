<template>
  <div>
    <div class="page-toolbar">
      <span style="font-weight: 600; color: #1e2438">API Token</span>
      <span style="color: #9aa3b8; font-size: 13px">供外部系统调用 /open/v1 接口（请求头 X-API-Token）</span>
      <div class="toolbar-spacer" />
      <el-button type="primary" plain :icon="Plus" @click="dlg = true">新建 Token</el-button>
    </div>
    <el-card class="table-card" shadow="never">
      <el-table :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="prefix" label="前缀" width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastUsedAt" label="最近使用" width="180">
        <template #default="{ row }">{{ row.lastUsedAt ? String(row.lastUsedAt).replace('T', ' ').slice(0, 19) : '从未' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>

    <el-dialog v-model="dlg" title="新建 API Token" width="440">
      <el-form label-width="70px">
        <el-form-item label="名称"><el-input v-model="name" placeholder="如 订单系统" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listTokens, createToken, setTokenStatus } from '../api'

const rows = ref<any[]>([])
const dlg = ref(false)
const name = ref('')

const load = async () => (rows.value = await listTokens())

const doCreate = async () => {
  if (!name.value.trim()) return ElMessage.warning('请填写 Token 名称')
  const data: any = await createToken({ name: name.value })
  dlg.value = false
  await load()
  ElMessageBox.alert(
    `Token: ${data.token}（仅显示一次，调用开放接口时放入请求头 X-API-Token）`,
    '创建成功'
  )
}

const toggle = async (row: any) => {
  await setTokenStatus(row.id, row.status === 1 ? 0 : 1)
  load()
}

onMounted(load)
</script>
