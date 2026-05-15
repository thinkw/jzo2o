<!-- 评价搜索表单 -->
<template>
  <div class="formBox bg-wt">
    <t-form ref="form" :data="formData" :label-width="100">
      <t-row>
        <t-col>
          <t-form-item label="评分等级：" name="scoreLevel">
            <t-select
              v-model="formData.scoreLevel"
              class="form-item-content"
              placeholder="请选择"
              clearable
            >
              <t-option label="好评" :value="3" />
              <t-option label="中评" :value="2" />
              <t-option label="差评" :value="1" />
            </t-select>
          </t-form-item>
        </t-col>
        <t-col>
          <t-form-item label="评价时间：" name="evaluationTime" :label-width="80">
            <t-date-range-picker
              v-model="formData.evaluationTime"
              class="form-item-content"
              placeholder="请选择时间范围"
              clearable
            />
          </t-form-item>
        </t-col>
        <t-col class="searchBtn">
          <button class="bt-grey wt-60" @click="handleReset()">重置</button>
          <button class="bt wt-60" @click="handleSearch()">搜索</button>
        </t-col>
      </t-row>
    </t-form>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const formData = ref({
  scoreLevel: null,
  evaluationTime: []
})

const emit: Function = defineEmits(['handleSearch', 'handleReset'])

const searchForm = {
  scoreLevel: null,
  evaluationTime: []
}

// 重置表单
const handleReset = () => {
  formData.value = { ...searchForm }
  emit('handleSearch', formData.value)
}

// 搜索表单
const handleSearch = () => {
  emit('handleSearch', formData.value)
}
</script>

<style lang="less" scoped src="../index.less"></style>
