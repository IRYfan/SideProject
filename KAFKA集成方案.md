# Kafka 集成方案 - 迷你实时分析系统

*最后更新: 2026-01-18*

---

## 🎯 目标与动机

### 为什么要引入Kafka?

**当前HTTP轮询架构的痛点**:

1. **延迟固定**: 永远是0-5秒延迟,无法更快
2. **资源浪费**: Consumer每5秒都要轮询,即使没有新事件
3. **紧耦合**: Consumer必须知道Producer的URL
4. **无法回溯**: 游标只能前进,无法重新处理历史事件
5. **单Consumer限制**: 无法水平扩展(多个Consumer并行处理)
6. **无持久化**: Producer重启后事件丢失

**Kafka带来的价值**:

1. ✅ **低延迟**: < 100ms的事件传递
2. ✅ **推送模式**: 事件到达即处理,无轮询浪费
3. ✅ **解耦**: Producer和Consumer通过Topic通信,互不知道对方
4. ✅ **持久化**: 事件保存在磁盘(可配置保留期)
5. ✅ **回放能力**: 可以重置offset重新处理历史事件
6. ✅ **水平扩展**: Consumer Group机制支持多Consumer并行
7. ✅ **顺序保证**: 分区内事件有序
8. ✅ **容错**: 副本机制保证高可用

---

## 🏗️ Kafka架构集成方案

### Option 1: 渐进式迁移 (推荐)

**阶段1: 双写模式 (2-3天)**
```
客户端 → Producer
         ├─► Kafka Topic (新增)
         └─► In-Memory Storage (保留)

Consumer ← Kafka (新)
Consumer ← HTTP Poll (保留,用于验证)
```

**阶段2: Kafka主导 (1-2天)**
```
客户端 → Producer → Kafka Topic (主路径)
                  └─► In-Memory (仅用于/stats API)

Consumer ← Kafka (唯一数据源)
```

**阶段3: 纯Kafka (1天)**
```
客户端 → Producer → Kafka Topic (唯一存储)

Consumer ← Kafka
```

**优点**:
- ✅ 逐步验证,风险低
- ✅ 可以对比HTTP vs Kafka的指标差异
- ✅ 出问题可以快速回退

---

### Option 2: 直接切换 (快速但风险高)

直接移除HTTP轮询,全面切换到Kafka

**优点**:
- ✅ 实施快速(1-2天)
- ✅ 代码更简洁

**缺点**:
- ❌ 无法对比验证
- ❌ 出问题难以回退
- ❌ 失去了"对比学习"的机会

**建议**: 学习项目应该选择 **Option 1**,体验架构演进过程

---

## 🔧 技术实现方案

### 1. Kafka环境搭建

#### 1.1 本地开发环境 (Docker Compose)

创建 `docker-compose.yml`:

```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    depends_on:
      - kafka
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
```

**启动**:
```bash
docker-compose up -d
```

**验证**:
- Kafka UI: http://localhost:8090
- Kafka Broker: localhost:9092

---

#### 1.2 创建Topic

```bash
# 进入Kafka容器
docker exec -it <kafka-container-id> bash

# 创建events topic (3个分区,1个副本)
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic events \
  --partitions 3 \
  --replication-factor 1

# 查看topic
kafka-topics --list --bootstrap-server localhost:9092

# 查看topic详情
kafka-topics --describe --bootstrap-server localhost:9092 --topic events
```

**分区策略**:
- **3个分区**: 支持最多3个Consumer并行
- **按queueId分区**: 保证同一队列的事件有序
  - Key = `queueId` → 同一队列进入同一分区 → 消费顺序保证

---

### 2. Producer端改造

#### 2.1 添加依赖 (pom.xml)

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

#### 2.2 配置 (application.properties)

```properties
# Kafka Producer Configuration
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3

# Topic name
kafka.topic.events=events
```

#### 2.3 KafkaProducer封装

创建 `KafkaEventProducer.java`:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProducer {

    private final KafkaTemplate<String, Event> kafkaTemplate;

    @Value("${kafka.topic.events}")
    private String eventsTopic;

    /**
     * Send event to Kafka
     * Key = queueId (for partitioning)
     */
    public void send(Event event) {
        String key = event.getQueueId(); // Partition by queueId

        kafkaTemplate.send(eventsTopic, key, event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to send event to Kafka: eventId={}",
                             event.getEventId(), ex);
                } else {
                    log.debug("Event sent to Kafka: eventId={}, partition={}, offset={}",
                             event.getEventId(),
                             result.getRecordMetadata().partition(),
                             result.getRecordMetadata().offset());
                }
            });
    }
}
```

#### 2.4 修改EventService (阶段1: 双写)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final KafkaEventProducer kafkaProducer; // 新增

    public Event createEvent(EventType eventType, String queueId, String agentId) {
        Event event = Event.create(eventType, queueId, agentId);

        // 写入内存 (保留)
        eventRepository.add(event);

        // 发送到Kafka (新增)
        kafkaProducer.send(event);

        log.info("Event created: id={}, type={}, queueId={}",
                event.getEventId(), eventType, queueId);
        return event;
    }

    // pollEvents() 保持不变,用于验证
}
```

---

### 3. Consumer端改造

#### 3.1 添加依赖 (pom.xml)

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

#### 3.2 配置 (application.properties)

```properties
# Kafka Consumer Configuration
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=event-consumer-group
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false

# JSON deserializer config
spring.kafka.consumer.properties.spring.json.trusted.packages=*

# Topic name
kafka.topic.events=events
```

**关键配置**:
- `group-id=event-consumer-group`: Consumer组ID,支持多实例
- `auto-offset-reset=earliest`: 第一次启动从头消费
- `enable-auto-commit=false`: 手动提交offset(更可靠)

#### 3.3 创建KafkaEventListener

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventListener {

    private final EventConsumerService consumerService;

    @KafkaListener(
        topics = "${kafka.topic.events}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(
        @Payload Event event,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        Acknowledgment acknowledgment
    ) {
        try {
            log.info("Received event from Kafka: eventId={}, partition={}, offset={}",
                    event.getEventId(), partition, offset);

            // 处理事件 (复用现有逻辑)
            consumerService.processEventFromKafka(event);

            // 手动提交offset
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process event: eventId={}", event.getEventId(), e);
            // 可以选择: 重试 / 发送到DLQ / 记录错误日志
        }
    }
}
```

#### 3.4 修改EventConsumerService

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EventConsumerService {

    // 保留现有字段...
    private final Map<String, Integer> eventCountByQueue =
        Collections.synchronizedMap(new HashMap<>());

    /**
     * 新增: 处理来自Kafka的事件
     */
    public void processEventFromKafka(Event event) {
        totalConsumed.incrementAndGet();

        // 计算延迟
        if (event.getTimestamp() != null) {
            long lagMs = Math.max(0,
                Instant.now().toEpochMilli() - event.getTimestamp().toEpochMilli());
            lastLagMillis.set(lagMs);
        }

        // 聚合逻辑
        if (event.getEventType() == EventType.ENQUEUED) {
            eventCountByQueue.merge(event.getQueueId(), 1, Integer::sum);
            log.debug("ENQUEUED event for queue: {}", event.getQueueId());
        } else if (event.getEventType() == EventType.DEQUEUED) {
            eventCountByQueue.merge(event.getQueueId(), -1, Integer::sum);
            log.debug("DEQUEUED event for queue: {}", event.getQueueId());
        }
    }

    /**
     * 保留: HTTP轮询方式 (阶段1验证用)
     */
    public synchronized void pollOnce() {
        // 保持不变,用于对比
    }
}
```

#### 3.5 配置类

创建 `KafkaConsumerConfig.java`:

```java
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, Event> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                  StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                  JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(config,
            new StringDeserializer(),
            new JsonDeserializer<>(Event.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Event>
           kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Event> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
```

---

## 📊 阶段1验证方案 (双写双读)

### 验证目标

证明Kafka和HTTP两种方式的**指标一致性**

### 验证步骤

**1. 启动系统**:
```bash
# 启动Kafka
docker-compose up -d

# 启动Producer (双写)
cd producer && ./mvnw spring-boot:run

# 启动Consumer (双读)
cd consumer && ./mvnw spring-boot:run
```

**2. 发送测试事件**:
```bash
# 发送10个ENQUEUED事件到queue-1
for i in {1..10}; do
  curl -X POST http://localhost:8080/v1/events \
    -H "Content-Type: application/json" \
    -d '{
      "eventType": "ENQUEUED",
      "queueId": "queue-1",
      "agentId": "agent-1"
    }'
  sleep 0.1
done

# 发送3个DEQUEUED事件
for i in {1..3}; do
  curl -X POST http://localhost:8080/v1/events \
    -H "Content-Type: application/json" \
    -d '{
      "eventType": "DEQUEUED",
      "queueId": "queue-1",
      "agentId": "agent-1"
    }'
  sleep 0.1
done
```

**3. 查看Kafka消费情况**:

Consumer日志应显示:
```
Received event from Kafka: eventId=xxx, partition=0, offset=0
Received event from Kafka: eventId=yyy, partition=0, offset=1
...
```

**4. 查询指标**:
```bash
curl http://localhost:8081/v1/metrics/queues/queue-1
# 预期: {"queueId":"queue-1","count":7}  (10 - 3)
```

**5. 对比延迟**:

在Consumer日志中对比:
- HTTP轮询: `lastLagMs` 应该在 0-5000 之间
- Kafka推送: `lastLagMs` 应该在 0-100 之间

**6. 验证Kafka UI**:

访问 http://localhost:8090:
- 查看Topic `events` 有13条消息
- 查看Consumer Group `event-consumer-group` 的offset
- 确认无lag (offset已追上)

---

## 🎯 阶段2: Kafka主导

**目标**: Consumer只从Kafka读取,HTTP poll仅保留用于调试

### 改动

1. **禁用HTTP轮询调度器**:

```java
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class EventPollingScheduler {

    // 注释掉@Scheduled,停止自动轮询
    // @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void pollProducer() {
        // 保留方法,手动调用用于调试
        log.debug("HTTP polling disabled, using Kafka");
    }
}
```

2. **验证**:

发送事件后,Consumer只应该通过Kafka接收:
```
# Consumer日志应该只有:
Received event from Kafka: eventId=xxx
# 不应该有:
Polling producer at: http://localhost:8080/v1/events/poll
```

---

## 🎯 阶段3: 纯Kafka

**目标**: 移除所有HTTP轮询代码和内存存储

### 改动

**Producer端**:

1. 移除 `EventRepository` (或改为Optional)
2. `EventService.createEvent()` 只发送到Kafka
3. `/v1/events/poll` 端点返回 HTTP 410 Gone

```java
@GetMapping("/poll")
public ResponseEntity<String> pollEvents() {
    return ResponseEntity.status(HttpStatus.GONE)
        .body("Polling API deprecated. Use Kafka consumer.");
}
```

**Consumer端**:

1. 删除 `EventPollingScheduler`
2. 删除 `pollOnce()` 方法
3. 删除游标文件逻辑 (Kafka自动管理offset)

---

## 📈 性能对比

### HTTP轮询 vs Kafka

| 指标 | HTTP轮询 | Kafka |
|------|---------|-------|
| **端到端延迟** | 0-5秒 (平均2.5秒) | < 100ms |
| **吞吐量** | ~20 events/sec (受限于5秒间隔) | 1000+ events/sec |
| **资源利用** | 定期轮询 (浪费CPU) | 事件驱动 (高效) |
| **可扩展性** | 单Consumer | Consumer Group (3个并发) |
| **持久化** | 内存 (易丢失) | 磁盘 (可配置保留期) |
| **回放** | 不支持 | 支持 (重置offset) |
| **背压处理** | Consumer必须跟上 | 可以暂停/恢复消费 |

---

## 🧪 测试策略

### 单元测试

**KafkaEventProducer测试**:
```java
@ExtendWith(MockitoExtension.class)
class KafkaEventProducerTest {

    @Mock
    private KafkaTemplate<String, Event> kafkaTemplate;

    @InjectMocks
    private KafkaEventProducer producer;

    @Test
    void shouldSendEventWithQueueIdAsKey() {
        // Given
        Event event = Event.create(EventType.ENQUEUED, "queue-1", "agent-1");
        when(kafkaTemplate.send(anyString(), anyString(), any(Event.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        // When
        producer.send(event);

        // Then
        verify(kafkaTemplate).send(
            eq("events"),
            eq("queue-1"),  // Key should be queueId
            eq(event)
        );
    }
}
```

### 集成测试 (使用EmbeddedKafka)

```java
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"events"}
)
class KafkaIntegrationTest {

    @Autowired
    private KafkaEventProducer producer;

    @Autowired
    private EventConsumerService consumerService;

    @Test
    void shouldProduceAndConsumeEvent() throws Exception {
        // Given
        Event event = Event.create(EventType.ENQUEUED, "queue-1", "agent-1");

        // When
        producer.send(event);

        // Then: wait for async consumption
        Thread.sleep(1000);
        assertThat(consumerService.getTotalConsumed()).isEqualTo(1);
        assertThat(consumerService.getQueueCount("queue-1")).isEqualTo(1);
    }
}
```

---

## 🚨 注意事项

### 1. JSON序列化配置

**问题**: Kafka默认不信任所有包,反序列化会失败

**解决**:
```properties
spring.kafka.consumer.properties.spring.json.trusted.packages=*
```

或更安全的方式:
```properties
spring.kafka.consumer.properties.spring.json.trusted.packages=com.learning.producer.model,com.example.consumer.model
```

---

### 2. Offset管理

**自动提交 vs 手动提交**:

| 方式 | 优点 | 缺点 |
|------|------|------|
| 自动提交 | 简单,无需代码 | 可能丢失事件 (消费失败但已提交) |
| 手动提交 | 精确控制,不丢失 | 需要处理acknowledge逻辑 |

**推荐**: 手动提交 (学习项目应该理解offset机制)

```java
@KafkaListener(...)
public void listen(Event event, Acknowledgment ack) {
    try {
        processEvent(event);
        ack.acknowledge();  // 只有成功才提交
    } catch (Exception e) {
        // 不提交,下次重新消费
        log.error("Processing failed", e);
    }
}
```

---

### 3. 幂等性

**问题**: Kafka可能重复投递事件 (网络抖动、重平衡等)

**解决**: 在Consumer端实现幂等性

```java
private final Set<String> processedEventIds =
    Collections.synchronizedSet(new HashSet<>());

public void processEventFromKafka(Event event) {
    // 检查是否已处理
    if (processedEventIds.contains(event.getEventId())) {
        log.warn("Duplicate event ignored: {}", event.getEventId());
        return;
    }

    // 处理事件
    processEvent(event);

    // 记录已处理
    processedEventIds.add(event.getEventId());

    // 可选: 定期清理旧ID (避免内存泄漏)
}
```

---

### 4. 错误处理

**策略1: 重试**
```java
@KafkaListener(...)
public void listen(Event event, Acknowledgment ack) {
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        try {
            processEvent(event);
            ack.acknowledge();
            return;
        } catch (Exception e) {
            log.warn("Retry {}/{} failed", i+1, maxRetries, e);
            Thread.sleep(1000);
        }
    }
    // 所有重试失败 → 发送到DLQ
    sendToDeadLetterQueue(event);
}
```

**策略2: Dead Letter Queue (DLQ)**
```java
private void sendToDeadLetterQueue(Event event) {
    kafkaTemplate.send("events-dlq", event.getEventId(), event);
    log.error("Event sent to DLQ: {}", event.getEventId());
}
```

---

## 📚 学习资源

### Kafka核心概念

1. **Topic**: 事件分类 (本项目: `events`)
2. **Partition**: Topic的物理分割 (本项目: 3个分区)
3. **Offset**: 分区内的消息位置 (类似HTTP的cursor)
4. **Consumer Group**: 多Consumer协同消费 (本项目: `event-consumer-group`)
5. **Rebalance**: Consumer加入/离开时重新分配分区

### 推荐阅读

- [Spring Kafka官方文档](https://docs.spring.io/spring-kafka/reference/)
- [Kafka入门教程](https://kafka.apache.org/quickstart)
- [Kafka最佳实践](https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html)

---

## 🎓 面试话术

**问题**: "你为什么选择Kafka而不是RabbitMQ?"

**回答**:
> "我最初用HTTP轮询实现了Producer-Consumer模式,深刻体会到轮询的延迟和资源浪费。选择Kafka因为:
> 1. **日志结构**: Kafka的append-only log天然适合事件流
> 2. **高吞吐**: 我的场景需要处理1000+ events/sec
> 3. **持久化**: 需要重放历史事件进行回测
> 4. **分区**: 按queueId分区保证同一队列事件有序
> 5. **生态**: 后续可以接入Kafka Streams做流处理
>
> RabbitMQ更适合任务队列(work queue)场景,而我的项目是事件流分析,Kafka更契合。"

---

**问题**: "如何保证Kafka消费的exactly-once语义?"

**回答**:
> "Kafka本身支持幂等性Producer和事务性写入,但Consumer端需要应用层实现:
> 1. **手动提交offset**: 只有成功处理后才acknowledge
> 2. **幂等性处理**: 用eventId去重 (Set<String> processedEventIds)
> 3. **事务性写入**: 如果聚合结果写入DB,需要offset和业务逻辑在同一事务
>
> 我的项目是内存聚合,用Set去重足够。如果写入DB,会用Spring的@Transactional配合手动offset提交。"

---

## 🎯 成功标准

Kafka集成完成的验收标准:

1. ✅ **功能**: 发送事件到Producer → Kafka → Consumer接收 → 指标更新
2. ✅ **延迟**: 端到端延迟 < 500ms (对比HTTP的0-5秒)
3. ✅ **吞吐**: 支持100 events/sec稳定处理
4. ✅ **持久化**: Consumer重启后从上次offset继续消费
5. ✅ **幂等性**: 重复事件不影响聚合准确性
6. ✅ **可观测**: Kafka UI能看到消息流转和Consumer lag

---

*这份文档会随着Kafka集成进度更新*
