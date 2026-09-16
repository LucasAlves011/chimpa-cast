package com.screenshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScreenShareApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScreenShareApplication.class, args);
    }
}
