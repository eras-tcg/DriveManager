package com.drive.drive_manager.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

@Component
public class DriveClientFactory {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Value("${drive.service-account-credentials}")
    private Resource serviceAccountCredentials;

    public Drive create() throws GeneralSecurityException, IOException {
        try (InputStream stream = serviceAccountCredentials.getInputStream()) {
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(stream)
                    .createScoped(DriveScopes.DRIVE_READONLY);
            return new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JSON_FACTORY,
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("drive_manager")
                    .build();
        }
    }
}
