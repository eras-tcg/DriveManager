package com.drive.drive_manager.simulator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** A broken card file stops the start, instead of the first game that needs it. */
@Component
class CardFilesCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CardFilesCheck.class);

    @Override
    public void run(ApplicationArguments args) {
        log.info("Cartas programadas cargadas: {}", CardEffects.definitions().size());
    }
}
