# 迷你实时分析系统 - 架构文档

最后更新: 2026-01-18

---

## 📋 概述

这是一个**以学习为导向**的实时分析系统,展示了从 HTTP 轮询到消息队列模式的演进过程。系统处理事件流(ENQUEUED/DEQUEUED)并提供实时队列指标。

**核心设计理念**:

- ✅ 从简单开始,逐步演进
- ✅ 端到端可运行 > 完美架构
- ✅ 优先使用内存存储,后续再引入外部依赖
- ✅ 将日志作为产品功能

---

## 🏗️ 系统架构概览

### 高层架构

```
┌─────────────┐                          ┌─────────────┐
│   Client    │                          │   Client    │
└──────┬──────┘                          └──────┬──────┘
       │                                        │
       │ POST /v1/events                        │ GET /v1/metrics/queues/{id}
       ▼                                        ▼
┌──────────────────────────────┐       ┌──────────────────────────────┐
│   Producer (Port 8080)       │       │   Consumer (Port 8081)       │
│  ┌────────────────────────┐  │       │  ┌────────────────────────┐  │
│  │   EventController      │  │       │  │  MetricsController     │  │
│  └──────────┬─────────────┘  │       │  └──────────┬─────────────┘  │
│             │                 │       │             │                 │
│  ┌──────────▼─────────────┐  │       │  ┌──────────▼─────────────┐  │
│  │   EventService         │  │       │  │ EventConsumerService   │  │
│  └──────────┬─────────────┘  │       │  └──────────┬─────────────┘  │
│             │                 │       │             │                 │
│  ┌──────────▼─────────────┐  │       │  ┌──────────▼─────────────┐  │
│  │  EventRepository       │  │       │  │ EventPollingScheduler  │  │
│  │  (ArrayList)           │  │       │  │  (@Scheduled 5s)       │  │
│  └────────────────────────┘  │       │  └──────────┬─────────────┘  │
│                               │       │             │                 │
│  GET /v1/events/poll          │       │             │                 │
│  ?after={cursor}&limit={n}    │◄──────┤─────────────┘                 │
└───────────────────────────────┘       │                               │
                                        │  ┌──────────────────────┐    │
                                        │  │ Cursor + Epoch       │    │
                                        │  │ (data/consumer-      │    │
                                        │  │  cursor.txt)         │    │
                                        │  └──────────────────────┘    │
                                        └───────────────────────────────┘
```

---

## 🧩 组件分解

### 1. Producer 服务 (端口 8080)

**职责**: 事件接收、存储和提供服务

#### 1.1 EventController

- **文件**: [EventController.java](producer/src/main/java/com/learning/producer/controller/EventController.java)
- **端点**:
  - `POST /v1/events` - 创建单个事件
  - `GET /v1/events/poll?after={cursor}&limit={n}` - 轮询事件
  - `GET /v1/events/stats` - 系统统计
  - `GET /v1/events/health` - 健康检查

#### 1.2 EventService

- **文件**: [EventService.java](producer/src/main/java/com/learning/producer/service/EventService.java)
- **职责**:
  - 创建带有自动生成 UUID 和时间戳的事件
  - 实现基于游标的分页逻辑
  - 计算 `nextCursor` 和 `hasMore` 标志

**核心逻辑**:
```java
public PollResponse pollEvents(int afterIndex, int limit) {
    List<Event> events = eventRepository.getAfter(afterIndex, limit);
    int nextCursor = Math.max(afterIndex, eventRepository.getSize() - 1);

    return PollResponse.builder()
            .events(events)
            .nextCursor(nextCursor)
            .epoch(eventRepository.getEpoch())
            .hasMore(nextCursor < eventRepository.getSize() - 1)
            .build();
}
```

#### 1.3 EventRepository
- **文件**: [EventRepository.java](producer/src/main/java/com/learning/producer/repository/EventRepository.java)
- **存储**: 内存中的 `ArrayList<Event>` (通过 `synchronized` 保证线程安全)
- **职责**:
  - 按时间顺序存储事件(仅追加)
  - 支持基于游标的检索
  - 维护原子事件计数器

**关键特性**:
- ✅ 线程安全 (synchronized 方法)
- ✅ 0索引游标 (游标 5 = 第6个事件)
- ✅ 简单的 ArrayList (无TTL,无淘汰)
- ⚠️ 无限增长 (最终会OOM)

---

### 2. Consumer 服务 (端口 8081)

**职责**: 事件消费、聚合和指标服务

#### 2.1 EventPollingScheduler
- **文件**: [EventPollingScheduler.java](consumer/src/main/java/com/example/consumer/scheduler/EventPollingScheduler.java)
- **调度**: `@Scheduled(fixedDelay = 5000, initialDelay = 2000)`
- **动作**: 每5秒调用一次 `EventConsumerService.pollOnce()`

#### 2.2 EventConsumerService
- **文件**: [EventConsumerService.java](consumer/src/main/java/com/example/consumer/service/EventConsumerService.java)
- **职责**:
  1. 通过HTTP轮询Producer (`GET /v1/events/poll`)
  2. 处理事件 (每个队列 ENQUEUED +1, DEQUEUED -1)
  3. 更新游标并持久化到文件
  4. 计算处理延迟 (事件时间戳 vs 当前时间)

**状态管理**:
```java
// 游标跟踪
private final AtomicInteger lastCursor = new AtomicInteger(-1);
private final AtomicReference<String> lastEpoch = new AtomicReference<>(null);

// 聚合状态
private final Map<String, Integer> eventCountByQueue =
    Collections.synchronizedMap(new HashMap<>());

// 可观测性
private final AtomicLong totalConsumed = new AtomicLong(0);
private final AtomicLong lastLagMillis = new AtomicLong(0);
```

#### 2.3 游标持久化
- **文件**: `data/consumer-cursor.txt`
- **格式**: 纯文本键值对
  ```
  epoch=9b3b0a5d-7b8c-4a88-9f4b-5b3c6d9d2d1f
  cursor=42
  ```
- **兼容**: 旧的纯数字格式仍可读取
- **加载**: 启动时通过 `@PostConstruct`
- **保存**: 每次成功轮询后

**恢复逻辑**:
```java
@PostConstruct
public void initCursor() {
    CursorState state = loadCursorFromFile();
    lastCursor.set(state.cursor);
    lastEpoch.set(state.epoch);
    log.info("Loaded cursor: {}, epoch: {} from {}", state.cursor, state.epoch, cursorFilePath);
}
```

---

## 📊 数据模型

### Event 事件模型

```java
Event {
    eventId: String          // UUID (例如 "123e4567-e89b-12d3-a456-426614174000")
    timestamp: Instant       // 事件发生时间 (ISO-8601)
    eventType: EventType     // ENQUEUED | DEQUEUED
    queueId: String          // 主要聚合维度
    agentId: String          // 次要维度 (可选)
    interactionId: String    // 用于事件关联 (可选)
    payload: Map<String, Object>  // 灵活的元数据 (可选)
}
```

### PollResponse 轮询响应

```java
PollResponse {
    events: List<Event>      // 游标后的事件列表
    nextCursor: int          // 下次使用的游标 (0索引)
    epoch: String            // 事件序列版本标识
    hasMore: boolean         // nextCursor之后是否还有更多事件
}
```

---

## 🔄 数据流与时序

### 1. 事件创建流程

```
客户端 → POST /v1/events
       → EventController.createEvent()
       → EventService.createEvent()
       → Event.create() [生成UUID和时间戳]
       → EventRepository.add()
       → [存储到ArrayList,递增计数器]
       ← 返回Event给客户端
```

### 2. 事件消费流程

```
[每5秒一次]
  EventPollingScheduler.pollProducer()
    → EventConsumerService.pollOnce()
    → RestTemplate.getForObject("http://localhost:8080/v1/events/poll?after={cursor}")
    → Producer返回PollResponse
    → 若epoch变化: 重置cursor与聚合,从新序列开始
    → 对每个Event:
        - 递增totalConsumed
        - 计算延迟 (now - event.timestamp)
        - 如果ENQUEUED: eventCountByQueue[queueId] += 1
        - 如果DEQUEUED: eventCountByQueue[queueId] -= 1
  → 更新 lastCursor = response.nextCursor
  → 保存游标到 data/consumer-cursor.txt
```

### 3. 指标查询流程

```
客户端 → GET /v1/metrics/queues/{queueId}
       → MetricsController.getQueueMetrics()
       → EventConsumerService.getQueueCount(queueId)
       ← 返回 {"queueId": "q1", "waitingCount": 5}
```

---

## 🧠 关键设计决策

### 1. 为什么用HTTP轮询而不是Kafka?

**理由**:
- ✅ **简洁性**: 零外部依赖,专注业务逻辑
- ✅ **学习路径**: 先理解Producer-Consumer模式,再体会Kafka的价值
- ✅ **对比学习**: 切换到Kafka时,痛点会变得非常清晰

**权衡**:
- ❌ **轮询开销**: 即使没有新事件也要发送请求
- ❌ **固定延迟**: 5秒延迟(不是真正的实时)
- ❌ **无推送**: Producer无法通知Consumer
- ❌ **紧耦合**: Consumer需要知道Producer的URL

---

### 2. 为什么使用内存存储?

**理由**:
- ✅ **快速迭代**: 无需数据库配置,即时启动
- ✅ **清晰边界**: 专注于事件流处理,而非持久化
- ✅ **易于演进**: 稍后可以切换到数据库而无需修改API

**权衡**:
- ❌ **易失性**: 重启后数据丢失
- ❌ **无持久化**: 事件仅存在于内存中
- ❌ **无界增长**: 事件过多会导致OOM

**何时切换**:
- 事件数 > 100K
- 需要跨重启的持久化
- 多实例部署

---

### 3. 为什么使用基于文件的游标?

**理由**:
- ✅ **最小复杂度**: 避免Redis/DB依赖
- ✅ **可见性**: `cat data/consumer-cursor.txt` 即可查看状态
- ✅ **学习足够**: 文件I/O足够可靠

**权衡**:
- ❌ **单实例**: 多个Consumer会有文件锁问题
- ❌ **无原子性**: 游标更新与事件处理不是原子操作
- ❌ **无TTL**: 旧游标永久保存
- ?? **序列重置**: Producer重启后epoch变化,Consumer会重置游标与聚合

**何时切换**:
- 多Consumer实例
- 需要分布式协调
- 需要恰好一次语义

---

### 4. 为什么选择5秒轮询间隔?

**理由**:
- ✅ **平衡**: 实时感受 vs 系统负载
- ✅ **可观测**: 足够快以"看到系统呼吸"
- ✅ **可调整**: 通过 `@Scheduled` 注解配置

**影响**:
- ⏱️ **延迟**: 0-5秒的事件到指标延迟
- 📊 **负载**: 每个Consumer 12次请求/分钟(可忽略不计)

---

## 🔍 技术深入探讨

### 游标机制

**问题**: Consumer如何知道"上次处理到哪里"?

**解决方案**: 0索引游标(事件数组中的位置)

**示例**:

```
Producer中的事件:
索引: 0    1    2    3    4
事件: E1   E2   E3   E4   E5

Consumer请求: GET /v1/events/poll?after=1&limit=2
返回: [E3, E4]  (索引2和3)
nextCursor: 3  (最后消费的事件索引)

下次轮询: GET /v1/events/poll?after=3&limit=2
返回: [E5]
nextCursor: 4
hasMore: false
```

**边界情况**:

1. **首次轮询** (`after=-1`): 从索引0开始返回事件
2. **无新事件** (`after=10`, size=10): 返回空列表,nextCursor=10
3. **重启恢复**: 从文件加载游标,从该位置继续

---

### 线程安全

**Producer (EventRepository)**:

```java
public synchronized Event add(Event event) {
    events.add(event);  // 由synchronized保护
    eventCounter.incrementAndGet();  // AtomicLong
    return event;
}

public synchronized List<Event> getAfter(int afterIndex, int limit) {
    // Synchronized确保subList期间的一致性视图
    return new ArrayList<>(events.subList(startIdx, endIdx));
}
```

**Consumer (EventConsumerService)**:

```java
// 整个轮询操作都是同步的
public synchronized void pollOnce() { ... }

// 并发安全的聚合映射
private final Map<String, Integer> eventCountByQueue =
    Collections.synchronizedMap(new HashMap<>());
```

---

### 聚合逻辑

**ENQUEUED 事件** (队列深度 +1):

```java
eventCountByQueue.merge(event.getQueueId(), 1, Integer::sum);
```

**DEQUEUED 事件** (队列深度 -1):

```java
eventCountByQueue.merge(event.getQueueId(), -1, Integer::sum);
```

**查询结果**:

- `queueId="queue-1"`: 5 ENQUEUED, 2 DEQUEUED → count = 3
- `queueId="queue-2"`: 10 ENQUEUED, 10 DEQUEUED → count = 0
- 不存在的队列 → count = 0 (默认值)

---

## 🎯 当前能力

### ✅ 目前可运行的功能

1. **单事件路径**
   - 客户端创建1个事件 → Producer存储 → Consumer在5秒内处理
   - 已通过10+个连续事件验证

2. **游标持久化**
   - Consumer重启 → 从文件加载游标 → 恢复处理
   - 无重复事件处理

3. **实时聚合**
   - ENQUEUED/DEQUEUED事件 → 队列深度指标
   - 指标API返回当前状态

4. **可观测性**
   - 系统统计(已创建事件总数、内存中的事件数)
   - 处理延迟(事件时间戳 vs 消费时间)
   - 已消费事件总数计数器
   - 当前cursor与epoch

5. **全面测试**
   - Producer: 15个单元测试
   - Consumer: 11个单元测试
   - 所有测试通过

---

### ❌ 尚未实现的功能

1. **批量事件**
   - 无 `POST /v1/events:batch` 端点
   - Consumer未针对大批量优化

2. **弹性**
   - 网络故障时无重试
   - 无幂等性(重复事件会被处理两次)
   - 无优雅关闭(游标可能未保存)

3. **高级可观测性**
   - 无每秒事件数指标
   - 无积压大小追踪
   - 无延迟直方图

4. **可扩展性**
   - 内存存储会导致OOM
   - 单Producer实例(无负载均衡)
   - 单Consumer实例(无并行处理)

---

## 🚀 演进路径: HTTP → Kafka

### 当前架构痛点

1. **轮询浪费**: 即使没有新事件,Consumer也要调用Producer
2. **固定延迟**: 总是0-5秒延迟(无法更快)
3. **紧耦合**: Consumer必须知道Producer的URL
4. **无回放**: 无法重新处理旧事件(游标只能前进)
5. **单Consumer**: 无法并行消费

---

### 使用Kafka的未来架构

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /v1/events
       ▼
┌──────────────────────────────┐
│   Producer (Port 8080)       │
│  ┌────────────────────────┐  │
│  │  EventController       │  │
│  └──────────┬─────────────┘  │
│             │                 │
│  ┌──────────▼─────────────┐  │
│  │  KafkaTemplate         │  │
│  │  .send("events-topic") │  │
│  └──────────┬─────────────┘  │
└─────────────┼────────────────┘
              │
              ▼
    ┌─────────────────────┐
    │   Kafka Cluster     │
    │  Topic: events      │
    │  Partitions: 3      │
    │  Replication: 2     │
    └─────────┬───────────┘
              │
              ▼
┌──────────────────────────────┐
│   Consumer (Port 8081)       │
│  ┌────────────────────────┐  │
│  │  @KafkaListener        │  │
│  │  (topics = "events")   │  │
│  └──────────┬─────────────┘  │
│             │                 │
│  ┌──────────▼─────────────┐  │
│  │ EventConsumerService   │  │
│  │ .processEvent(event)   │  │
│  └────────────────────────┘  │
│                               │
│  [Kafka manages offsets]     │
└───────────────────────────────┘
```

### Kafka解决的问题

| 问题 | HTTP轮询 | Kafka |
|------|---------|-------|
| **延迟** | 0-5秒 | < 100ms |
| **轮询浪费** | Consumer轮询空响应 | 基于推送,无浪费 |
| **解耦** | Consumer知道Producer URL | Broker作为中介 |
| **回放** | 无法回放旧事件 | Offset管理,随时回放 |
| **可扩展性** | 单Consumer | 多Consumer(消费者组) |
| **持久性** | 仅内存 | 持久化日志(可配置保留期) |
| **顺序性** | 仅单线程 | 分区级别顺序 |
| **背压** | Consumer必须跟上 | Consumer延迟追踪,暂停/恢复 |

---

### 迁移策略

**阶段1: 添加Kafka (保留HTTP)**

- Producer同时写入Kafka和内存存储
- Consumer同时从HTTP轮询和Kafka读取
- 验证指标一致性

**阶段2: Kafka为主**

- Consumer仅从Kafka读取
- Producer仍暴露HTTP poll端点(用于调试)

**阶段3: 纯Kafka**

- 移除HTTP poll端点
- 移除内存存储
- Producer仅写入Kafka

**阶段4: 高级特性**

- 流处理(Kafka Streams / Flink)
- 多分区并行
- 恰好一次语义

---

## 📈 性能特征

### 当前系统 (HTTP轮询)

**Producer**:

- **吞吐量**: 约1000 events/秒 (受限于ArrayList同步)
- **延迟**: 每个事件创建 < 10ms
- **内存**: 每个事件约1KB (无界增长)

**Consumer**:

- **吞吐量**: 每次轮询约100个事件 (5秒间隔)
- **最大事件速率**: 约20 events/秒 (100事件 / 5秒)
- **延迟**: 0-5秒 (轮询间隔)
- **瓶颈**: 轮询间隔,而非处理速度

**系统限制**:

- ⚠️ **内存**: 约100万事件时会OOM (约1GB堆)
- ⚠️ **Consumer延迟**: 如果Producer创建 > 20 events/秒,Consumer会落后

---

## 🧪 测试策略

### 单元测试 (共26个)

**Producer测试** (15个):

- EventRepository: 线程安全、游标逻辑、分页
- EventService: 事件创建、轮询响应、hasMore标志
- Application: 上下文加载

**Consumer测试** (11个):

- EventConsumerService: 轮询、聚合、游标持久化
- 边界情况: 空响应、null响应、网络错误
- 延迟计算、多次轮询场景

**测试理念**:

- ✅ Mock外部依赖 (RestTemplate, EventRepository)
- ✅ 使用@TempDir进行基于文件的测试
- ✅ Given-When-Then结构
- ✅ 专注业务逻辑,而非Spring配置

---

## 🎓 Learning Value

### Skills Demonstrated

1. **Microservices Architecture**
   - Service decomposition (Producer vs Consumer)
   - REST API design
   - Service-to-service communication

2. **Event-Driven Patterns**
   - Producer-Consumer pattern
   - Cursor-based pagination
   - State management (aggregation)

3. **Real-Time Processing**
   - Scheduled polling
   - Event stream processing
   - Lag tracking

4. **Operational Concerns**
   - Cursor persistence (stateful services)
   - Graceful degradation (network error handling)
   - Observability (metrics, logging)

---

## 📚 Key Files Reference

### Producer Service
- [ProducerApplication.java](producer/src/main/java/com/learning/producer/ProducerApplication.java) - Entry point
- [EventController.java](producer/src/main/java/com/learning/producer/controller/EventController.java) - REST endpoints
- [EventService.java](producer/src/main/java/com/learning/producer/service/EventService.java) - Business logic
- [EventRepository.java](producer/src/main/java/com/learning/producer/repository/EventRepository.java) - In-memory storage
- [Event.java](producer/src/main/java/com/learning/producer/model/Event.java) - Event model
- [pom.xml](producer/pom.xml) - Maven dependencies

### Consumer Service
- [ConsumerApplication.java](consumer/src/main/java/com/example/consumer/ConsumerApplication.java) - Entry point
- [MetricsController.java](consumer/src/main/java/com/example/consumer/controller/MetricsController.java) - Metrics API
- [EventConsumerService.java](consumer/src/main/java/com/example/consumer/service/EventConsumerService.java) - Consumption logic
- [EventPollingScheduler.java](consumer/src/main/java/com/example/consumer/scheduler/EventPollingScheduler.java) - Scheduler
- [pom.xml](consumer/pom.xml) - Maven dependencies

### Documentation
- [HELP.md](producer/HELP.md) - Build plan (Phases 0-8)
- [personal-profile.md](personal-profile.md) - User context
- [fancy-wandering-minsky.md](.claude/plans/fancy-wandering-minsky.md) - Execution plan

---

## 🎯 Next Steps

### Immediate (This Week)
1. **Implement Phase 5**: Batch event endpoint
2. **Stress Test**: Verify 1000 events can be processed
3. **Enhanced Metrics**: Add backlog size, events/sec

### Short-Term (2-4 Weeks)
4. **Introduce Kafka**: Side-by-side with HTTP
5. **Blog Post**: "HTTP Polling to Kafka Evolution"

### Long-Term (1-2 Months)
6. **Cloud Deployment**: AWS ECS / EKS
7. **Stream Processing**: Kafka Streams / Flink
8. **Full Test Coverage**: Integration tests, load tests

---

## 🤝 Career Alignment

**Guidewire → Genesys Transition**:

This project maps directly to real-time analytics requirements:

| Guidewire Skill | This Project | Genesys Relevance |
|----------------|--------------|-------------------|
| Complex business modeling | Event schema design | Real-time data modeling |
| Cloud-native (Guidewire Cloud) | Spring Boot microservices | Cloud-native analytics |
| System integration (ECF) | Producer-Consumer communication | Multi-system data ingestion |
| Metadata-driven (APD) | Event-driven aggregation | Stream processing pipelines |

**Interview Story**:
> "After building complex insurance systems at Guidewire, I wanted to understand real-time data processing. I built an end-to-end analytics system from scratch, starting with HTTP polling to deeply understand the Producer-Consumer pattern, then evolved it to Kafka. The project taught me cursor management, state persistence, backpressure handling, and observability—all critical for real-time systems at scale."

---

*This document is a living architecture reference. Update as the system evolves.*
