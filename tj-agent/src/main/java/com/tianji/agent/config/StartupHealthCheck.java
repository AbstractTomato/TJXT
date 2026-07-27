package com.tianji.agent.config;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

@Component
@Slf4j
@RequiredArgsConstructor
public class StartupHealthCheck implements ApplicationListener<ApplicationReadyEvent> {

    private final RestHighLevelClient restHighLevelClient;
    private final StringRedisTemplate redisTemplate;


    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        //ES
        try {
            boolean alive = restHighLevelClient.ping(RequestOptions.DEFAULT);
            log.info("ES连接检查: {}", alive ? "正常" : "不可达");
        } catch (IOException e) {
            log.warn("ES 连接检查: ❌ 失败 — {}", e.getMessage());
        }

        // Redis
        try {
            String pong = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().ping();
            log.info("Redis 连接检查: {}", "PONG".equals(pong) ? "✅ 正常" : "❌ 异常");
        } catch (Exception e) {
            log.warn("Redis 连接检查: ❌ 失败 — {}", e.getMessage());
        }
    }
}
