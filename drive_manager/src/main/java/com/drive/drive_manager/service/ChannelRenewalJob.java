package com.drive.drive_manager.service;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz job that runs daily to renew the Google Drive watch channel
 * before it expires (channels live at most 7 days).
 *
 * This is the only scheduled task left — it does NOT trigger a full sync,
 * just ensures the webhook channel stays alive.
 */
@Component
public class ChannelRenewalJob implements Job {

    private static final Logger logger = LoggerFactory.getLogger(ChannelRenewalJob.class);

    @Autowired
    private DriveWatchService driveWatchService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            driveWatchService.renewIfExpiringSoon();
        } catch (Exception e) {
            logger.error("Drive channel renewal failed", e);
        }
    }
}
