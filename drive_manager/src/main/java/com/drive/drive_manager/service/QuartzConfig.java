package com.drive.drive_manager.service;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

    @Bean
    JobDetail channelRenewalJobDetail() {
        return JobBuilder.newJob(ChannelRenewalJob.class)
                .withIdentity("channelRenewalJob")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger channelRenewalTrigger(JobDetail channelRenewalJobDetail) {
        // Runs every day at 02:00 — renews only if expiry is within 2 days
        return TriggerBuilder.newTrigger()
                .forJob(channelRenewalJobDetail)
                .withIdentity("channelRenewalTrigger")
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(2, 0))
                .build();
    }
}
