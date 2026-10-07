package com.ticketsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// The auth module owns credential verification; do not create Boot's default in-memory user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class TicketSystemApplication {

    public static void main(String[] args) {
//        if (System.getProperty("os.name").startsWith("Windows")) {
//            System.getProperties().putIfAbsent("jdk.net.unixdomain.tmpdir", System.getProperty("user.dir"));
//        }
        SpringApplication.run(TicketSystemApplication.class, args);
    }
}
