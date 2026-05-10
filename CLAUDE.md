# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

云岚到家 — 家政O2O平台。将家政服务与互联网技术结合，为家庭用户提供在线预约家政服务，同时为家政机构提供人员管理、派单、抢单等运营功能。

## 后端技术栈

- Java 11, Spring Boot 2.7.10, Spring Cloud 2021.0.4, Spring Cloud Alibaba 2021.0.1.0
- Nacos (服务注册/配置中心), Spring Cloud Gateway, OpenFeign
- MyBatis-Plus 3.4.3.2, MySQL, Redis (Redisson 3.17.7), Elasticsearch 7.17.7, RabbitMQ
- Seata 1.5.2 (分布式事务), ShardingSphere-JDBC 5.4.0 (分库分表)
- Canal 1.1.5 (MySQL binlog 同步), XXL-JOB 2.3.0 (定时任务)
- Spring Statemachine (状态机), Knife4j (API文档), Hutool 5.8.24

## 后端模块结构

```
jzo2o-framework/                        ← 基础设施层（被业务模块依赖）
  jzo2o-parent         — 父POM，管理所有依赖版本
  jzo2o-common         — 公共工具（UserContext ThreadLocal用户上下文、JWT）
                         + 公共模型（Result, CurrentUserInfo, PageResult）
                         + 公共异常（BadRequestException, CommonException...）
                         + 常量（HeaderConstants, MqConstants, UserType...）
  jzo2o-mvc            — 全局异常处理（CommonExceptionAdvice）
                         + 用户Token拦截器（UserContextInteceptor）
                         + 统一响应包装 + 序列化配置
  jzo2o-mysql          — MyBatis-Plus配置、分页拦截器
  jzo2o-redis          — 分布式锁注解 @Lock（基于Redisson）
                         + 缓存同步（Canal+MQ + @LockSync 注解）
                         + Redis配置（Redisson, CacheManager）
  jzo2o-es             — Elasticsearch操作封装（索引操作、文档CRUD、搜索）
  jzo2o-rabbitmq       — RabbitMQ客户端封装（消息发送、延迟消息、消费确认）
  jzo2o-canal-sync     — Canal binlog监听 + 自动同步ES/缓存
  jzo2o-statemachine   — Spring Statemachine状态机封装
  jzo2o-shardingsphere-jdbc — ShardingSphere分库分表配置
  jzo2o-thirdparty     — 第三方服务：阿里云OSS、微信支付/登录、高德地图
  jzo2o-xxl-job        — XXL-JOB定时任务调度集成
  jzo2o-knife4j-web    — Knife4j API文档配置
  jzo2o-seata          — Seata分布式事务配置

jzo2o-gateway/                          ← 网关层
  └─ com.jzo2o.gateway
       ├─ GatewayApplication             — 启动类
       ├─ filter/                        — AuthFilter、TokenFilter（Gateway过滤器）
       ├─ config/                        — 网关配置（跨域、路由）
       ├─ constants/                     — 常量
       ├─ properties/                    — 配置属性（白名单路径）
       └─ utils/                         — 工具类

jzo2o-api/                              ← Feign接口层（纯接口定义，无业务逻辑）
  └─ com.jzo2o.api
       ├─ customer/                      — 客户服务：AddressBookApi, CommonUserApi, CustomerApi,
       │   + dto/                          EvaluationApi, InstitutionApi, ServeProviderApi...
       ├─ foundations/                   — 运营基础：RegionApi, ServeApi, ServeItemApi, ServeTypeApi
       │   + dto/
       ├─ market/                        — 营销服务：CouponApi
       │   + dto/
       ├─ orders/                        — 订单服务：OrdersApi, OrdersHistoryApi, OrdersServeApi...
       │   + dto/
       ├─ publics/                       — 公共服务：WechatApi, SmsCodeApi, MapApi
       │   + dto/
       ├─ trade/                         — 交易服务：TradingApi, NativePayApi, RefundRecordApi
       │   + dto/ + enums/
       ├─ config/                        — Feign通用配置（MyQueryMapEncoder）
       └─ interceptor/                   — Feign请求拦截器

jzo2o-foundations/                      ← 运营基础服务（服务类型、服务项、区域管理）
  └─ com.jzo2o.foundations
       ├─ FoundationsApplication         — 启动类（@MapperScan, @EnableCaching）
       ├─ controller/
       │    ├─ consumer/                  — 用户端接口
       │    ├─ worker/                    — 服务人员端接口
       │    ├─ agency/                    — 机构端接口
       │    ├─ operation/                 — 运营管理端接口
       │    ├─ inner/                     — 内部Feign调用接口（暴露给jzo2o-api）
       │    └─ open/                      — 公开接口（无需Token）
       ├─ service/ + impl/               — 业务逻辑层
       ├─ mapper/                        — MyBatis Mapper接口
       ├─ model/
       │    ├─ domain/                    — 数据库实体
       │    └─ dto/request/,response/     — 入参/出参DTO
       ├─ config/                        — 配置类
       ├─ constants/                     — 业务常量
       ├─ enums/                         — 业务枚举
       ├─ handler/                       — 处理器
       └─ properties/                    — 配置属性

jzo2o-market/                           ← 营销服务（优惠券管理）
  └─ com.jzo2o.market
       ├─ MarketApplication              — 启动类
       ├─ controller/
       │    ├─ consumer/                  — 用户端接口
       │    ├─ inner/                     — 内部Feign调用接口
       │    └─ operation/                 — 运营管理端接口
       ├─ service/ + impl/               — 业务逻辑层
       ├─ mapper/                        — MyBatis Mapper接口
       ├─ model/
       │    ├─ domain/                    — 数据库实体
       │    └─ dto/request/,response/     — 入参/出参DTO
       ├─ config/                        — 配置类
       ├─ constants/                     — 业务常量
       ├─ enums/                         — 业务枚举
       ├─ handler/                       — 处理器
       └─ utils/                         — 工具类

jzo2o-publics/                          ← 公共服务（微信、短信、地图、文件上传）
  └─ com.jzo2o.publics
       ├─ PublicsApplication             — 启动类
       ├─ controller/
       │    ├─ inner/                     — 内部Feign调用接口
       │    └─ outer/                     — 外部公开接口
       ├─ service/ + impl/               — 业务逻辑层
       └─ model/dto/request/,response/   — 入参/出参DTO

jzo2o-customer-dev_01/                  ← 客户服务（用户、机构、服务人员、评价）
  └─ com.jzo2o.customer
       ├─ CustomerApplicaiton            — 启动类（注意类名拼写）
       ├─ controller/
       │    ├─ consumer/                  — 用户端（C端）
       │    ├─ worker/                    — 服务人员端
       │    ├─ agency/                    — 机构端
       │    ├─ operation/                 — 运营管理端
       │    ├─ inner/                     — 内部Feign调用接口
       │    └─ open/                      — 公开接口（无需Token）
       ├─ service/ + impl/               — 业务逻辑层
       ├─ mapper/                        — MyBatis Mapper接口
       ├─ model/
       │    ├─ domain/                    — 数据库实体
       │    └─ dto/request/,response/     — 入参/出参DTO
       ├─ config/                        — 配置类
       ├─ constants/                     — 业务常量
       ├─ enums/                         — 业务枚举
       ├─ handler/                       — 处理器
       ├─ listener/                      — MQ监听器
       ├─ client/                        — 自定义客户端
       └─ properties/                    — 配置属性
```

## 后端微服务路由

Gateway 按路径前缀路由到对应微服务（`lb://` 负载均衡），所有路由均通过 `Token` 过滤器：
- `/foundations/**` → `jzo2o-foundations`
- `/market/**` → `jzo2o-market`
- `/publics/**` → `jzo2o-publics`
- `/customer/**` → `jzo2o-customer`
- `/orders-manager/**`、`/orders-dispatch/**`、`/orders-seize/**`、`/orders-history/**` → 对应订单服务
- `/trade/**` → `jzo2o-trade`

白名单路径（无需Token）定义在 `jzo2o.access-path-white-list` 配置项中，包括登录、注册、Swagger文档等。

## 后端关键模式

- **当前用户**: `UserContext`（ThreadLocal）存储当前请求的用户信息，由MVC拦截器在请求时设置
- **分布式锁**: `@Lock(formatter="key表达式")` 注解，基于Redisson，支持自动续期
- **Feign客户端**: 所有Feign接口定义在 `jzo2o-api` 模块，按被调用方的服务名分包。Controller 需为内部调用暴露 `/inner/` 路径
- **Controller分层**: 各业务模块按 `consumer`(用户端)、`worker`(服务人员端)、`agency`(机构端)、`operation`(运营管理端)、`inner`(内部Feign调用)、`open`(公开接口) 分包
- **异常体系**: 统一使用 `com.jzo2o.common.expcetions` 下的异常类型，由 `CommonExceptionAdvice` 全局处理
- **单元测试**: `maven-surefire-plugin` 配置了 `<skip>true</skip>`，打包时跳过测试

## 构建命令

```bash
# 编译全部模块（跳过测试）
mvn clean compile -DskipTests

# 打包全部模块
mvn clean package -DskipTests

# 编译单个模块
mvn clean compile -pl jzo2o-gateway -am

# 打包单个模块
mvn clean package -pl jzo2o-gateway -am

# 运行单个模块（指定profile）
mvn spring-boot:run -pl jzo2o-gateway -Dspring.profiles.active=dev
```

## 前端项目

三个独立前端项目，均使用 Vue 3 + TypeScript + TDesign + Vite + Pinia：

| 项目 | 定位 | 开发命令 |
|------|------|----------|
| `project-xzb-PC-vue3-java` | 机构端PC | `npm run dev` (mock) / `npm run dev:linux` (连接测试环境) |
| `project-xzb-pc-admin-vue3-java` | 运营管理后台PC | `npm run dev` (mock) / `npm run dev:linux` (连接测试环境) |
| `project-xzb-app-uniapp-java` | 移动端App (Uniapp) | 通过 HBuilderX 运行 |

机构端和后台端共享相同的技术栈和项目结构（`src/api/`、`src/pages/`、`src/components/`、`src/layouts/`、`src/router/`）。

## 命名约定与规范

- 前端: 页面命名用小写驼峰，组件用大写开头，公共样式用 `-` 连接，内部样式用驼峰
- 用户侧文案: 用户端称"服务人员/服务者"，机构端称"服务人员/服务者"，不要用"阿姨/师傅/保姆"等词汇
- 后端: 遵循项目现有 MyBatis-Plus + Spring MVC 分层模式编写代码
