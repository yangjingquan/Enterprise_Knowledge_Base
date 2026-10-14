package com.kb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot 启动类。
 * <p>
 * - {@code @EnableAsync}：开启异步任务，文档上传后的解析/向量化在后台线程执行，不阻塞上传接口。<br>
 * - {@code @MapperScan}：扫描 MyBatis-Plus 的 Mapper 接口，自动生成数据库操作实现。
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.kb.mapper")
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }
}
