<template>
  <div class="login-page">
    <div class="login-left">
      <div class="deco deco-1" />
      <div class="deco deco-2" />
      <div class="login-brand">
        <div class="logo">R</div>
        <h1>JavaRPA</h1>
        <p class="slogan">云端脚本管理 · 设备集群控制 · 自动化调度中心</p>
        <ul class="features">
          <li><span class="f-dot" />脚本热更新，免重装 APK</li>
          <li><span class="f-dot" />多设备分组与批量任务调度</li>
          <li><span class="f-dot" />灰度发布 · 版本回滚 · 失败重试</li>
          <li><span class="f-dot" />实时日志与成功率统计</li>
        </ul>
      </div>
    </div>

    <div class="login-right">
      <el-card class="login-card" shadow="never">
        <h2 class="card-title">欢迎回来</h2>
        <p class="card-sub">登录管理控制台</p>
        <el-form :model="form" label-width="0" size="large" @keyup.enter="doLogin">
          <el-form-item>
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              show-password
              :prefix-icon="Lock"
            />
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading"
            @click="doLogin"
          >
            登 录
          </el-button>
        </el-form>
        <p class="card-tip">默认账号 admin / admin123，登录后请立即修改密码</p>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../api'

const router = useRouter()
const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)

const doLogin = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data: any = await login(form)
    localStorage.setItem('token', data.token)
    localStorage.setItem('admin', JSON.stringify(data.admin))
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
}
.login-left {
  flex: 1.15;
  position: relative;
  overflow: hidden;
  background: linear-gradient(140deg, #141a3a 0%, #22205e 55%, #372a86 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.deco {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.45;
}
.deco-1 { width: 380px; height: 380px; background: #4f6bf5; top: -80px; left: -60px; }
.deco-2 { width: 300px; height: 300px; background: #7b5cf0; bottom: -60px; right: -40px; }

.login-brand { position: relative; color: #fff; max-width: 420px; padding: 0 40px; }
.logo {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: linear-gradient(135deg, #4f6bf5, #7b5cf0);
  font-size: 26px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 24px rgba(79, 107, 245, 0.5);
  margin-bottom: 18px;
}
.login-brand h1 { font-size: 30px; margin: 0 0 6px; letter-spacing: 0.5px; }
.slogan { color: #9aa3d8; font-size: 14px; margin: 0 0 28px; }
.features { list-style: none; padding: 0; margin: 0; }
.features li {
  color: #c6cdf2;
  font-size: 14px;
  padding: 8px 0;
  display: flex;
  align-items: center;
}
.f-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f6bf5, #7b5cf0);
  margin-right: 10px;
  box-shadow: 0 0 8px rgba(123, 92, 240, 0.8);
}

.login-right {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--rpa-bg);
}
.login-card {
  width: 400px;
  padding: 12px 8px;
  border-radius: 18px;
}
.card-title { margin: 8px 12px 2px; font-size: 22px; }
.card-sub { margin: 0 12px 22px; color: #9aa3b8; font-size: 13px; }
.login-btn {
  width: 100%;
  margin-top: 4px;
  background: linear-gradient(135deg, #4f6bf5, #7b5cf0);
  border: none;
  height: 44px;
  font-size: 15px;
  letter-spacing: 4px;
}
.login-btn:hover { opacity: 0.92; }
.card-tip {
  margin: 16px 12px 0;
  color: #b0b8cc;
  font-size: 12px;
  text-align: center;
}

@media (max-width: 860px) {
  .login-left { display: none; }
}
</style>
