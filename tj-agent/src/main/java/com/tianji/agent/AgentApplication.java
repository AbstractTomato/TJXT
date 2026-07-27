package com.tianji.agent;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 天机 Agent 服务 —— 智能教育助手
 * <p>
 * 核心 Agent：
 * 🦉 夜猫子助教 — 7×24 小时智能答疑
 * 🧭 领航员   — 学习路径规划
 * 📝 总结侠   — 课程摘要/笔记生成
 * 🕵️ 巡捕    — 异常行为检测
 * 🧙 数据掌柜 — 运营数据分析
 * </p>
 */
@SpringBootApplication
@MapperScan("com.tianji.agent.mapper")
@EnableFeignClients(basePackages = "com.tianji.api.client")
@Slf4j
@EnableAsync
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) throws UnknownHostException {
        SpringApplication app = new SpringApplicationBuilder(AgentApplication.class).build(args);
        Environment env = app.run(args).getEnvironment();
        String protocol = "http";
        if (env.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }
        log.info("--/\n---------------------------------------------------------------------------------------\n\t" +
                        "Application '{}' is running! Access URLs:\n\t" +
                        "Local: \t\t{}://localhost:{}\n\t" +
                        "External: \t{}://{}:{}\n\t" +
                        "Profile(s): \t{}" +
                        "\n---------------------------------------------------------------------------------------",
                env.getProperty("spring.application.name"),
                protocol,
                env.getProperty("server.port"),
                protocol,
                InetAddress.getLocalHost().getHostAddress(),
                env.getProperty("server.port"),
                env.getActiveProfiles());
    }
}
