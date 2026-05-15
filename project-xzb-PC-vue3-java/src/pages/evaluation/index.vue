<!-- 评价管理 -->
<template>
  <div class="base-up-wapper bgTable min-h">
    <!-- 搜索表单区域 -->
    <SearchForm
      @handleSearch="handleSearch"
      @handleReset="handleReset"
    />
    <!-- 表格 -->
    <div class="baseList bg-wt min-h">
      <div class="tableBoxs">
        <t-table
          :data="listData"
          :columns="COLUMNS"
          row-key="id"
          vertical-align="middle"
          :hover="true"
          :pagination="pagination.total <= 10 || !pagination.total ? null : pagination"
          :loading="dataLoading"
          :sort="sort"
          table-layout="fixed"
          @page-change="onPageChange"
          @sort-change="sortChange"
        >
          <!-- 空页面 -->
          <template #empty>
            <no-data content="暂无评价数据" />
          </template>
          <!-- 序号 -->
          <template #index="{ rowIndex }">
            <span>{{ (pagination.defaultCurrent - 1) * pagination.defaultPageSize + rowIndex + 1 }}</span>
          </template>
          <!-- 操作栏 -->
          <template #op="{ row }">
            <a class="btn-dl line btn-split-right" @click="handleClickDetail(row)">
              详情
            </a>
            <a class="btn-dl line btn-split-right" @click="handleClickSummary(row)">
              AI 总结
            </a>
            <a class="font-bt line" @click="handleClickDelete(row)">删除</a>
          </template>
        </t-table>
      </div>
    </div>
    <!-- 详情弹窗 -->
    <DetailDialog
      :visible="detailVisible"
      :detail="detailData"
      @handleClose="detailVisible = false"
    />
    <!-- 删除弹窗 -->
    <Delete
      :title="'确认删除'"
      :dialog-delete-visible="dialogDeleteVisible"
      :delete-text="'确认删除该评价吗？（一经删除无法恢复）'"
      @handle-delete="handleDelete"
      @handle-close="dialogDeleteVisible = false"
    />
    <!-- AI 总结弹窗 -->
    <t-dialog
      v-model:visible="summaryVisible"
      header="AI 评价总结"
      width="800px"
      :footer="false"
    >
      <div class="summary-target">
        评价对象：<strong>{{ summaryTargetName }}</strong>
      </div>
      <div v-if="summaryLoading" class="summary-loading">
        <t-loading text="AI 正在生成评价总结..." />
      </div>
      <div v-else-if="summaryError" class="summary-error">
        {{ summaryError }}
      </div>
      <div v-else-if="summaryContent" class="summary-content">
        <ChatMarkdown :content="summaryContent" />
      </div>
      <div v-else class="summary-empty">暂无总结数据</div>
      <div class="summary-footer">
        <button class="bt-grey wt-60" @click="summaryVisible = false">关闭</button>
        <button class="bt wt-100" :disabled="summaryLoading" @click="handleClickSummary(currentSummaryRow)">
          {{ summaryLoading ? '生成中...' : '重新生成' }}
        </button>
      </div>
    </t-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import { getEvaluationList, getEvaluationDetail, deleteEvaluation, getEvaluationSummary } from '@/api/service'
import { COLUMNS } from './constants'
import SearchForm from './components/SearchForm.vue'
import DetailDialog from './components/DetailDialog.vue'
import Delete from '@/components/Delete/index.vue'
import NoData from '@/components/noData/index.vue'
import ChatMarkdown from '@/components/chat/ChatMarkdown.vue'

const listData = ref([])
const dataLoading = ref(false)
const detailVisible = ref(false)
const detailData = ref(null)
const dialogDeleteVisible = ref(false)
const deleteId = ref('')

// AI 总结相关
const summaryVisible = ref(false)
const summaryLoading = ref(false)
const summaryContent = ref('')
const summaryError = ref('')
const summaryTargetName = ref('')
const currentSummaryRow = ref(null)

// 分页
const pagination = ref({
  defaultPageSize: 10,
  total: 0,
  defaultCurrent: 1
})
// 排序
const sort = ref([{ sortBy: 'createTime', descending: true }])
// 搜索参数
const searchParams = ref({
  scoreLevel: null,
  minEvaluationTime: '',
  maxEvaluationTime: ''
})
// 请求参数
const requestData = ref({
  targetTypeId: '7',
  pageNo: 1,
  pageSize: 10,
  sortBy: 1,
  scoreLevel: null,
  minEvaluationTime: '',
  maxEvaluationTime: ''
})

onMounted(() => {
  fetchData()
})

// 获取列表数据
const fetchData = async () => {
  dataLoading.value = true
  try {
    const res = await getEvaluationList(requestData.value)
    if (res.code === 200) {
      listData.value = res.data.list || []
      pagination.value.total = Number(res.data.total || 0)
    }
    dataLoading.value = false
  } catch (err) {
    console.error(err)
    dataLoading.value = false
  }
}

// 搜索
const handleSearch = (val) => {
  if (val.scoreLevel) {
    requestData.value.scoreLevel = val.scoreLevel
  } else {
    requestData.value.scoreLevel = null
  }
  if (val.evaluationTime && val.evaluationTime.length === 2) {
    requestData.value.minEvaluationTime = val.evaluationTime[0]
    requestData.value.maxEvaluationTime = val.evaluationTime[1]
  } else {
    requestData.value.minEvaluationTime = ''
    requestData.value.maxEvaluationTime = ''
  }
  requestData.value.pageNo = 1
  pagination.value.defaultCurrent = 1
  fetchData()
}

// 重置
const handleReset = () => {
  requestData.value.scoreLevel = null
  requestData.value.minEvaluationTime = ''
  requestData.value.maxEvaluationTime = ''
  requestData.value.pageNo = 1
  pagination.value.defaultCurrent = 1
  fetchData()
}

// 翻页
const onPageChange = (val) => {
  requestData.value.pageNo = val.defaultCurrent || val.current
  pagination.value.defaultCurrent = val.defaultCurrent || val.current
  requestData.value.pageSize = val.defaultPageSize || val.pageSize
  pagination.value.defaultPageSize = val.defaultPageSize || val.pageSize
  fetchData()
}

// 排序
const sortChange = (val) => {
  sort.value = val
  if (val && val.length > 0) {
    requestData.value.sortBy = val[0].descending ? 1 : 1
  }
  fetchData()
}

// 查看详情
const handleClickDetail = async (row) => {
  try {
    const res = await getEvaluationDetail(row.id)
    if (res.code === 200) {
      detailData.value = res.data
      detailVisible.value = true
    }
  } catch (err) {
    console.error(err)
  }
}

// 点击删除
const handleClickDelete = (row) => {
  deleteId.value = row.id
  dialogDeleteVisible.value = true
}

// AI 总结 — GET 端点已自带"有则返回/无则生成"逻辑
const handleClickSummary = async (row) => {
  currentSummaryRow.value = row
  summaryTargetName.value = row.targetName || ''
  summaryVisible.value = true
  summaryError.value = ''
  summaryContent.value = ''
  summaryLoading.value = true

  try {
    const res = await getEvaluationSummary(7, row.targetId)
    // 后端返回的 data 字段包含 {summary: "..."}
    if (res && res.data && res.data.summary) {
      summaryContent.value = res.data.summary
    } else {
      summaryError.value = (res && res.msg) || '无返回数据'
    }
  } catch (err) {
    const msg = err?.response?.data?.msg || err?.message || String(err)
    summaryError.value = msg
  } finally {
    summaryLoading.value = false
  }
}

// 确认删除
const handleDelete = async () => {
  try {
    const res = await deleteEvaluation(deleteId.value)
    if (res.code === 200) {
      dialogDeleteVisible.value = false
      MessagePlugin.success('删除成功')
      fetchData()
    } else {
      MessagePlugin.error(res.msg || '删除失败')
    }
  } catch (err) {
    console.error(err)
  }
}
</script>

<style lang="less" scoped src="./index.less"></style>
