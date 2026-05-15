import { formatDateTimeToDateTimeString } from '@/utils/date'

// 评价等级映射
export const SCORE_LEVEL_MAP = {
  1: '差评',
  2: '中评',
  3: '好评'
}

// 评价列表表格列
export const COLUMNS = [
  {
    title: '序号',
    align: 'left',
    width: 80,
    colKey: 'index'
  },
  {
    title: '评价对象',
    width: 160,
    colKey: 'targetName'
  },
  {
    title: '评价内容',
    width: 260,
    colKey: 'content',
    cell: (h, { row }) => {
      if (row.content) {
        return h('span', { class: 'evaluationContent' }, row.content)
      }
      return h('span', { style: 'color: #999' }, '此用户没有填写评价，系统默认好评')
    }
  },
  {
    title: '评分',
    width: 200,
    colKey: 'totalScore',
    cell: (h, { row }) => {
      const score = row.totalScore || 5
      const level = row.scoreLevel
      const stars = h('span', { style: 'color: #f5a623; margin-right: 6px' }, '★'.repeat(Math.round(score / 5 * 5)))
      const levelText = h('span', { style: 'font-size: 12px' }, SCORE_LEVEL_MAP[level] || '好评')
      return h('span', [stars, levelText])
    }
  },
  {
    title: '评价人',
    width: 140,
    colKey: 'evaluatorInfo',
    cell: (h, { row }) => {
      const info = row.evaluatorInfo
      if (!info) return ''
      if (info.isAnonymous) return '匿名用户'
      return info.nickName || '匿名用户'
    }
  },
  {
    title: '评价时间',
    width: 180,
    colKey: 'createTime',
    sorter: true,
    cell: (h, { row }) =>
      h('span', formatDateTimeToDateTimeString(new Date(row.createTime)))
  },
  {
    align: 'left',
    fixed: 'right',
    width: 160,
    colKey: 'op',
    title: '操作'
  }
]
