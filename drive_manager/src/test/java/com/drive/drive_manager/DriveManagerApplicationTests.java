package com.drive.drive_manager;

import com.drive.drive_manager.service.DriveWatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DriveManagerApplicationTests {

	// Avoid registering a real Drive watch channel (or starting ngrok) during tests
	@MockitoBean
	DriveWatchService driveWatchService;

	@Test
	void contextLoads() {
	}

}
