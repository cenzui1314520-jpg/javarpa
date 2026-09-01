<template>
  <div>
    <div class="page-toolbar">
      <span style="font-weight: 600; color: #1e2438">脚本库</span>
      <span style="color: #9aa3b8; font-size: 13px">zip 包根目录需包含 main.js 与 config.json</span>
      <div class="toolbar-spacer" />
      <el-button type="primary" plain :icon="Plus" @click="dlg = true">新建脚本</el-button>
    </div>
    <el-card class="table-card" shadow="never">
      <el-table :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="脚本名" min-width="140" />
      <el-table-column prop="pkgName" label="包名" min-width="140" />
      <el-table-column label="稳定版本" width="100">
        <template #default="{ row }">{{ row.stableVersionCode > 0 ? 'v' + row.stableVersionCode : '未发布' }}</template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160">
        <template #default="{ row }">{{ row.description || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="$router.push(`/scripts/${row.id}`)">版本与发布</el-button>
          <el-button size="small" type="danger" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>

    <el-dialog v-model="dlg" title="新建脚本" width="460">
      <el-form label-width="70px">
        <el-form-item label="脚本名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="包名">
          <el-input v-model="form.pkgName" placeholder="字母数字._- 如 demo.hello" />
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listScripts, createScript, deleteScript } from '../api'
import { useRouter } from 'vue-router'

const router = useRouter()
const rows = ref<any[]>([])
const dlg = ref(false)
const form = reactive({ name: '', pkgName: '', description: '' })

const load = async () => (rows.value = await listScripts())

const doCreate = async () => {
  await createScript(form)
  dlg.value = false
  ElMessage.success('已创建')
  load()
}

const doDelete = async (row: any) => {
  await ElMessageBox.confirm(`确认删除脚本 ${row.name} 及其全部版本？`, '确认', { type: 'warning' })
  await deleteScript(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>
