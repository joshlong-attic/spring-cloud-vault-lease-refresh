package com.example.vault;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.event.SecretLeaseExpiredEvent;

import javax.sql.DataSource;
import java.util.logging.Logger;

@SpringBootApplication
public class VaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultApplication.class, args);
    }
}

@Configuration
class VaultConfig {

    // Needs something to refresh
    @Bean
    @RefreshScope
    DataSource dataSource(DataSourceProperties properties) {
        return DataSourceBuilder.create().url(properties.getUrl()).username(properties.getUsername())
                .password(properties.getPassword()).build();
    }

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
                    System.out.println("refreshed credentials");
                    System.out.println("username:"+ environment.getProperty("spring.datasource.username"));
                    System.out.println("password:"+environment.getProperty("spring.datasource.password"));
                }
            }
        });
    }
}
