package com.capstone.ztacsredis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.capstone")
public class ZtacsRedisApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZtacsRedisApplication.class, args);
    }
}
