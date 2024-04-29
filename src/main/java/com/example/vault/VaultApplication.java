package com.example.vault;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.ProxyFactoryBean;
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
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * - reflection
 * - serialization
 * - jni
 * - jdk proxies
 * - resource loading
 */

@SpringBootApplication
public class VaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultApplication.class, args);
    }


    interface RefreshedEventListener extends ApplicationListener<RefreshEvent> {
    }


    static void dump(String msg, DataSourceProperties properties) {
        System.out.println(msg + " : " +
                properties.getUrl() + ":" +
                properties.getUsername() + ":" +
                properties.getPassword());

    }

    @Bean
    DataSource dataSource(DataSourceProperties properties) {

        var rebuild = (Function<DataSourceProperties, DataSource>) dataSourceProperties -> DataSourceBuilder //
                .create()//
                .url(properties.getUrl()) //
                .username(properties.getUsername()) //
                .password(properties.getPassword()) //
                .build();

        var delegate = new AtomicReference<DataSource>();
        delegate.set(rebuild.apply(properties));


        dump("initial", properties);

        var pfb = new ProxyFactoryBean();
        pfb.addInterface(DataSource.class);
        pfb.addInterface(RefreshedEventListener.class);
        pfb.addAdvice((MethodInterceptor) invocation -> {
            var methodName = invocation.getMethod().getName();
            System.out.println("method name is " + methodName);
            if (methodName.equals("onApplicationEvent")) {
                delegate.set(rebuild.apply(properties));
                dump("application event", properties);
                return null;
            }

            dump("otherwise", properties);
            return invocation.getMethod().invoke(delegate.get(), invocation.getArguments());
        });
        return (DataSource) pfb.getObject();
    }
}

/* yuck. */
class DataSourceRefreshListener implements DataSource, ApplicationListener<RefreshEvent> {

    private final AtomicReference<DataSource> delegate = new AtomicReference<>();

    private final Object monitor = new Object();

    private final DataSourceProperties properties;


    DataSourceRefreshListener(DataSourceProperties dataSourceProperties) {
        this.properties = dataSourceProperties;
        this.reset();
    }

    private void reset() {
        synchronized (this.monitor) {
            this.delegate.set(this.build());
        }
    }

    private DataSource build() {
        System.out.println(properties.getUrl() + ":" + properties.getUsername() + ":" + properties.getPassword());
        return DataSourceBuilder //
                .create()//
                .url(properties.getUrl()) //
                .username(properties.getUsername()) //
                .password(properties.getPassword()) //
                .build();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.get().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate.get().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.get().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.get().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.get().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.get().getLoginTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.get().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.get().isWrapperFor(iface);
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.get().getParentLogger();
    }

    @Override
    public void onApplicationEvent(RefreshEvent event) {
        reset();
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
class VaultConfiguration {

    private final Log log = LogFactory.getLog(getClass());

    private final ApplicationEventPublisher publisher;

    VaultConfiguration(@Value("${spring.cloud.vault.database.role}") String databaseRole,
                       @Value("${spring.cloud.vault.database.backend}") String databaseBackend,
                       SecretLeaseContainer leaseContainer,
                       ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        var vaultCredsPath = String.format("%s/creds/%s", databaseBackend, databaseRole);
        leaseContainer.addLeaseListener(event -> {
            if (event instanceof SecretLeaseExpiredEvent && event.getSource().getMode() == RequestedSecret.Mode.RENEW) {
                log.info("==> Replace RENEW lease by a ROTATE one.");
                leaseContainer.requestRotatingSecret(vaultCredsPath);
            }//
            else if (event instanceof SecretLeaseCreatedEvent && event.getSource().getMode() == RequestedSecret.Mode.ROTATE) {
                refresh();
            }
        });
    }

    @Scheduled(initialDelayString = "${kv.refresh-interval}", fixedDelayString = "${kv.refresh-interval}")
    void refresher() {
        refresh();
        log.info("refresh KV secret");
    }

    private void refresh() {
        log.info("refresh()!");
        publisher.publishEvent(new RefreshEvent(this, null, "vault or bust!"));
    }
}
