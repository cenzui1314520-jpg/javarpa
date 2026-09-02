import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { disconnectStomp } from '../ws/stomp'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 并发多个 401 时只处理一次跳转
let redirecting = false

http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  resp => {
    const body = resp.data
    if (body && body.code !== undefined && Number(body.code) !== 0) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg))
    }
    return body ? body.data : body
  },
  err => {
    if (err.response && err.response.status === 401) {
      if (!redirecting) {
        redirecting = true
        localStorage.removeItem('token')
        localStorage.removeItem('admin')
        // 同步停掉 STOMP，否则旧 token 每 5s 重连一次触发错误风暴
        disconnectStomp()
        ElMessage.warning('登录已过期，请重新登录')
        const redirect = encodeURIComponent(location.pathname + location.search)
        router.push(`/login?redirect=${redirect}`).finally(() => { redirecting = false })
      }
    } else {
      ElMessage.error(err.response?.data?.msg || err.message || '网络错误')
    }
    return Promise.reject(err)
  }
)

// 响应拦截器已把 AxiosResponse 解包为 body.data，修正导出静态类型
type UnwrappedHttp = {
  get<T = any>(url: string, config?: any): Promise<T>
  post<T = any>(url: string, data?: any, config?: any): Promise<T>
  put<T = any>(url: string, data?: any, config?: any): Promise<T>
  delete<T = any>(url: string, config?: any): Promise<T>
}

export default http as unknown as UnwrappedHttp
