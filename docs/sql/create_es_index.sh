#!/bin/bash
# 在虚拟机中创建 agent_knowledge ES 索引
# 用法: bash create_es_index.sh
#
# 注意：此脚本会先删除旧索引（如果存在），然后重建。
# 向量已从 Redis 迁移至 ES dense_vector（768 维），不再需要 vectorKey 字段。

ES_HOST="192.168.150.101:9200"

echo "删除旧索引（如果存在）..."
curl -X DELETE "${ES_HOST}/agent_knowledge" 2>/dev/null
echo ""

echo "创建 ES 索引 agent_knowledge..."
curl -X PUT "${ES_HOST}/agent_knowledge" -H 'Content-Type: application/json' -d'
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
}'

echo ""
echo "验证索引是否创建成功..."
curl -X GET "${ES_HOST}/agent_knowledge"
