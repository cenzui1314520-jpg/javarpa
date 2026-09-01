<template>
  <div class="layout">
    <header class="topbar">
      <div class="topbar-left">
        <div class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon :size="16"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </div>
        <el-breadcrumb v-if="isDetail" separator="/" class="crumb">
          <el-breadcrumb-item :to="breadcrumbParent.path">{{ breadcrumbParent.title }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ $route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
        <span class="page-title">{{ $route.meta.title }}</span>
      </div>

      <el-dropdown @command="onCommand">
        <div class="user-chip">
          <el-avatar :size="30" class="user-avatar">{{ adminInitial }}</el-avatar>
          <span class="user-name">{{ adminName }}</span>
          <el-icon :size="12" color="#9aa3b8"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="password">
              <el-icon><Lock /></el-icon>修改密码
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon>退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <div class="layout-body">
      <aside :class="['aside', collapsed ? 'is-collapsed' : '']">
        <div class="brand">
          <div class="brand-logo">R</div>
          <div v-if="!collapsed" class="brand-text">
            <div class="brand-name">JavaRPA</div>
            <div class="brand-sub">云端脚本管理平台</div>
          </div>
        </div>

        <el-menu
          :default-active="$route.path"
          router
          :collapse="collapsed"
          :collapse-transition="false"
          background-color="transparent"
          text-color="#8b94b3"
          active-text-color="#ffffff"
          class="side-menu"
        >
          <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon>
            <template #title>{{ m.title }}</template>
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="main">
        <router-view />
      </main>
    </div>

    <el-dialog v-model="pwdDlg" title="修改密码" width="420">
      <el-form label-width="80px">
        <el-form-item label="原密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDlg = false">取消</el-button>
        <el-button type="primary" @click="doChangePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Odometer, Monitor, Grid, Files, List, Notebook, DataAnalysis, Key,
  User, SwitchButton, Lock, ArrowDown, Fold, Expand
} from '@element-plus/icons-vue'
import { changePassword } from '../api'
import { disconnectStomp } from '../ws/stomp'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)

const menus = [
  { path: '/dashboard', title: '仪表盘', icon: Odometer },
  { path: '/devices', title: '设备管理', icon: Monitor },
  { path: '/groups', title: '设备分组', icon: Grid },
  { path: '/scripts', title: '脚本管理', icon: Files },
  { path: '/tasks', title: '任务管理', icon: List },
  { path: '/logs', title: '运行日志', icon: Notebook },
  { path: '/stats', title: '数据统计', icon: DataAnalysis },
  { path: '/tokens', title: 'API Token', icon: Key }
]

const adminName = computed(() => {
  try {
    const admin = JSON.parse(localStorage.getItem('admin') || '{}')
    return admin.nickname || admin.username || 'admin'
  } catch {
    return 'admin'
  }
})
const adminInitial = computed(() => adminName.value.slice(0, 1).toUpperCase())

const breadcrumbParent = computed(() =>
  route.path.includes('/scripts/') ? { path: '/scripts', title: '脚本管理' } : { path: '/tasks', title: '任务管理' }
)
const isDetail = computed(() => route.path.includes('/scripts/') || route.path.includes('/tasks/'))

const pwdDlg = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '' })

const onCommand = (cmd: string) => {
  if (cmd === 'logout') {
    localStorage.removeItem('token')
    disconnectStomp()
    router.push('/login')
  } else if (cmd === 'password') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdDlg.value = true
  }
}

const doChangePassword = async () => {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) return ElMessage.warning('请填写完整')
  await changePassword(pwdForm)
  pwdDlg.value = false
  ElMessage.success('密码已修改')
}
</script>

<style scoped>
.layout {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ===== 通栏顶栏 ===== */
.topbar {
  height: 56px;
  flex-shrink: 0;
  background: #fff;
  border-bottom: 1px solid #eef1f7;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  z-index: 10;
}
.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  white-space: nowrap;
}
.collapse-btn {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  color: #5b6478;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.collapse-btn:hover { background: #f4f6fb; color: var(--rpa-primary); }
.page-title { font-size: 15px; font-weight: 600; color: #1e2438; }

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 10px;
  white-space: nowrap;
}
.user-chip:hover { background: #f4f6fb; }
.user-avatar {
  background: linear-gradient(135deg, #4f6bf5, #7b5cf0);
  color: #fff;
  font-weight: 700;
}
.user-name { font-size: 13px; color: #3d4661; }

/* ===== 主体区 ===== */
.layout-body {
  flex: 1;
  min-height: 0;
  display: flex;
}
.aside {
  width: 216px;
  flex-shrink: 0;
  background: linear-gradient(180deg, #141a3a 0%, #101530 100%);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  overflow-x: hidden;
  transition: width 0.25s;
}
.aside.is-collapsed { width: 64px; }

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px 14px;
}
.brand-logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #4f6bf5, #7b5cf0);
  color: #fff;
  font-weight: 800;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(79, 107, 245, 0.4);
}
.brand-name { color: #fff; font-weight: 700; font-size: 16px; line-height: 1.2; }
.brand-sub { color: #6c75a0; font-size: 11px; white-space: nowrap; }

.side-menu {
  border-right: none;
  flex: 1;
  padding: 6px 10px;
}
.side-menu :deep(.el-menu-item) {
  border-radius: 8px;
  margin: 3px 0;
  height: 44px;
}
.side-menu :deep(.el-menu-item:hover) { background: rgba(255, 255, 255, 0.06); }
.side-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #4f6bf5, #7b5cf0);
  box-shadow: 0 4px 10px rgba(79, 107, 245, 0.35);
}

.main {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 18px;
}
</style>
