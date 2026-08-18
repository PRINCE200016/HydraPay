package com.hydrapay.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HydraPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(HydraPayApplication.class, args);
    }
}
