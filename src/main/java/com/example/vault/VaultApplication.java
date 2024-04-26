package com.example.vault;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.event.SecretLeaseExpiredEvent;

import java.util.logging.Logger;

@SpringBootApplication
public class VaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultApplication.class, args);
    }
}

@Configuration
class VaultConfig {

    VaultConfig(@Value("${spring.cloud.vault.database.role}") String databaseRole,
                @Value("${spring.cloud.vault.database.backend}") String databaseBackend,
                SecretLeaseContainer leaseContainer,
                ContextRefresher contextRefresher ,
                Environment environment) {

        System.out.println("reading environment configuration");
        System.out.println("username:"+ environment.getProperty("spring.datasource.username"));
        System.out.println("password:"+environment.getProperty("spring.datasource.password"));



        var vaultCredsPath = String.format("%s/creds/%s", databaseBackend, databaseRole);
        leaseContainer.addLeaseListener(event -> {
            if (vaultCredsPath.equals(event.getSource().getPath())) {
                if (event instanceof SecretLeaseExpiredEvent) {
                    contextRefresher.refresh();
                }
            }
        });
    }
}
