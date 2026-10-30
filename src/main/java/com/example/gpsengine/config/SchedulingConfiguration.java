package com.example.gpsengine.config;

import com.example.gpsengine.job.RecomputeStats;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulingConfiguration {

    @Bean
    public RecomputeStats recomputeStats() {
        return new RecomputeStats();
    }
}

