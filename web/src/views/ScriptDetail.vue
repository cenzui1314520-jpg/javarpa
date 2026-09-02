<template>
  <div v-if="script">
    <el-page-header @back="$router.back()" :content="`${script.name} (${script.pkgName})`" style="margin-bottom: 16px" />

    <el-card>
      <template #header>
        版本列表
        <el-button type="primary" size="small" style="float: right" @click="uploadDlg = true">上传新版本</el-button>
      </template>
      <el-table :data="versions" stripe>
        <el-table-column label="版本号" width="90">
          <template #default="{ row }">
            v{{ row.versionCode }}
            <el-tag v-if="row.versionCode === script.stableVersionCode" size="small" type="success">稳定</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="versionName" label="版本名" width="110" />
        <el-table-column prop="fileMd5" label="MD5" min-width="240" show-overflow-tooltip />
        <el-table-column prop="fileSize" label="大小" width="90">
          <template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }}K</template>
        </el-table-column>
        <el-table-column prop="changelog" label="变更说明" min-width="160">
          <template #default="{ row }">{{ row.changelog || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openPublish(row)">发布</el-button>
            <el-button size="small" v-if="row.versionCode !== script.stableVersionCode" @click="doRollback(row)">
              回滚到此版
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>发布记录</template>
      <el-table :data="records" stripe size="small">
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.versionCode }}</template>
        </el-table-column>
        <el-table-column label="方式" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ targetText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="createdAt" label="时间">
          <template #default="{ row }">{{ String(row.createdAt).replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="uploadDlg" title="上传脚本版本" width="480" @close="resetUpload">
      <el-form label-width="80px">
        <el-form-item label="版本号"><el-input-number v-model="upForm.versionCode" :min="1" /></el-form-item>
        <el-form-item label="版本名"><el-input v-model="upForm.versionName" placeholder="如 1.0.1" /></el-form-item>
        <el-form-item label="变更说明"><el-input v-model="upForm.changelog" type="textarea" /></el-form-item>
        <el-form-item label="脚本包">
          <input type="file" accept=".zip" ref="fileEl" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDlg = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="doUpload">上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pubDlg" title="发布版本" width="480">
      <el-form label-width="80px">
        <el-form-item label="版本">
          <el-tag v-if="pubForm.versionCode">v{{ pubForm.versionCode }}</el-tag>
        </el-form-item>
        <el-form-item label="发布方式">
          <el-radio-group v-model="pubForm.targetType">
            <el-radio value="ALL">全量</el-radio>
            <el-radio value="PERCENT">灰度</el-radio>
            <el-radio value="GROUP">分组</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="pubForm.targetType === 'PERCENT'" label="灰度比例">
          <el-slider v-model="pubForm.percent" :min="1" :max="100" show-input />
        </el-form-item>
        <el-form-item v-if="pubForm.targetType === 'GROUP'" label="选择分组">
          <el-select v-model="pubForm.groupId" style="width: 100%">
            <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pubDlg = false">取消</el-button>
        <el-button type="primary" @click="doPublish">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listScripts, listVersions, uploadVersion, publishScript, publishRecords, listGroups } from '../api'

const route = useRoute()
const router = useRouter()
const scriptId = () => Number(route.params.id)
const script = ref<any>(null)
const versions = ref<any[]>([])
const records = ref<any[]>([])
const groups = ref<any[]>([])
const uploadDlg = ref(false)
const pubDlg = ref(false)
const uploading = ref(false)
const fileEl = ref<HTMLInputElement>()
const upForm = reactive({ versionCode: 1, versionName: '', changelog: '' })
const pubForm = reactive({ versionCode: 0, targetType: 'ALL', percent: 20, groupId: undefined as any })

const load = async () => {
  const id = scriptId()
  if (!Number.isFinite(id)) return
  const all: any[] = await listScripts()
  script.value = all.find(s => s.id === id)
  if (!script.value) {
    ElMessage.error('脚本不存在或已被删除')
    router.replace('/scripts')
    return
  }
  versions.value = await listVersions(id)
  records.value = await publishRecords(id)
}

// 路由参数变化时（前进/后退）组件被复用，需重新加载
watch(() => route.params.id, () => {
  if (route.path.startsWith('/scripts/')) load()
})

const targetText = (row: any) =>
  row.targetType === 'ALL' ? '全量' : row.targetType === 'PERCENT' ? `灰度 ${row.targetValue}%` : `分组 ${row.targetValue}`

const resetUpload = () => {
  upForm.versionCode = Math.max(1, (versions.value[0]?.versionCode || 0) + 1)
  upForm.versionName = ''
  upForm.changelog = ''
  if (fileEl.value) fileEl.value.value = ''
}

const doUpload = async () => {
  const file = fileEl.value?.files?.[0]
  if (!file) return ElMessage.warning('请选择 zip 文件')
  const fd = new FormData()
  fd.append('file', file)
  fd.append('versionCode', String(upForm.versionCode))
  fd.append('versionName', upForm.versionName)
  fd.append('changelog', upForm.changelog)
  uploading.value = true
  try {
    await uploadVersion(scriptId(), fd)
    uploadDlg.value = false
    ElMessage.success('版本已上传')
    load()
  } catch {
    // 拦截器已提示，这里只恢复按钮状态
  } finally {
    uploading.value = false
  }
}

const openPublish = (row: any) => {
  pubForm.versionCode = row.versionCode
  pubDlg.value = true
}

const doPublish = async () => {
  const value =
    pubForm.targetType === 'PERCENT' ? String(pubForm.percent)
      : pubForm.targetType === 'GROUP' ? String(pubForm.groupId || '')
        : null
  if (pubForm.targetType === 'GROUP' && !value) return ElMessage.warning('请选择分组')
  try {
    await publishScript(scriptId(), { versionCode: pubForm.versionCode, targetType: pubForm.targetType, targetValue: value })
    pubDlg.value = false
    ElMessage.success('发布成功，在线设备将自动更新')
    load()
  } catch { /* 拦截器已提示 */ }
}

const doRollback = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认将稳定版本回滚到 v${row.versionCode}？（以全量方式重新发布旧版本）`, '回滚确认')
  } catch {
    return // 用户取消
  }
  await publishScript(scriptId(), { versionCode: row.versionCode, targetType: 'ALL' })
  ElMessage.success('已回滚')
  load()
}

onMounted(async () => {
  await load()
  groups.value = await listGroups()
  resetUpload()
})
</script>
