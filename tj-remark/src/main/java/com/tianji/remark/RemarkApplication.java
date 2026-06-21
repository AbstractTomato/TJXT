package com.tianji.remark;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tianji.remark.mapper")
public class RemarkApplication {
    public static void main(String[] args) {
        SpringApplication.run(RemarkApplication.class, args);
    }
}
