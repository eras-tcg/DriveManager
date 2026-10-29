package com.drive.drive_manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
public class DriveManagerApplication {

	public static void main(String[] args) {
		SpringApplication.run(DriveManagerApplication.class, args);
	}

}