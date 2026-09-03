<template>
  <el-dialog
    :model-value="visible"
    title="设备配置二维码"
    width="440px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="regen"
  >
    <el-form label-width="90px">
      <el-form-item label="服务器地址">
        <el-input v-model="serverUrl" placeholder="设备实际可访问的云端地址" @change="regen" />
      </el-form-item>
    </el-form>
    <div class="qr-box">
      <img v-if="qrDataUrl" :src="qrDataUrl" alt="设备配置二维码" />
      <el-skeleton v-else style="width: 260px; height: 260px; margin: 0 auto" />
    </div>
    <div class="meta">
      <div>设备编号：<b>{{ deviceSn }}</b></div>
      <div class="mono">secret: {{ secret }}</div>
      <div class="warn">密钥仅显示一次；关闭弹窗后将无法再次查看，可随时「重置密钥」重新生成</div>
    </div>
    <div class="tip">手机 App 点「扫码自动填入配置」，对准上方二维码即可</div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import QRCode from 'qrcode'

const props = defineProps<{
  visible: boolean
  deviceSn: string
  secret: string
}>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const serverUrl = ref('')
const qrDataUrl = ref('')

// 默认值猜一个设备可达地址：vite dev(5173) 下云端在 8080；生产同域直接用 origin。均可手改后重新出码
function defaultServer() {
  if (location.port === '5173') return `http://${location.hostname}:8080`
  return location.origin
}

async function regen() {
  const payload = {
    v: 1,
    type: 'javarpa-device',
    server: serverUrl.value.trim(),
    deviceSn: props.deviceSn,
    secret: props.secret
  }
  qrDataUrl.value = await QRCode.toDataURL(JSON.stringify(payload), { width: 260, margin: 2 })
}

watch(() => props.visible, v => {
  if (v) {
    if (!serverUrl.value) serverUrl.value = defaultServer()
    regen()
  }
})
</script>

<style scoped>
.qr-box {
  display: flex;
  justify-content: center;
  padding: 8px 0;
  background: #fff;
}
.qr-box img {
  width: 260px;
  height: 260px;
}
.meta {
  margin-top: 8px;
  line-height: 1.8;
  word-break: break-all;
}
.meta .warn {
  color: var(--el-color-warning);
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
}
.tip {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: center;
}
</style>
