<template>
  <div v-if="script">
    <el-page-header @back="$router.back()" :content="`${script.name} (${script.pkgName})`" style="margin-bottom: 16px" />

    <el-card>
      <template #header>
        版本列表
        <span style="float: right">
          <el-button type="success" size="small" @click="openEditorNew">在线编写</el-button>
          <el-button type="primary" size="small" style="margin-left: 8px" @click="uploadDlg = true">上传新版本</el-button>
        </span>
      </template>
      <el-table :data="versions" stripe>
        <el-table-column label="版本号" width="90">
          <template #default="{ row }">
            v{{ row.versionCode }}
            <el-tag v-if="row.versionCode === script.stableVersionCode" size="small" type="success">稳定</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="versionName" label="版本名" width="110" />
        <el-table-column prop="fileSha256" label="SHA-256" min-width="300" show-overflow-tooltip />
        <el-table-column prop="fileSize" label="大小" width="90">
          <template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }}K</template>
        </el-table-column>
        <el-table-column prop="changelog" label="变更说明" min-width="160">
          <template #default="{ row }">{{ row.changelog || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270">
          <template #default="{ row }">
            <el-button size="small" type="success" plain @click="openEditorVersion(row)">编辑</el-button>
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
        <el-button type="primary" :loading="pubSubmitting" @click="doPublish">发布</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="editorDlg" :title="editorTitle" size="72%" :close-on-click-modal="false">
      <el-form inline>
        <el-form-item label="保存为版本">
          <el-input-number v-model="editorForm.versionCode" :min="1" size="small" />
        </el-form-item>
        <el-form-item label="版本名">
          <el-input v-model="editorForm.versionName" placeholder="如 1.0.1" size="small" style="width: 120px" />
        </el-form-item>
        <el-form-item label="变更说明">
          <el-input v-model="editorForm.changelog" placeholder="本次改动" size="small" style="width: 220px" />
        </el-form-item>
      </el-form>
      <div style="margin-bottom: 8px">
        <el-button size="small" @click="addFile">新增文件</el-button>
        <el-button size="small" type="danger" plain :disabled="!canDeleteActive" @click="removeActiveFile">
          删除当前文件
        </el-button>
        <span v-if="editorBase > 0" style="color: #909399; font-size: 12px; margin-left: 8px">
          基于 v{{ editorBase }} 修改，保存后将生成新 zip 版本
        </span>
      </div>
      <el-tabs v-model="activeFile" type="card" closable @tab-remove="tryRemoveFile">
        <el-tab-pane v-for="f in editorFiles" :key="f.name" :name="f.name">
          <template #label>
            <span :style="f.text ? '' : 'color:#909399'">{{ f.name }}</span>
          </template>
          <div v-if="f.text">
            <textarea
              v-model="f.content"
              spellcheck="false"
              style="width: 100%; height: 55vh; box-sizing: border-box; font-family: Menlo, Consolas, monospace;
                font-size: 13px; line-height: 1.6; padding: 12px; border: 1px solid #3a3f4f; border-radius: 4px;
                background: #1e222d; color: #d7dae0; resize: vertical; tab-size: 2"
            />
          </div>
          <el-alert v-else :title="`二进制文件（${f.size} 字节），不支持在线编辑，保存时将原样保留`" type="info" :closable="false" />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="editorDlg = false">取消</el-button>
        <el-button type="primary" :loading="editorSaving" @click="saveEditor">保存为新版本</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScripts, listVersions, uploadVersion, publishScript, publishRecords, listGroups,
  getVersionFiles, uploadVersionEditor
} from '../api'

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
const pubSubmitting = ref(false)

// ---------- 在线编辑 ----------
interface EditorFile { name: string; text: boolean; size?: number; content: string }
const editorDlg = ref(false)
const editorTitle = ref('在线编辑')
const editorFiles = ref<EditorFile[]>([])
const activeFile = ref('')
const editorBase = ref(0)
const editorSaving = ref(false)
const editorForm = reactive({ versionCode: 1, versionName: '', changelog: '' })

const nextVersionCode = () => Math.max(1, (versions.value[0]?.versionCode || 0) + 1)

const openEditorVersion = async (row: any) => {
  try {
    const files: any[] = await getVersionFiles(scriptId(), row.versionCode)
    editorFiles.value = files.map(f => ({
      name: f.name, text: !!f.text, size: f.size, content: f.content || ''
    }))
    editorBase.value = row.versionCode
    editorTitle.value = `编辑 v${row.versionCode}（保存为新版本）`
    editorForm.versionCode = nextVersionCode()
    editorForm.versionName = row.versionName || ''
    editorForm.changelog = `基于 v${row.versionCode} 修改`
    activeFile.value = 'main.js'
    editorDlg.value = true
  } catch { /* 拦截器已提示 */ }
}

const openEditorNew = async () => {
  editorBase.value = 0
  if (versions.value.length > 0) {
    // 已有版本：复制最新版作为起点，二进制资源保存时自动保留
    const latest = versions.value[0]
    try {
      const files: any[] = await getVersionFiles(scriptId(), latest.versionCode)
      editorFiles.value = files.map(f => ({
        name: f.name, text: !!f.text, size: f.size, content: f.content || ''
      }))
      editorBase.value = latest.versionCode
      editorTitle.value = `在线编写（复制自 v${latest.versionCode}）`
    } catch { /* 拦截器已提示 */ }
  }
  if (!editorBase.value) {
    editorFiles.value = [
      { name: 'main.js', text: true, content: '// JavaRPA 在线编写\nlog("hello, task params:", JSON.stringify(params));\n' },
      { name: 'config.json', text: true, content: JSON.stringify({ name: script.value?.name || 'script', version: '1.0.0', entry: 'main.js' }, null, 2) }
    ]
    editorTitle.value = '在线编写新脚本'
  }
  editorForm.versionCode = nextVersionCode()
  editorForm.versionName = ''
  editorForm.changelog = ''
  activeFile.value = 'main.js'
  editorDlg.value = true
}

const addFile = async () => {
  try {
    const { value } = await ElMessageBox.prompt('文件名（资源请以 res/ 开头）', '新增文件', {
      inputPattern: /^(?!\/)[^:\\]+$/, inputErrorMessage: '文件名不合法'
    })
    const name = value.trim()
    if (!name) return
    if (editorFiles.value.some(f => f.name === name)) return ElMessage.warning('文件已存在')
    editorFiles.value.push({ name, text: true, content: '' })
    activeFile.value = name
  } catch { /* 用户取消 */ }
}

const canDeleteActive = () => {
  const f = editorFiles.value.find(x => x.name === activeFile.value)
  return !!f && f.text && f.name !== 'main.js' && f.name !== 'config.json'
}

const removeFile = (name: string) => {
  if (name === 'main.js' || name === 'config.json') return ElMessage.warning('入口与配置文件不可删除')
  const f = editorFiles.value.find(x => x.name === name)
  if (f && !f.text) return ElMessage.warning('二进制文件由基准版本保留，不支持在此删除')
  editorFiles.value = editorFiles.value.filter(x => x.name !== name)
  if (activeFile.value === name) activeFile.value = editorFiles.value[0]?.name || ''
}

const removeActiveFile = () => removeFile(activeFile.value)
const tryRemoveFile = (name: any) => removeFile(String(name))

const saveEditor = async () => {
  if (editorSaving.value) return
  const files = editorFiles.value.filter(f => f.text).map(f => ({ name: f.name, content: f.content }))
  editorSaving.value = true
  try {
    await uploadVersionEditor(scriptId(), {
      versionCode: editorForm.versionCode,
      versionName: editorForm.versionName || undefined,
      changelog: editorForm.changelog || undefined,
      baseVersionCode: editorBase.value || undefined,
      files
    })
    editorDlg.value = false
    ElMessage.success(`已保存为 v${editorForm.versionCode}，可在版本列表发布`)
    await load()
  } catch { /* 拦截器已提示 */ } finally {
    editorSaving.value = false
  }
}

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
  if (pubSubmitting.value) return
  pubSubmitting.value = true
  try {
    await publishScript(scriptId(), { versionCode: pubForm.versionCode, targetType: pubForm.targetType, targetValue: value })
    pubDlg.value = false
    ElMessage.success('发布成功，在线设备将自动更新')
    load()
  } catch { /* 拦截器已提示 */ } finally {
    pubSubmitting.value = false
  }
}

const doRollback = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认将稳定版本回滚到 v${row.versionCode}？（以全量方式重新发布旧版本）`, '回滚确认')
  } catch {
    return // 用户取消
  }
  if (pubSubmitting.value) return
  pubSubmitting.value = true
  try {
    await publishScript(scriptId(), { versionCode: row.versionCode, targetType: 'ALL' })
    ElMessage.success('已回滚')
    load()
  } finally {
    pubSubmitting.value = false
  }
}

onMounted(async () => {
  await load()
  groups.value = await listGroups()
  resetUpload()
})
</script>
