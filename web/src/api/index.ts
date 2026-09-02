import http from './http'

// ---------- 核心实体契约（与后端 R<T> 包装对应） ----------

export interface DeviceOption {
  id: number
  deviceSn: string
  name?: string
  online: number
}

export interface PageResult<T = any> {
  total: number
  pages: number
  list: T[]
}

export interface LoginResult {
  token: string
  admin: { id: number; username: string; nickname?: string; role: string }
}

export interface DeviceCreated {
  id: number
  deviceSn: string
  secret: string
}

export interface TokenCreated {
  id: number
  name: string
  token: string
  prefix: string
}

export interface TaskDetailResult {
  id: number
  name: string
  scriptId: number
  versionCode?: number | null
  taskDevices: { deviceId: number; status: string; successCount: number; failCount: number }[]
  executions: any[]
}

export const login = (data: any) => http.post<LoginResult>('/auth/login', data)
export const changePassword = (data: any) => http.post('/auth/change-password', data)

// devices
export const pageDevices = (params: any) => http.get<PageResult>('/devices/page', { params })
// 选择器用不分页精简列表，避免 page 接口 200 上限造成静默截断
export const deviceOptions = () => http.get<DeviceOption[]>('/devices/options')
export const createDevice = (data: any) => http.post<DeviceCreated>('/devices', data)
export const updateDevice = (id: any, data: any) => http.put(`/devices/${id}`, data)
export const deleteDevice = (id: any) => http.delete(`/devices/${id}`)
export const resetSecret = (id: any) => http.post<{ secret: string }>(`/devices/${id}/reset-secret`)
export const deviceCommand = (id: any, data: any) => http.post(`/devices/${id}/command`, data)

// groups
export const listGroups = () => http.get<any>('/groups')
export const createGroup = (data: any) => http.post<any>('/groups', data)
export const updateGroup = (id: any, data: any) => http.put(`/groups/${id}`, data)
export const deleteGroup = (id: any) => http.delete(`/groups/${id}`)
export const setGroupMembers = (id: any, deviceIds: any[]) => http.post(`/groups/${id}/members`, { deviceIds })
export const groupDevices = (id: any) => http.get<any>(`/groups/${id}/devices`)

// scripts
export const listScripts = () => http.get<any>('/scripts')
export const createScript = (data: any) => http.post<any>('/scripts', data)
export const updateScript = (id: any, data: any) => http.put(`/scripts/${id}`, data)
export const deleteScript = (id: any) => http.delete(`/scripts/${id}`)
export const listVersions = (id: any) => http.get<any>(`/scripts/${id}/versions`)
export const uploadVersion = (id: any, form: FormData) =>
  // 大文件上传豁免全局 20s 超时
  http.post<any>(`/scripts/${id}/versions`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000
  })
export const publishScript = (id: any, data: any) => http.post(`/scripts/${id}/publish`, data)
export const publishRecords = (id: any) => http.get<any>(`/scripts/${id}/publish-records`)

// tasks
export const listTasks = () => http.get<any>('/tasks')
export const createTask = (data: any) => http.post<any>('/tasks', data)
export const updateTask = (id: any, data: any) => http.put(`/tasks/${id}`, data)
export const deleteTask = (id: any) => http.delete(`/tasks/${id}`)
export const taskAction = (id: any, action: string) => http.post(`/tasks/${id}/actions`, { action })
export const taskDetail = (id: any) => http.get<TaskDetailResult>(`/tasks/${id}`)

// logs & stats
export const pageLogs = (params: any) => http.get<any>('/logs', { params })
export const statsSummary = () => http.get<any>('/stats/summary')
export const statsTrend = (days: number) => http.get<any>('/stats/trend', { params: { days } })
export const statsByTask = (start: string, end: string) =>
  http.get<any>('/stats/by-task', { params: { start, end } })

// tokens
export const listTokens = () => http.get<any>('/tokens')
export const createToken = (data: any) => http.post<TokenCreated>('/tokens', data)
export const setTokenStatus = (id: any, status: number) => http.post(`/tokens/${id}/status`, { status })
