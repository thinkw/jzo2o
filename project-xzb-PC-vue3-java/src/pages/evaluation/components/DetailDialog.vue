<!-- 评价详情弹窗 -->
<template>
  <t-dialog
    v-model:visible="dialogVisible"
    header="评价详情"
    :footer="false"
    width="560px"
    :on-close="handleClose"
  >
    <div v-if="detail" class="detailBox">
      <!-- 评价对象信息 -->
      <div class="detailRow">
        <span class="label">评价对象：</span>
        <span>{{ detail.targetName }}</span>
      </div>
      <div class="detailRow">
        <span class="label">评价人：</span>
        <span>
          {{ detail.evaluatorInfo?.isAnonymous ? '匿名用户' : (detail.evaluatorInfo?.nickName || '--') }}
        </span>
      </div>
      <div class="detailRow">
        <span class="label">总评分：</span>
        <span class="scoreNum">{{ detail.totalScore || 5 }}</span>
        <span class="scoreStar">{{ '★'.repeat(Math.round((detail.totalScore || 5) / 5 * 5)) }}</span>
      </div>
      <div class="detailRow" v-if="detail.scoreArray && detail.scoreArray.length">
        <span class="label">评分明细：</span>
        <div class="scoreItems">
          <span v-for="item in detail.scoreArray" :key="item.itemName" class="scoreTag">
            {{ item.itemName }} {{ item.score }}分
          </span>
        </div>
      </div>
      <div class="detailRow">
        <span class="label">评价内容：</span>
        <div class="contentText">
          {{ detail.content || '此用户没有填写评价，系统默认好评' }}
        </div>
      </div>
      <div class="detailRow" v-if="detail.pictureArray && detail.pictureArray.length">
        <span class="label">评价图片：</span>
        <div class="pictureList">
          <t-image-viewer :images="detail.pictureArray">
            <template #trigger="{ open }">
              <img
                v-for="(pic, idx) in detail.pictureArray"
                :key="idx"
                :src="pic"
                class="evaluationImg"
                @click="open"
              />
            </template>
          </t-image-viewer>
        </div>
      </div>
      <div class="detailRow">
        <span class="label">评价时间：</span>
        <span>{{ detail.createTime }}</span>
      </div>
    </div>
    <div class="dialog-footer">
      <button class="bt-grey wt-60" @click="handleClose">关闭</button>
    </div>
  </t-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  detail: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['handleClose'])
const dialogVisible = ref(false)

watch(
  () => props.visible,
  () => {
    dialogVisible.value = props.visible
  }
)

const handleClose = () => {
  emit('handleClose')
}
</script>

<style lang="less" scoped>
.detailBox {
  padding: 16px 0;
  .detailRow {
    display: flex;
    margin-bottom: 16px;
    line-height: 24px;
    .label {
      color: #666;
      width: 80px;
      flex-shrink: 0;
    }
    .scoreNum {
      color: #f5a623;
      font-size: 18px;
      font-weight: bold;
      margin-right: 8px;
    }
    .scoreStar {
      color: #f5a623;
      font-size: 14px;
    }
    .scoreItems {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      .scoreTag {
        padding: 2px 10px;
        background: #f3f3f3;
        border-radius: 4px;
        font-size: 12px;
      }
    }
    .contentText {
      flex: 1;
      line-height: 1.6;
      color: #333;
    }
    .pictureList {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      .evaluationImg {
        width: 80px;
        height: 80px;
        object-fit: cover;
        border-radius: 4px;
        cursor: pointer;
      }
    }
  }
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid #e8e8e8;
}
</style>
