# 家政O2O平台

家政服务与互联网技术结合的O2O平台，为家庭用户提供在线预约家政服务，同时为家政机构提供人员管理、派单、抢单等运营功能。

## 技术栈

### 后端

| 层次 | 技术 |
|------|------|
| 框架 | Spring Boot 2.7.10, Spring Cloud 2021.0.4, Spring Cloud Alibaba 2021.0.1.0 |
| 注册/配置 | Nacos |
| 网关 | Spring Cloud Gateway |
| 微服务调用 | OpenFeign |
| ORM | MyBatis-Plus 3.4.3.2 |
| 数据库 | MySQL, Redis (Redisson 3.17.7), Elasticsearch 7.17.7 |
| 消息队列 | RabbitMQ |
| 分布式事务 | Seata 1.5.2 |
| 分库分表 | ShardingSphere-JDBC 5.4.0 |
| 数据同步 | Canal 1.1.5 |
| 定时任务 | XXL-JOB 2.3.0 |
| 状态机 | 自研 jzo2o-statemachine（Redis + MySQL） |
| 第三方 | 阿里云OSS、微信支付/登录、高德地图 |
| AI引擎 🚧 | Python FastAPI, Streamable HTTP 流式对话 |

### 前端

| 项目 | 定位 | 技术栈 |
|------|------|--------|
| `project-xzb-PC-vue3-java` | 机构端PC | Vue 3 + TypeScript + TDesign + Vite + Pinia |
| `project-xzb-pc-admin-vue3-java` | 运营管理后台PC | Vue 3 + TypeScript + TDesign + Vite + Pinia |
| `project-xzb-app-uniapp-java` | 移动端App | Uniapp |

## 微服务架构

```
客户端 → Gateway → foundations(运营基础)
                  → market(营销/优惠券)
                  → customer(客户/机构/人员)
                  → publics(微信/短信/地图)
                  → orders-manager(订单管理)
                  → orders-dispatch(派单)
                  → orders-seize(抢单)
                  → orders-history(订单历史)
                  → trade(交易)
                  → ai(AI助手) 🚧开发中 → ai-engine(Python大模型推理) 🚧
```

## 模块说明

### 基础设施 (`jzo2o-framework/`)
- **jzo2o-common** — 公共工具、异常、常量、ThreadLocal 用户上下文
- **jzo2o-mvc** — 全局异常处理、Token拦截、统一响应包装
- **jzo2o-mysql** — MyBatis-Plus 配置
- **jzo2o-redis** — 分布式锁 `@Lock`、缓存同步
- **jzo2o-es** — Elasticsearch 操作封装
- **jzo2o-rabbitmq** — RabbitMQ 客户端封装
- **jzo2o-statemachine** — 自研状态机框架
- **jzo2o-shardingsphere-jdbc** — 分库分表
- **jzo2o-thirdparty** — 阿里云OSS、微信支付/登录、高德地图
- **jzo2o-xxl-job** — XXL-JOB 定时任务
- **jzo2o-seata** — Seata 分布式事务

### 业务服务
| 模块 | 职责 |
|------|------|
| `jzo2o-gateway` | API 网关，统一鉴权与路由 |
| `jzo2o-api` | Feign 接口定义（纯接口，无实现） |
| `jzo2o-foundations` | 服务类型/服务项/区域管理 |
| `jzo2o-market` | 优惠券管理 |
| `jzo2o-customer-dev_01` | 用户/机构/服务人员/评价 |
| `jzo2o-publics` | 微信/短信/地图/文件上传 |
| `jzo2o-orders/` | 订单服务聚合（base/manager/seize/dispatch/history） |
| `jzo2o-ai/` 🚧 | AI助手Java中间层（鉴权/过滤/持久化） |
| `jzo2o-ai-engine/` 🚧 | AI大模型推理引擎（Python） |

## 快速开始

### 环境要求
- JDK 11+
- Maven 3.6+
- MySQL 8.0+, Redis, Elasticsearch 7.x, RabbitMQ
- Nacos
- Python 3.10+（AI引擎）

### 启动步骤

1. **启动基础设施**（MySQL、Redis、ES、RabbitMQ、Nacos）

2. **编译项目**
```bash
mvn clean compile -DskipTests
```

3. **启动网关**
```bash
mvn spring-boot:run -pl jzo2o-gateway -Dspring.profiles.active=dev
```

4. **启动业务服务**（按需）
```bash
mvn spring-boot:run -pl jzo2o-foundations -Dspring.profiles.active=dev
mvn spring-boot:run -pl jzo2o-customer-dev_01 -Dspring.profiles.active=dev
mvn spring-boot:run -pl jzo2o-orders/jzo2o-orders-manager -Dspring.profiles.active=dev
```

5. **启动AI引擎** 🚧
```bash
cd jzo2o-ai-engine
pip install -r requirements.txt
python main.py
```

### 前端开发
```bash
# 机构端
cd project-xzb-PC-vue3-java && npm run dev

# 管理后台
cd project-xzb-pc-admin-vue3-java && npm run dev
```

## 订单状态机

订单状态流转基于自研状态机框架，状态与事件定义在 `jzo2o-orders-base`：

```
下单 → 待支付 → 派单中 → 待服务 → 服务中 → 已完成
         ↓        ↓        ↓        ↓
       已取消   已关闭    已关闭    已关闭
```

- 状态枚举: `OrderStatusEnum`
- 事件枚举: `OrderStatusChangeEventEnum`
- 处理器: `jzo2o-orders-base/.../handler/` 下以 `order_{eventCode}` 命名的 Spring Bean

## AI助手 🚧开发中

AI 对话功能采用两层架构：
- **jzo2o-ai**（Java）：负责 Token 鉴权、敏感词过滤、业务数据预取、对话记录持久化、流量整形
- **jzo2o-ai-engine**（Python）：负责大模型推理，纯文本流式协议（SSE），不涉及 Prompt 逻辑以外的业务

## 术语约定

- 用户端称"服务人员/服务者"，不使用"阿姨/师傅/保姆"等词汇
- 机构端与运营端共享后端接口，按 controller 分包区分
