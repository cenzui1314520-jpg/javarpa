import { Client, type StompSubscription } from '@stomp/stompjs'
import { ElMessage } from 'element-plus'

let client: Client | null = null

export function connectStomp(onConnect?: () => void) {
  // 复用已有连接，避免反复新建 client 造成旧连接泄漏
  if (client && client.connected) {
    onConnect?.()
    return
  }
  const token = localStorage.getItem('token') || ''
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  if (client) client.deactivate()
  client = new Client({
    brokerURL: `${proto}://${location.host}/ws/admin`,
    // token 走 CONNECT 帧头而非 URL query，避免进入访问日志/浏览器历史
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 5000,
    onConnect: () => onConnect?.(),
    onStompError: frame => {
      ElMessage.error(`实时通道错误: ${frame.headers['message'] || '连接被拒绝'}`)
    },
    onWebSocketClose: () => {
      if (!client?.connected) {
        ElMessage.warning('实时通道已断开，将自动重连')
      }
    }
  })
  client.activate()
}

export function subscribe(destination: string, callback: (body: any) => void): StompSubscription | null {
  if (client && client.connected) {
    return client.subscribe(destination, msg => {
      try {
        callback(JSON.parse(msg.body))
      } catch {
        callback(msg.body)
      }
    })
  }
  ElMessage.warning('实时通道未就绪，稍后将自动重试')
  return null
}

export function disconnectStomp() {
  if (client) {
    client.deactivate()
    client = null
  }
}
