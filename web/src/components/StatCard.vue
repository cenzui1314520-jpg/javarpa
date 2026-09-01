<template>
  <div class="stat-card">
    <div class="stat-icon" :class="gradient">
      <el-icon :size="22"><component :is="icon" /></el-icon>
    </div>
    <div class="stat-body">
      <div class="stat-label">{{ title }}</div>
      <div class="stat-value num">{{ value }}</div>
      <div v-if="sub" class="stat-sub">{{ sub }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  value: string | number
  sub?: string
  icon: any
  gradient?: string
}>()
</script>

<style scoped>
.stat-card {
  background: #fff;
  border-radius: var(--rpa-radius);
  box-shadow: var(--rpa-card-shadow);
  padding: 18px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  transition: transform 0.2s, box-shadow 0.2s;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(16, 24, 40, 0.09);
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}
.stat-label {
  color: #7c869c;
  font-size: 13px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.3;
  color: #1e2438;
}
.stat-sub {
  color: #9aa3b8;
  font-size: 12px;
  margin-top: 2px;
}
</style>
