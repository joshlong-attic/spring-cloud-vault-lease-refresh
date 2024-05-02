package com.example.vault;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactoryBean;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.DecoratingProxy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.domain.RequestedSecret;
import org.springframework.vault.core.lease.event.SecretLeaseCreatedEvent;
import org.springframework.vault.core.lease.event.SecretLeaseExpiredEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;


@SpringBootApplication
public class VaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultApplication.class, args);
    }


}

@Controller
@ResponseBody
class PaymentsController {

    record Payment(String id, String name, String address, Instant createdAt) {
    }

    private final JdbcClient db;

    PaymentsController(DataSource dataSource) {
        this.db = JdbcClient.create(dataSource);
    }

    @GetMapping("/payments")
    Collection<Payment> payments() {
        var payments = this.db
                .sql("select * from payments")
                .query((rs, rowNum) -> new Payment(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("billing_address"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
        return payments;
    }
}

@Configuration
@EnableScheduling
@ImportRuntimeHints(RefreshableDataSourceVaultConfiguration.RefreshableDataSourceHints.class)
class RefreshableDataSourceVaultConfiguration {

    private final Log log = LogFactory.getLog(getClass());

    private final ApplicationEventPublisher publisher;

    RefreshableDataSourceVaultConfiguration(@Value("${spring.cloud.vault.database.role}") String databaseRole,
                                            @Value("${spring.cloud.vault.database.backend}") String databaseBackend,
                                            DataSourceProperties properties,
                                            SecretLeaseContainer leaseContainer,
                                            ApplicationEventPublisher publisher) {
        this.publisher = publisher;

        var vaultCredsPath = String.format("%s/creds/%s", databaseBackend, databaseRole);
        leaseContainer.addLeaseListener(event -> {
            if (vaultCredsPath.equals(event.getSource().getPath())) {
                if (event instanceof SecretLeaseExpiredEvent && event.getSource().getMode() == RequestedSecret.Mode.RENEW) {
                    log.info("Expiring lease, rotate database credentials");
                    leaseContainer.requestRotatingSecret(vaultCredsPath);
                } else if (event instanceof SecretLeaseCreatedEvent secretLeaseCreatedEvent
                        && event.getSource().getMode() == RequestedSecret.Mode.ROTATE) {

                    String username = (String) secretLeaseCreatedEvent.getSecrets().get("username");
                    String password = (String) secretLeaseCreatedEvent.getSecrets().get("password");

                    log.info("Updating database properties : " + username);
                    properties.setUsername(username);
                    properties.setPassword(password);

                    refresh();
                }
            }
        });
    }

    @Scheduled(initialDelayString = "${kv.refresh-interval}", fixedDelayString = "${kv.refresh-interval}")
    void refresher() {
        refresh();
    }

    static class RefreshableDataSourceHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.proxies().registerJdkProxy(DataSource.class, RefreshedEventListener.class,
                    SpringProxy.class, Advised.class, DecoratingProxy.class);
        }
    }

    interface RefreshedEventListener extends ApplicationListener<RefreshEvent> {
    }

    @Bean
    DataSource dataSource(DataSourceProperties properties) {
        var rebuild = (Function<DataSourceProperties, DataSource>) dataSourceProperties -> {
            log.info("Building data source: " + properties.getUsername());

            return DataSourceBuilder //
                    .create()//
                    .url(properties.getUrl()) //
                    .username(properties.getUsername()) //
                    .password(properties.getPassword()) //
                    .build();

        };

        var delegate = new AtomicReference<>(rebuild.apply(properties));

        var pfb = new ProxyFactoryBean();
        pfb.addInterface(DataSource.class);
        pfb.addInterface(RefreshedEventListener.class);
        pfb.addAdvice((MethodInterceptor) invocation -> {
            var methodName = invocation.getMethod().getName();
            if (methodName.equals("onApplicationEvent")) {
                delegate.set(rebuild.apply(properties));
                return null;
            }
            return invocation.getMethod()
                    .invoke(delegate.get(), invocation.getArguments());
        });
        return (DataSource) pfb.getObject();
    }

    private void refresh() {
        this.publisher.publishEvent(new RefreshEvent(this, null,
                "refresh database connection with new Vault credentials"));
    }
}
