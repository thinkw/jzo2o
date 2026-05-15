import { request } from '@/utils/request'
import type { ListResult } from '@/api/model/listModel'

export function getProjectList() {
  return request.get<ListResult>({
    url: '/get-detail-list'
  })
}

// 评价列表相关 API
/**
 * 分页查询评价列表
 * @param params targetTypeId: 对象类型id, targetId: 对象id, scoreLevel: 评价等级, sortBy: 排序方式, pageNo: 页码, pageSize: 每页数量
 */
export function getEvaluationsList(params: {
  targetTypeId: string
  targetId?: string
  scoreLevel?: string
  sortBy?: number
  pageNo: number
  pageSize: number
  labelId?: string
  havePictures?: string
  haveAppend?: string
}) {
  return request.get({
    url: '/consumer/evaluation/pageByTarget',
    params
  })
}

/**
 * 删除评价
 * @param id 评价id
 * @param targetTypeId 对象类型id
 */
export function deleteComments(id: string, targetTypeId: string) {
  return request.delete({
    url: `/consumer/evaluation/${id}`,
    params: { targetTypeId }
  })
}

/**
 * 回复评价/评论
 * @param targetTypeId 对象类型id
 * @param data 回复数据
 */
export function replayComments(targetTypeId: string, data: {
  ownerId: string
  evaluationId: string
  parentId: number
  isAnonymous: number
  content: string
  pictureArray: string[]
}) {
  return request.post({
    url: '/consumer/evaluation/reply',
    params: { targetTypeId },
    data
  })
}

/**
 * 删除回复
 * @param id 回复id
 * @param targetTypeId 对象类型id
 */
export function deleteReply(id: string, targetTypeId: string) {
  return request.delete({
    url: `/consumer/evaluation/reply/${id}`,
    params: { targetTypeId }
  })
}

/**
 * 置顶/取消置顶评价
 * @param id 评价id
 * @param isTop 是否置顶 0: 取消置顶, 1: 置顶
 * @param targetTypeId 对象类型id
 */
export function setTop(id: string, isTop: number, targetTypeId: string) {
  return request.post({
    url: `/consumer/evaluation/top`,
    params: { id, isTop, targetTypeId }
  })
}

/**
 * 获取回复列表
 * @param params evaluationId: 评价id, targetTypeId: 对象类型id, sortBy: 排序方式, pageNo: 页码, pageSize: 每页数量
 */
export function getReplyList(params: {
  evaluationId: string
  targetTypeId: string
  sortBy?: number
  pageNo: number
  pageSize: number
}) {
  return request.get({
    url: '/consumer/evaluation/reply/list',
    params
  })
}

/**
 * 查询评价状态（是否可评价）
 * @param params targetTypeId: 对象类型id, targetId: 对象id
 */
export function getEvaluateById(params: {
  targetTypeId: string
  targetId: string
}) {
  return request.get({
    url: '/consumer/evaluation/status',
    params
  })
}

/**
 * 开启/关闭评价
 * @param targetTypeId 对象类型id
 * @param targetId 对象id
 * @param enabled 是否开启
 */
export function setEvaluateSwitch(targetTypeId: string, targetId: string, enabled: boolean) {
  return request.post({
    url: '/consumer/evaluation/switch',
    params: { targetTypeId, targetId, enabled }
  })
}

/**
 * 获取评价系统Token
 */
export function getEvaluationToken() {
  return request.get({
    url: '/operation/evaluation/token'
  })
}
// 首页数据
export function getDashBoardData(params) {
  return request.get({
    url: '/orders-history/operation/orders-statistics/homePage',
    params
  })
}
// 导出统计数据
export function exportStatisticsData(params) {
  return request.get({
    url: '/orders-history/operation/orders-statistics/downloadStatistics',
    params,
    responseType: 'blob'
  })
}
