import re

with open('tj-agent/README.md', 'r', encoding='utf-8') as f:
    content = f.read()

# Find the directory tree section
old_tree_start = content.find('tj-agent/')
old_tree_end = content.find('```', content.find('AgentApplication.java'))

# New directory tree
new_tree = '''tj-agent/
├── pom.xml                                         # JDK 11 编译目标，JDK 17 运行
│                                                   # 依赖：langchain4j 0.36.2、ES 7.12.1、MyBatis-Plus
├── Dockerfile                                      # JDK 17 自建镜像
└── src/main/
    ├── java/com/tianji/agent/
    │   ├── AgentApplication.java                   # 启动类，@EnableFeignClients + @MapperScan + @EnableScheduling + @EnableAsync
    │   ├── config/
    │   │   ├── AgentConfig.java                    # LLM 配置：OpenAiStreamingChatModel + BgeSmallZhEmbeddingModel（本地 768 维）
    │   │   ├── EmbeddingWarmup.java                # 启动时预热 BGE 模型（避免首次调用超时）
    │   │   └── StartupHealthCheck.java             # ES/Redis 启动连通性检查（Phase 5）
    │   ├── controller/
    │   │   └── ChatController.java                 # POST /agent/chat/stream (SSE)
    │   ├── service/
    │   │   ├── ChatService.java                    # 对话服务接口
    │   │   ├── IngestionService.java               # 知识库入库接口
    │   │   ├── RetrievalService.java               # ★ 检索管线接口（Phase 3）
    │   │   ├── TermAliasService.java               # 术语标准化（ES IK 分词 → Redis Hash）
    │   │   ├── PromptBuilder.java                  # System Prompt 模板构造器（Phase 4）
    │   │   ├── ConversationHistoryService.java     # 多轮对话历史管理（Phase 4）
    │   │   └── impl/
    │   │       ├── ChatServiceImpl.java            # ★ 完整 RAG 流式对话（Phase 4 重写）
    │   │       ├── IngestionServiceImpl.java       # ★ 核心入库逻辑
    │   │       └── RetrievalServiceImpl.java       # ★ 检索管线实现（向量+关键词+RRF+缓存）
    │   ├── mapper/
    │   │   └── DocChunkMapper.java                 # MyBatis-Plus Mapper
    │   ├── repository/
    │   │   └── KnowledgeEsRepository.java          # ES 操作（写入+向量检索+关键词检索+删除）
    │   ├── handler/
    │   │   └── ContentSyncHandler.java             # XXL-Job：syncKnowledgeBase + syncNewQA
    │   ├── domain/
    │   │   ├── dto/
    │   │   │   ├── ChatRequest.java                # {question, courseId, sessionId}
    │   │   │   ├── ChatChunk.java                  # SSE 响应片段（含 sessionId）
    │   │   │   └── RetrievalResult.java            # 检索结果 DTO（DocChunk + RRF 分数）
    │   │   ├── enums/
    │   │   │   └── Confidence.java                 # HIGH / MEDIUM / LOW
    │   │   └── po/
    │   │       └── DocChunk.java                   # 知识库文档块实体
    └── resources/
        ├── bootstrap.yml                           # 主配置
        ├── bootstrap-dev.yml                       # VM 部署环境
        └── bootstrap-local.yml                     # 本地开发环境
'''

old_tree_section = content[old_tree_start:old_tree_end]
content = content.replace(old_tree_section, new_tree)

# Now fix ES index mapping section — replace stale mapping with actual one
old_mapping_start = content.find('```json')
old_mapping_end = content.find('```', old_mapping_start + 7)

new_mapping = '''```json
PUT /agent_knowledge
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "30s"
  },
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
```'''

old_mapping_section = content[old_mapping_start:old_mapping_end + 3]
content = content.replace(old_mapping_section, new_mapping)

with open('tj-agent/README.md', 'w', encoding='utf-8') as f:
    f.write(content)

print('README fixed: directory tree + ES mapping')
