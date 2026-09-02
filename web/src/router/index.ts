import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/Login.vue') },
    {
      path: '/',
      component: () => import('../layout/AdminLayout.vue'),
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '仪表盘' } },
        { path: 'devices', component: () => import('../views/Devices.vue'), meta: { title: '设备管理' } },
        { path: 'groups', component: () => import('../views/Groups.vue'), meta: { title: '设备分组' } },
        { path: 'scripts', component: () => import('../views/Scripts.vue'), meta: { title: '脚本管理' } },
        { path: 'scripts/:id', component: () => import('../views/ScriptDetail.vue'), meta: { title: '脚本详情' } },
        { path: 'tasks', component: () => import('../views/Tasks.vue'), meta: { title: '任务管理' } },
        { path: 'tasks/:id', component: () => import('../views/TaskDetail.vue'), meta: { title: '任务详情' } },
        { path: 'logs', component: () => import('../views/Logs.vue'), meta: { title: '运行日志' } },
        { path: 'stats', component: () => import('../views/Stats.vue'), meta: { title: '数据统计' } },
        { path: 'tokens', component: () => import('../views/Tokens.vue'), meta: { title: 'API Token' } }
      ]
    },
    // 404 兜底，避免渲染空白页
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach((to, _from, next) => {
  const logged = !!localStorage.getItem('token')
  if (to.path !== '/login' && !logged) {
    next('/login')
  } else if (to.path === '/login' && logged) {
    next('/dashboard')
  } else {
    next()
  }
})

router.afterEach(to => {
  const title = (to.meta as any)?.title
  document.title = title ? `${title} · JavaRPA` : 'JavaRPA 云端管理平台'
})

export default router
