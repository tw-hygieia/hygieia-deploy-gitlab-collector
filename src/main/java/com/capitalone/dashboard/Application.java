package com.capitalone.dashboard;

import com.capitalone.dashboard.event.EnvironmentComponentEventListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Application configuration and bootstrap
 */
@SpringBootApplication
@ComponentScan(excludeFilters = {@ComponentScan.Filter(value = EnvironmentComponentEventListener.class, type = FilterType.ASSIGNABLE_TYPE)})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
