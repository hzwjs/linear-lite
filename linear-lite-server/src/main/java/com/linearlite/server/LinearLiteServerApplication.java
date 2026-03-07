package com.linearlite.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.linearlite.server.mapper")
public class LinearLiteServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinearLiteServerApplication.class, args);
    }
}
