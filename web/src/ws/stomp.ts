import { Client } from '@stomp/stompjs'

let client: Client | null = null

export function connectStomp(onConnect?: () => void) {
  const token = localStorage.getItem('token') || ''
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  client = new Client({
    brokerURL: `${proto}://${location.host}/ws/admin?token=${encodeURIComponent(token)}`,
    reconnectDelay: 5000,
    onConnect: () => onConnect && onConnect()
  })
  client.activate()
}

export function subscribe(destination: string, callback: (body: any) => void) {
  if (client && client.connected) {
    return client.subscribe(destination, msg => callback(JSON.parse(msg.body)))
  }
  return null
}

export function disconnectStomp() {
  if (client) {
    client.deactivate()
    client = null
  }
}
