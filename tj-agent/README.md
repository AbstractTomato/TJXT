# 🦉 天机 Agent 开发备忘录

> 最后更新：2026-07-22（Phase 5 对接与收尾已完成）

---

## 一、当前状态

### 1.1 已完成

| 阶段 | 事项 | 状态 |
|------|------|------|
| Phase 1 | 模块搭建、LLM 对接、SSE 骨架 | ✅ |
| **Phase 2** | **知识库构建（ingestion + 表 + 索引 + 定时任务）** | ✅ **已完成** |
| **Phase 3** | **检索管线（向量 + 关键词 + RRF 融合）** | ✅ **已完成** |
| **Phase 4** | **对话生成（RAG 管线：检索 → Prompt → LLM 流式 → SSE）** | ✅ **已完成** |
| **Phase 5** | **对接与收尾（sessionId下发 + 自信度Prompt + 自动建Question + 健康检查）** | ✅ **已完成** |
| Phase 6 | 优化 | 🔲 待开发 |

### 1.2 部署环境

| 组件 | 地址 | 说明 |
|------|------|------|
| 虚拟机 | 192.168.150.101 | VMware + Docker 20.10.8 |
| tj-agent | 192.168.150.101:8100 | JDK 17 自建镜像，3GB 内存 |
| MySQL | 192.168.150.101:3306 | root / 123 |
| Redis | 192.168.150.101:6379 | 密码 123321 |
| Elasticsearch | 192.168.150.101:9200 | 7.12.1 |
| Nacos | 192.168.150.101:8848 | 2.1.0-slim（嵌入式 Derby） |
| XXL-Job | 192.168.150.101:8880 | 2.3.0 |

### 1.3 重要链接

- **Nacos 控制台**：http://192.168.150.101:8848/nacos（nacos/nacos）
- **XXL-Job 管理后台**：http://192.168.150.101:8880/xxl-job-admin（admin/123456）
- **Agent API**：`POST http://192.168.150.101:8100/agent/chat/stream`
- **问题记录文档**：`docs/phase2-issues-and-fixes.md`

---

## 二、当前目录结构

```
tj-agent/
├── pom.xml                                         # JDK 11 编译目标，JDK 17 运行
│                                                   # 依赖：langchain4j 0.36.2、ES 7.12.1、MyBatis-Plus
├── Dockerfile                                      # JDK 17 自建镜像（基于 openjdk:11 + 下载的 JDK 17 JRE）
└── src/main/
    ├── java/com/tianji/agent/
    │   ├── AgentApplication.java                   # 启动类，@EnableFeignClients + @MapperScan + @EnableScheduling
    │   ├── config/
    │   │   ├── AgentConfig.java                    # LLM 配置：OpenAiStreamingChatModel + BgeSmallZhEmbeddingModel（本地 768 维）
    │   │   └── EmbeddingWarmup.java                # 启动时预热 BGE 模型（避免首次调用超时）
│   │   │   └── StartupHealthCheck.java             # ES/Redis 启动连通性检查（Phase 5）
    │   ├── controller/
    │   │   └── ChatController.java                 # POST /agent/chat/stream (SSE)
    │   ├── service/
    │   │   ├── ChatService.java                    # 对话服务接口
│   │   ├── RetrievalService.java               # ★ 检索管线接口（Phase 3）
│   │   ├── TermAliasService.java               # 术语标准化（Redis Hash）
│   │   │   ├── PromptBuilder.java                  # System Prompt 模板构造器（Phase 4）
│   │   │   ├── ConversationHistoryService.java     # 多轮对话历史管理（Phase 4）
    │   │   ├── IngestionService.java               # 知识库入库接口
    │   │   └── impl/
    │   │       ├── ChatServiceImpl.java            # ★ 完整 RAG 流式对话（Phase 4 重写）
│   │   │       └── RetrievalServiceImpl.java       # ★ 检索管线实现（向量+关键词+RRF+缓存）
    │   │       └── IngestionServiceImpl.java       # ★ 核心入库逻辑（章节种子 + QA 问答 → 向量化 → MySQL + ES dense_vector）
    │   ├── mapper/
    │   │   └── DocChunkMapper.java                 # MyBatis-Plus Mapper
    │   ├── repository/
    │   │   └── KnowledgeEsRepository.java          # ES 操作（写入+向量检索+关键词检索+删除）
    │   ├── handler/
    │   │   └── ContentSyncHandler.java             # XXL-Job：syncKnowledgeBase + syncNewQA
    │   ├── domain/
    │   │   ├── dto/
    │   │   │   ├── ChatRequest.java                # {question, courseId, sessionId}
    │   │   │   └── ChatChunk.java                  # SSE 响应片段（含 sessionId）
│   │   │   │   └── RetrievalResult.java            # 检索结果 DTO（DocChunk + RRF 分数）
    │   │   ├── enums/
    │   │   │   └── Confidence.java                 # HIGH / MEDIUM / LOW
    │   │   └── po/
    │   │       └── DocChunk.java                   # 知识库文档块实体
    └── resources/
        ├── bootstrap.yml                           # 主配置
        ├── bootstrap-dev.yml                       # VM 部署环境（Nacos + MySQL + Redis + ES）
        └── bootstrap-local.yml                     # 本地开发环境
```

### 新增文件（Phase 2 实施过程中创建）

| 文件 | 模块 | 说明 |
|------|------|------|
| `IngestionServiceImpl.java` | tj-agent | 核心入库逻辑 |
| `ChatServiceImpl.java` | tj-agent | 对话占位实现 |
│   │   │       └── RetrievalServiceImpl.java       # ★ 检索管线实现（向量+关键词+RRF+缓存）
| `DocChunkMapper.java` | tj-agent | MyBatis-Plus Mapper |
| `KnowledgeEsRepository.java` | tj-agent | ES 写入仓库 |
| `Dockerfile` | tj-agent | JDK 17 镜像 |
| `QuestionDTO.java` | tj-api | Feign 通信 DTO |
| `ReplyDTO.java` | tj-api | Feign 通信 DTO |
| `agent_doc_chunk.sql` | docs/sql | 建表 SQL |
| `phase2-issues-and-fixes.md` | docs | 问题记录文档 |

### 新增文件（Phase 3 检索管线）

| 文件 | 模块 | 说明 |
|------|------|------|
| `RetrievalService.java` | tj-agent | 检索管线接口 |
| `RetrievalServiceImpl.java` | tj-agent | 核心检索实现（术语标准化→embedding缓存→向量+关键词→RRF） |
| `TermAliasService.java` | tj-agent | 术语标准化（ES IK 分词 → Redis Hash 映射） |

### 新增文件（Phase 4 对话生成）

| 文件 | 模块 | 说明 |
|------|------|------|
| `RetrievalResult.java` | tj-agent | 检索结果 DTO（DocChunk + RRF 分数） |
| `PromptBuilder.java` | tj-agent | System Prompt 模板构造器 |
| `ConversationHistoryService.java` | tj-agent | 多轮对话历史（Redis List，滑动窗口最近 5 轮） |

### 新增文件（Phase 5 对接与收尾）

| 文件 | 模块 | 说明 |
|------|------|------|
| `QuestionFormDTO.java` | tj-api | Agent 专用问题表单（courseId + title + description） |
| `StartupHealthCheck.java` | tj-agent | ES/Redis 启动连通性检查（WARN 不阻断启动） |

### 已修改的已有文件

| 文件 | 变更内容 |
|------|---------|
| `ChatServiceImpl.java` | Phase 4 重写：占位 → 完整 RAG 流式对话 |
│   │   │       └── RetrievalServiceImpl.java       # ★ 检索管线实现（向量+关键词+RRF+缓存）
| `AgentConfig.java` | BGE 本地模型替换 OpenAiEmbeddingModel；新增 OpenAiStreamingChatModel Bean |
| `RetrievalService.java` | 返回类型 DocChunk → RetrievalResult（含 RRF 分数） |
| `RetrievalServiceImpl.java` | 适配 RetrievalResult 返回类型 |
| `AgentApplication.java` | 添加 @EnableFeignClients |
| `LearningClient.java` | 新增 3 个查询方法 + createQuestion（Phase 5） |
| `LearningClientFallback.java` | 新增降级方法（含 createQuestion） |
| `ContentSyncHandler.java` | 实现定时任务逻辑 |
| `KnowledgeEsRepository.java` | 新增 searchByKeyword() 关键词检索方法 |
| `pom.xml` (tj-agent) | 移除 starter、添加 ES 依赖 |
| `bootstrap.yml` | 添加 allow-bean-definition-overriding + swagger 修复 |
| `bootstrap-dev.yml` | 修正 Nacos/MySQL/Redis 地址 |
| `bootstrap-local.yml` | 添加 ES uris + 同步配置 |
| `DocChunk.java` | 更新 sourceType 注释；标记 vectorKey @Deprecated |
| `ChatChunk.java` | Phase 5 新增 sessionId 字段 |
│   │   │   │   └── RetrievalResult.java            # 检索结果 DTO（DocChunk + RRF 分数）
| `PromptBuilder.java` | Phase 5：接收 Confidence，不同级别注入不同回答策略 |
| `SimpleRedisVectorStore.java` | 已删除（向量存储迁移至 ES dense_vector） |
| `create_es_index.sh` | 新增 dense_vector embedding 字段 |

---

## 三、Phase 2 实施方案

### 3.1 知识库数据源

由于项目中**不存在字幕功能**，实际使用的数据源为：

| 数据源 | 来源 | sourceType | 说明 |
|--------|------|------------|------|
| 课程章节名 | `CourseClient.getCourseInfoById()` → `CatalogueDTO` | `CHAPTER` | 冷启动种子数据 |
| 问答对 | `LearningClient.queryQuestionPage()` + `queryReplyPage()` | `QA` | 主要知识来源 |

### 3.2 文本分块策略

- 短文本（< 800 字）：不分块，整体存入
- 长文本（> 800 字）：按段落边界切分，500~800 字/块，重叠 100 字
- QA 分块遵循 Q-Only Embedding 设计：问题作为上下文头，回答按段落拆分

### 3.3 数据流

```
ingestCourse(courseId):
  ① CourseClient.getCourseInfoById(courseId, true, false)
     → 课程名 + 章节列表 → 种子数据块（CHAPTER）
  ② LearningClient.queryQuestionPage(courseId, 1, 100)
     → 该课程下所有问答 → QA 数据块
  ③ 文本分块（500~800 字）
  ④ BGE-small-zh 向量化（768 维）
  ⑤ 存储：MySQL 元数据 + ES（关键词索引 + dense_vector 向量）

ingestQA(questionId):
  单条增量入库（讲师回答后触发），流程同上
```

### 3.4 关键设计决策

| 决策 | 原因 |
|------|------|
| **ES dense_vector 向量存储** | 利用已有 ES 7.12.1 的 dense_vector 字段，无需额外组件 |
| **本地 BGE 模型** | 无需网络，768 维中文向量 |
| **Nacos 使用嵌入式 Derby** | MySQL 8 认证插件与 Nacos 内置 JDBC 驱动不兼容 |
| **JDK 17 自建镜像** | VM 无 JDK 17 镜像且无法拉取 Docker Hub |
| **Q-Only Embedding** | 只向量化提问，回答/评论存 MySQL 作为 Payload |
| **环境变量直连中间件** | 当 Nacos 不可用时的降级方案 |

---

## 四、API 接口

### 4.1 对话接口

```
POST /agent/chat/stream
Content-Type: application/json

{
  "question": "Spring Boot 怎么配置多数据源？",
  "courseId": 1,
  "sessionId": "abc123"  // 可选，多轮对话
}

Response (SSE):
data:{"messageId":"xxx","sessionId":"abc123","content":"回答片段","finished":false}
data:{"messageId":"xxx","sessionId":"abc123","content":"","finished":true,"confidence":"HIGH","sources":"[...]"}
```

### 4.2 Feign 接口（tj-api）

**LearningClient** 新增方法：
```java
// 分页查询互动问题
PageDTO<QuestionDTO> queryQuestionPage(Long courseId, Integer pageNo, Integer pageSize);

// 根据 ID 查询问题
QuestionDTO queryQuestionById(Long id);

// 分页查询回答
PageDTO<ReplyDTO> queryReplyPage(Long questionId, Integer pageNo, Integer pageSize);
```

---

## 五、数据库与索引

### 5.1 MySQL 表

```sql
-- tj_agent.agent_doc_chunk
CREATE TABLE agent_doc_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    content       TEXT         NOT NULL,
    course_id     BIGINT       NOT NULL,
    course_name   VARCHAR(255),
    chapter_title VARCHAR(255),
    source_type   VARCHAR(32),   -- CHAPTER / QA
    source_id     BIGINT,
    vector_key    VARCHAR(255),
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_course_id (course_id),
    INDEX idx_source_type (source_type),
    UNIQUE INDEX idx_source (source_type, source_id)
);
```

### 5.2 ES 索引

```json
PUT /agent_knowledge
{
  "mappings": {
    "properties": {
      "id":           { "type": "long" },
      "content":      { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "courseId":     { "type": "keyword" },
      "courseName":   { "type": "text", "analyzer": "ik_smart" },
      "chapterTitle": { "type": "text", "analyzer": "ik_smart" },
      "sourceType":   { "type": "keyword" },
      "sourceId":     { "type": "long" },
      "embedding":    { "type": "dense_vector", "dims": 768 },
      "createTime":   { "type": "date", "format": "yyyy-MM-dd HH:mm:ss" }
    }
  }
}
```

### 5.3 ES dense_vector 向量存储

```
ES 字段：  embedding (dense_vector, dims: 768)
检索方式： script_score + cosineSimilarity Painless 函数
存储位置： agent_knowledge 索引，与关键词索引合并存储
```

### 5.4 XXL-Job 定时任务

| 任务 | Cron | 说明 |
|------|------|------|
| `syncKnowledgeBase` | `0 0 3 * * ?` | 每天凌晨 3 点全量同步 |
| `syncNewQA` | `0 */10 * * * ?` | 每 10 分钟增量同步 |

---

## 六、Nacos 配置

### 6.1 共享配置

| Data ID | Group | 内容 |
|---------|-------|------|
| `shared-redis.yaml` | DEFAULT_GROUP | Redis 连接（host/port/password） |
| `shared-mybatis.yaml` | DEFAULT_GROUP | MyBatis-Plus 配置 |
| `shared-xxljob.yaml` | DEFAULT_GROUP | XXL-Job Admin 地址 + Executor 配置 |

### 6.2 应用专属配置

| Data ID | Group | 关键配置 |
|---------|-------|---------|
| `agent-service-dev.yaml` | DEFAULT_GROUP | `tj.agent.sync.course-ids: 1,2,3` |

在 Nacos 中修改 `agent-service-dev.yaml` 的 `course-ids` 为实际课程 ID，然后手动触发一次 `syncKnowledgeBase` 即可开始首次入库。

---

## 七、编译与部署

### 7.1 本地编译

```bash
# 需要 JDK 17
export JAVA_HOME=~/.jdks/ms-17.0.18

# 先编译 tj-api
mvn install -pl tj-api -DskipTests

# 编译 tj-agent
mvn package -pl tj-agent -DskipTests
# 输出：tj-agent/target/tj-agent.jar (~283MB)
```

### 7.2 部署到虚拟机

```bash
# 1. 创建部署 tar（jar + JDK 17 JRE）
# 2. 通过 Docker API 上传到 VM
# 3. 构建镜像并启动

# 容器启动参数：
docker run -d --name tj-agent \
  --network heima-net \
  -p 8100:8100 \
  -e DEEPSEEK_API_KEY=sk-xxx \
  -e JAVA_TOOL_OPTIONS="-Xmx2g -Xms512m" \
  --memory 3g \
  tj-agent:latest
```

### 7.3 Docker 镜像结构

```
FROM openjdk:11.0-jre-buster
  → 下载 JDK 17 JRE（45MB）from 清华镜像
  → 解压到 /opt/java/jdk-17.0.19+10-jre
  → 设置 JAVA_HOME + PATH
  → 启动 java -jar /app/app.jar
```

---

## 八、关键问题与修复

Phase 2 实施过程中遇到并修复了 **13 个问题**，详见 `docs/phase2-issues-and-fixes.md`。

### 主要问题摘要

| # | 问题 | 根因 | 解决方案 |
|---|------|------|---------|
| 1 | langchain4j-starter 不兼容 | Spring Boot 2.7 vs 3.x API 差异 | 移除 starter，手动配置 |
| 2 | BGE 类路径错误 | 包名含 `onnx.bgesmallzh` 前缀 | 修正 import |
| 3 | RedisBuilder API 不匹配 | Builder 类名 + 参数类型不对 | 查看 JAR 确认 API |
| 4 | Redis 无 RediSearch | VM 无法拉取 redis-stack | 自建 SimpleRedisVectorStore → 最终迁移至 ES dense_vector |
| 5 | BGE OOM | 模型需要 2GB+ 堆内存 | 容器 3GB + JVM -Xmx2g |
| 6 | JDK 17 不可用 | VM 仅 openjdk:11 | 清华镜像下载 + 自建镜像 |
| 7 | Feign 重复注册 | auth-sdk 定义同名 Client | allow-bean-definition-overriding |
| 8 | 缺少 @EnableFeignClients | AgentApplication 未扫描 | 添加注解 |
| 9 | Swagger 异常 | Springfox 路径策略不兼容 | ant_path_matcher |
| 10 | Docker 端口未映射 | 缺少 ExposedPorts | 添加配置 |
| 11 | Nacos 无法启动 | MySQL 8 认证 + SQL 不兼容 | 重建为嵌入式 Derby |
| 12 | XXL-Job 表缺失 | DB 未初始化 | 手动创建表结构 |
| 13 | MySQL 跨网络不通 | bridge vs heima-net | 连接 MySQL 到 heima-net |

---

## 九、Phase 3 检索管线（✅ 已完成）

### 已实现

1. **RetrievalService + RetrievalServiceImpl**：混合检索（向量 + ES 关键词 → RRF 融合排序）
2. **向量检索**：ES `dense_vector` + `cosineSimilarity` Painless 函数（`KnowledgeEsRepository.searchByVector()`）
3. **ES 关键词检索**：`matchQuery("content")` 利用 IK 分词实现中文关键词搜索（`KnowledgeEsRepository.searchByKeyword()`）
4. **检索缓存**：Redis 缓存 embedding 向量（`agent:embedding:{md5(q)}`），Base64 编码，TTL 1 小时
5. **术语标准化**：`TermAliasService` 从 Redis Hash `agent:term:alias` 读取映射，未命中原样返回（骨架已就绪，待填充数据）

### 架构

```
问题 → 术语标准化 → BGE向量化(缓存) → ┬─ 向量检索(ES cosineSimilarity) ─┐
                                         └─ 关键词检索(ES match+IK) ──┘
                                         → RRF融合(k=60) → topK DocChunk
```

### 新增文件

- `RetrievalService.java` — 检索管线接口
- `RetrievalServiceImpl.java` — 核心检索实现
- `TermAliasService.java` — 术语标准化（Redis Hash 骨架）

### 关键设计决策

| 决策 | 原因 |
|------|------|
| **ES 直连而非 Feign 调 tj-search** | tj-search 的 SearchClient 只能搜课程名，无法检索 agent_knowledge 索引内容 |
| **RRF k=60** | 学术标准参数，免归一化调参，向量和关键词评分量纲不同时仍能合理融合 |
| **仅缓存 embedding，不缓存检索结果** | 知识库会更新，缓存搜索结果会返回过期数据；embedding 是纯计算，缓存安全 |
| **并行检索独立容错** | 向量或关键词任意一路失败，另一路结果仍可用（`getQuietly` 降级） |
| **术语标准化：IK 分词 + Redis Hash** | ES `_analyze` API 调 ik_smart 切词，逐词查 Redis Hash，与搜索端同分词器保证一致；分词失败降级原问题 |

### ⚠️ 术语标准化的实现与优化方向

**当前实现**（TermAliasService）：

```
用户问题 → ES IK 分词（ik_smart）→ 逐个 token 查 Redis Hash → 命中替换 → 拼回
   "SpringBoot怎么配置多数据源"
     ↓ ik_smart
   ["SpringBoot", "怎么", "配置", "多数据源"]
     ↓ HGET agent:term:alias (逐个)
   "SpringBoot" → "Spring Boot"（命中）
   "多数据源"   → "多数据源配置"（命中）
   其他          → 保留原词
     ↓
   "Spring Boot 怎么 配置 多数据源配置"
```

用 ES `_analyze` API 调 IK 分词器，与搜索端使用**同一分词器**，保证切词一致。
无需新增依赖（`RestHighLevelClient` 已有）。
分词失败时降级返回原问题，不影响检索主流程。

**后续升级方向（Phase 6）**：

这只能匹配"用户输入恰好等于某个术语 key"的场景，实际意义不大。
真正有用的术语标准化需要对问题中的**每个子串**做匹配（如 "SpringBoot怎么配置多数据源" → 识别 "SpringBoot" 和 "多数据源" 两个术语）。

**推荐升级方案（Phase 6）：ES IK Synonym 词典**

在 ES 端配置 IK 同义词词典，让检索端自动扩展同义词，无需 Java 端预处理：

```bash
# ES 配置文件 elasticsearch/config/analysis-ik/synonyms.txt
SpringBoot => Spring Boot
多数据源 => 多数据源配置
```

然后 IK 分词时自动将 "SpringBoot" 扩展为 "Spring Boot" 去搜索。
优点：改动最小（只改 ES 配置，不改代码），零延迟成本，删掉 TermAliasService 即可。

**备选方案：AC 自动机（Aho-Corasick）**

如果未来术语量很大（> 1 万），可以从 Redis 加载全量术语到内存构建 AC 自动机，
对用户问题做单次扫描 O(n) 完成全部术语匹配。
10 万术语的 Trie 树约几 MB 内存，完全够用。

三种方案的对比：

| | Redis Hash HGET | ES IK Synonym | AC 自动机 | LLM 改写 |
|---|---|---|---|---|
| **延迟** | < 1ms | 0（检索时自动做） | < 0.1ms | 500ms~2s |
| **成本** | 零 | 零 | 零 | 每次消耗 token |
| **确定性** | 100% | 100% | 100% | 可能不稳定 |
| **大词典性能** | 差（需多次 HGET） | 好 | 好（O(n) 扫描） | 差（prompt 装不下） |
| **推荐阶段** | 当前骨架 | **Phase 6 首选** | Phase 6 备选 | 不推荐 |

---

## 十、Phase 4 对话生成（✅ 已完成）

### 数据流

```
POST /agent/chat/stream {question, courseId, sessionId}
    │
    ▼
ChatServiceImpl.chat()
    │
    ├─ 1. RetrievalService.retrieve(question, courseId, topK=5)
    │     → List<RetrievalResult>（DocChunk + RRF score）
    │
    ├─ 2. ConversationHistoryService.getHistory(sessionId)
    │     → 最近 5 轮对话（Redis List，滑动窗口）
    │
    ├─ 3. PromptBuilder.build(chunks, history, question)
    │     → System Prompt（指令 + 参考资料 + 历史 + 问题）
    │
    ├─ 4. OpenAiStreamingChatModel.generate(prompt, handler)
    │     → StreamingResponseHandler.onNext(token) → Flux<ChatChunk>(finished=false)
    │        SSE 逐字输出
    │
    └─ 5. onComplete() → 自信度判定（RRF maxScore 阈值）
          → ChatChunk(finished=true, confidence, sources JSON)
          → 保存本轮对话到 ConversationHistoryService
```

### 自信度判定策略

```
maxRRFScore ≥ 0.08  → HIGH   → 直接返回
maxRRFScore ≥ 0.04  → MEDIUM → 带免责声明
maxRRFScore < 0.04  → LOW    → "请等待讲师回答"
```

不用 LLM 做自信度分类——避免多一次 API 调用（延迟翻倍，成本翻倍）。
阈值可在部署后根据实际日志调优。

### 多轮对话

- Redis Key: `agent:session:{sessionId}`，类型 List
- 每轮存 2 条 JSON：`{"role":"user","content":"..."}` + `{"role":"assistant","content":"..."}`
- `LTRIM 0 9`（保留 10 条 = 5 轮），`EXPIRE 24h`
- 不做 LLM 压缩——教育场景轮次少（< 5 轮），Phase 6 需要时再加

### 流式生成

- 使用 langchain4j 0.36.2 的 `OpenAiStreamingChatModel`（jar 已包含）
- `StreamingResponseHandler` 回调 → `Flux.create(OverflowStrategy.BUFFER)` 桥接到 WebFlux
- `onError` 降级：发送礼貌提示 ChatChunk，不抛异常
- `sources` 字段：JSON 数组，含 courseName、chapterTitle、excerpt、score

### 关键设计决策

| 决策 | 原因 |
|------|------|
| **新增 RetrievalResult DTO** | DocChunk 是 MyBatis 实体，加分数会污染 MySQL/ES 文档 |
| **PromptBuilder + HistoryService 独立类** | 模板和 Redis 操作从 ChatServiceImpl 解耦，可单独测试 |
| **Flux.create + BUFFER** | OkHttp 异步线程安全，`onCancel` 可取消 LLM 流 |
| **Redis List 滑动窗口** | 简单够用，TTL 自动过期，不需要 MySQL 表 |
| **自信度用 RRF 分数不用 LLM** | 免额外 API 调用，RRF 分数已是检索质量的有效代理 |
| **流式不用 batch** | 首 token ~500ms vs batch 3~10s，SSE 已在 Phase 1 就绪 |

### 遇到的实际问题

| 问题 | 状态 | 解决方案 |
|------|------|---------|
| langchain4j 0.36.2 `generate(String, handler)` API 兼容性 | 编译通过 | API 匹配预期，无需调整 |

### 新增文件

- `RetrievalResult.java` — 检索结果 DTO
- `PromptBuilder.java` — Prompt 模板构造器
- `ConversationHistoryService.java` — 多轮对话历史管理

### 修改文件

- `ChatServiceImpl.java` — 占位 → 完整 RAG 流式管线
│   │   │       └── RetrievalServiceImpl.java       # ★ 检索管线实现（向量+关键词+RRF+缓存）
- `AgentConfig.java` — 新增 `OpenAiStreamingChatModel` Bean
- `RetrievalService.java` / `RetrievalServiceImpl.java` — 返回 `RetrievalResult`

---

## 十一、Phase 5 对接与收尾（✅ 已完成）

### sessionId 下发

`ChatChunk` 新增 `sessionId` 字段。前端未传时自动生成 UUID（`substring(0,8)`），
每个 SSE 片段都携带，前端存起来即可实现多轮对话。
读/写 Redis 对话历史统一使用此 sessionId（修了之前用 `request.getSessionId()` 的 bug）。

### 自信度与 Prompt 联动

`PromptBuilder.build()` 接收 `Confidence` 参数，根据级别注入不同的 LLM 回答策略：
- **HIGH**：正常回答，参考资料充足
- **MEDIUM**：追加免责声明——"【仅供参考】以上信息基于已有课程资料，可能不完整"
- **LOW**：告知 LLM 无法回答，"已自动转交给讲师，请等待讲师回复"

同时 `computeConfidence()` 新增 INFO 日志记录 maxRRFScore + 阈值对比，
部署后观察实际分数分布，按需调整阈值。

### Confidence.LOW → 自动建 Question

自信度 LOW 时异步调 `LearningClient.createQuestion()`，
在 learning-service 中建一个待回答问题工单（`CompletableFuture.runAsync`，不阻塞 SSE 流）。
`QuestionFormDTO` 在 tj-api 中定义，仅含 `courseId + title + description`，
与 learning-service 的完整表单（含 chapterId/sectionId）独立。

### 启动健康检查

`StartupHealthCheck` 在 `ApplicationReadyEvent` 时 ping ES + Redis，
失败仅 WARN 日志，不阻止启动（兼容 Docker Compose 启动顺序不一致）。

### 关键设计决策

| 决策 | 原因 |
|------|------|
| 异步建 Question（`runAsync`） | 不阻塞 SSE 主流程，learning-service 挂了不影响对话 |
| QuestionFormDTO 不加 chapterId/sectionId | Agent 无法确定学生问题对应哪个章节；learning-service 端预留新端点 |
| MEDIUM 不在代码层阻止回答 | 降级到 Prompt 免责——LLM 够聪明时仍能给有用信息 |
| 健康检查只 WARN | Docker Compose 启动顺序不保证，不阻止应用启动 |

---

## 十二、下一步（Phase 6 优化）

### 待做

1. **ES IK Synonym 词典**：术语标准化从 Java 端 IK+HGET 升级为 ES 端同义词扩展，零延迟
2. **置信度阈值调优**：部署后基于 `maxRRFScore` 日志分布重新校准 0.08/0.04 阈值
3. **增量同步优化**：`syncNewQA` 只查增量而非全量 `ingestCourse`
4. **Actuator + Prometheus**：检索延迟、LLM 延迟、自信度分布的监控指标
5. **多轮对话 LLM 压缩**：轮次多了之后用 LLM 总结旧历史
6. **删除 `langchain4j-redis`**：死依赖清理
