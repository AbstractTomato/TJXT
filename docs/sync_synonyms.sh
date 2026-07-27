#!/bin/bash
# ============================================
# 天机 Agent — 同义词同步脚本
# 用法: bash docs/sync_synonyms.sh
# 在 Windows 本地编辑 synonyms.txt，运行此脚本推送到 VM
# ============================================
set -e

VM="192.168.150.101"
DOCKER="http://${VM}:2375"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNONYMS_FILE="$SCRIPT_DIR/synonyms.txt"
REDIS_PASSWORD="123321"

echo "=== 同步同义词到 VM ==="

if [ ! -f "$SYNONYMS_FILE" ]; then
    echo "错误: $SYNONYMS_FILE 不存在"
    exit 1
fi

# ---- Docker exec helper ----
docker_exec() {
    local container="$1" cmd="$2"
    local id=$(curl -s -X POST "$DOCKER/containers/$container/exec" \
        -H 'Content-Type: application/json' \
        -d "{\"AttachStdout\":true,\"AttachStderr\":true,\"Cmd\":[\"sh\",\"-c\",\"$cmd\"]}" | sed 's/.*"Id":"\([^"]*\)".*/\1/')
    curl -s -X POST "$DOCKER/exec/$id/start" \
        -H 'Content-Type: application/json' -d '{"Detach":false,"Tty":false}' 2>&1 | tail -c +9  # skip Docker 8-byte header
}

# ---- 1. 备份 ext.dic ----
echo "[1/3] 备份 ext.dic..."
docker_exec es "cp /usr/share/elasticsearch/plugins/ik/config/ext.dic /usr/share/elasticsearch/plugins/ik/config/ext.dic.bak"

# ---- 2. 下载现有 ext.dic 用于去重 ----
EXISTING=$(curl -s "$DOCKER/containers/es/archive?path=/usr/share/elasticsearch/plugins/ik/config/ext.dic" | tar -xO 2>/dev/null)

# ---- 3. 逐行处理 ----
echo "[2/3] 同步术语..."
ADDED=0
TEMP_DIC=$(mktemp)
echo "$EXISTING" > "$TEMP_DIC"

while IFS=" => " read -r RAW STANDARD; do
    [ -z "$RAW" ] && continue
    echo "$RAW" | grep -q "^#" && continue

    # ext.dic: 只追加不重复
    if ! grep -qxF "$RAW" "$TEMP_DIC" 2>/dev/null; then
        echo "$RAW" >> "$TEMP_DIC"
        echo "  ext.dic + $RAW"
        ADDED=$((ADDED + 1))
    fi

    # Redis: 覆盖写入
    docker_exec redis "redis-cli -a $REDIS_PASSWORD HSET agent:term:alias \"$RAW\" \"$STANDARD\"" | grep -q '[0-9]' && \
        echo "  Redis  + $RAW => $STANDARD"
done < "$SYNONYMS_FILE"

# 上传新的 ext.dic
if [ "$ADDED" -gt 0 ]; then
    cp "$TEMP_DIC" /tmp/ext_new.dic
    cd /tmp && tar -cf ext_new.tar ext_new.dic 2>/dev/null && cd "$OLDPWD"
    curl -s -X PUT "$DOCKER/containers/es/archive?path=/usr/share/elasticsearch/plugins/ik/config/" \
        -H 'Content-Type: application/x-tar' --data-binary @/tmp/ext_new.tar > /dev/null
    rm -f /tmp/ext_new.dic /tmp/ext_new.tar
fi
rm -f "$TEMP_DIC"

# ---- 4. 重启 ES ----
if [ "$ADDED" -gt 0 ]; then
    echo "[3/3] 新增 $ADDED 词，重启 ES..."
    curl -s -X POST "$DOCKER/containers/es/restart" > /dev/null
    echo "等待 ES 就绪..."
    for i in $(seq 1 30); do
        curl -s "http://${VM}:9200/" > /dev/null 2>&1 && echo "ES 已就绪" && break
        sleep 2
    done
else
    echo "[3/3] 无新增词，跳过重启"
fi

echo "=== 同步完成 ==="
