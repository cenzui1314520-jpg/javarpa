import { Client, type StompSubscription } from '@stomp/stompjs'
import { ElMessage } from 'element-plus'

let client: Client | null = null

// 连接未就绪时的待订阅请求，连接（含每次重连）成功后统一补订
interface PendingSub {
  destination: string
  callback: (body: any) => void
  active: boolean
}
let pendingSubs: PendingSub[] = []

function wrapCallback(callback: (body: any) => void) {
  return (msg: any) => {
    try {
      callback(JSON.parse(msg.body))
    } catch {
      callback(msg.body)
    }
  }
}

function doSubscribe(p: PendingSub): StompSubscription {
  const raw = client!.subscribe(p.destination, wrapCallback(p.callback))
  return {
    id: raw.id,
    unsubscribe: () => {
      p.active = false
      raw.unsubscribe()
    }
  }
}

function flushPending() {
  const stillPending: PendingSub[] = []
  for (const p of pendingSubs) {
    if (!p.active) continue
    if (client?.connected) {
      doSubscribe(p)
    } else {
      stillPending.push(p)
    }
  }
  pendingSubs = stillPending
}

export function connectStomp(onConnect?: () => void) {
  // 复用已有连接，避免反复新建 client 造成旧连接泄漏
  if (client && client.connected) {
    onConnect?.()
    return
  }
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  if (client) client.deactivate()
  client = new Client({
    brokerURL: `${proto}://${location.host}/ws/admin`,
    // token 走 CONNECT 帧头而非 URL query，避免进入访问日志/浏览器历史；
    // 每次连接/重连前重读 token，改密或重登后不再拿旧 token 空转
    beforeConnect: () => {
      const token = localStorage.getItem('token') || ''
      if (client) client.connectHeaders = { Authorization: `Bearer ${token}` }
    },
    reconnectDelay: 5000,
    onConnect: () => {
      flushPending()
      onConnect?.()
    },
    onStompError: frame => {
      // 重连风暴下最多每 60s 提示一次，避免弹窗轰炸
      const now = Date.now()
      const msg = frame.headers['message'] || '连接被拒绝'
      if (now - lastErrorAt > 60_000) {
        lastErrorAt = now
        console.warn('stomp error:', msg)
      }
    },
    onWebSocketClose: () => {
      // 断开后由 stompjs 自动重连，重连成功会补订 pending 订阅
    }
  })
  client.activate()
}

let lastErrorAt = 0

/**
 * 订阅实时消息。连接未就绪时自动排队，连接成功（含重连）后补订；
 * 返回的订阅对象在排队期间也可正常 unsubscribe。
 */
export function subscribe(destination: string, callback: (body: any) => void): StompSubscription {
  const p: PendingSub = { destination, callback, active: true }
  if (client && client.connected) {
    return doSubscribe(p)
  }
  pendingSubs.push(p)
  return {
    id: 'pending',
    unsubscribe: () => {
      p.active = false
      pendingSubs = pendingSubs.filter(x => x !== p)
    }
  }
}

export function disconnectStomp() {
  pendingSubs = []
  if (client) {
    client.deactivate()
    client = null
  }
}
