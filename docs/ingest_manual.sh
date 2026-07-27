#!/bin/bash
# 天机 Agent — 手动知识库灌入脚本
# 用法: bash docs/ingest_manual.sh
set -e

VM="192.168.150.101"
DOCKER="http://${VM}:2375"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KNOWLEDGE_FILE="$SCRIPT_DIR/manual_knowledge.json"

echo "=== 手动知识库灌入 ==="

if [ ! -f "$KNOWLEDGE_FILE" ]; then
    echo "错误: $KNOWLEDGE_FILE 不存在"
    exit 1
fi

# Upload the knowledge file to tj-agent container
echo "[1/3] 上传知识文件..."
cd "$SCRIPT_DIR" && tar -czf /tmp/knowledge.tar.gz manual_knowledge.json
curl -s -X PUT "${DOCKER}/containers/tj-agent/archive?path=/tmp/" \
    -H 'Content-Type: application/x-tar' --data-binary @/tmp/knowledge.tar.gz > /dev/null
echo "  已上传"

# Wait for tj-agent to be ready
echo "[2/3] 等待 tj-agent 就绪..."
for i in $(seq 1 10); do
    STATUS=$(curl -s "${DOCKER}/containers/tj-agent/json" | grep -o '"Status":"[^"]*"' | head -1)
    if [ "$STATUS" = '"Status":"running"' ]; then
        echo "  tj-agent 运行中"
        break
    fi
    sleep 3
done

# Ingest each entry
echo "[3/3] 灌入知识条目..."
SUCCESS=0
FAIL=0
COUNT=$(python3 -c "import json; print(len(json.load(open('$KNOWLEDGE_FILE'))))" 2>/dev/null || echo "0")
echo "  共 $COUNT 条"

# Use Docker exec to run curl inside the container
for i in $(seq 0 $((COUNT - 1))); do
    ENTRY=$(python3 -c "import json; data=json.load(open('$KNOWLEDGE_FILE')); print(json.dumps(data[$i]))" 2>/dev/null)
    if [ -z "$ENTRY" ]; then
        echo "  跳过第 $i 条（解析失败）"
        continue
    fi

    # Write single entry to a temp file and upload
    echo "$ENTRY" > /tmp/single_entry.json
    cd /tmp && tar -czf /tmp/single.tar.gz single_entry.json
    curl -s -X PUT "${DOCKER}/containers/tj-agent/archive?path=/tmp/" \
        -H 'Content-Type: application/x-tar' --data-binary @/tmp/single.tar.gz > /dev/null

    # Call the ingest endpoint
    RESPONSE=$(curl -s -X POST "http://${VM}:2375/containers/tj-agent/exec" \
        -H 'Content-Type: application/json' \
        -d "{\"AttachStdout\":true,\"AttachStderr\":true,\"Cmd\":[\"sh\",\"-c\",\"curl -s -X POST http://localhost:8100/agent/admin/ingest -H 'Content-Type: application/json' -d @/tmp/single_entry.json 2>&1\"]}" 2>/dev/null)

    EXEC_ID=$(echo "$RESPONSE" | grep -o '"Id":"[^"]*"' | head -1 | sed 's/"Id":"//;s/"//' 2>/dev/null)
    if [ -n "$EXEC_ID" ]; then
        RESULT=$(curl -s -X POST "http://${VM}:2375/exec/$EXEC_ID/start" \
            -H 'Content-Type: application/json' -d '{"Detach":false,"Tty":false}' 2>&1 | tail -c +9)
        if echo "$RESULT" | grep -q "OK"; then
            SUCCESS=$((SUCCESS + 1))
            echo "  [$((i+1))/$COUNT] ✅"
        else
            FAIL=$((FAIL + 1))
            echo "  [$((i+1))/$COUNT] ❌ $RESULT"
        fi
    else
        FAIL=$((FAIL + 1))
        echo "  [$((i+1))/$COUNT] ❌ exec failed"
    fi
done

echo ""
echo "=== 完成: 成功=$SUCCESS 失败=$FAIL ==="

# Show ES count
echo ""
echo "=== ES 文档数 ==="
curl -s "${DOCKER}/containers/tj-agent/logs?stdout=true&stderr=true&tail=5" > /dev/null
sleep 3
