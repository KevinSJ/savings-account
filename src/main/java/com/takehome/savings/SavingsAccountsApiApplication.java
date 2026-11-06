package com.takehome.savings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the savings-account API.
 *
 * <p>Runtime dependencies (Postgres + Redis) are expected to be reachable
 * at startup; {@code docker compose up -d} in the repo root provides both.
 */
@SpringBootApplication
public class SavingsAccountsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SavingsAccountsApiApplication.class, args);
    }
}
