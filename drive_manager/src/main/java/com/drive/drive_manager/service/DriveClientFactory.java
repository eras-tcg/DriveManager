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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

@Component
public class DriveClientFactory {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Value("${drive.sa.project-id}")
    private String projectId;

    @Value("${drive.sa.private-key-id}")
    private String privateKeyId;

    @Value("${drive.sa.private-key}")
    private String privateKeyPem;

    @Value("${drive.sa.client-email}")
    private String clientEmail;

    @Value("${drive.sa.client-id}")
    private String clientId;

    public Drive create() throws GeneralSecurityException, IOException {
        PrivateKey privateKey = parsePrivateKey(privateKeyPem);

        GoogleCredentials credentials = ServiceAccountCredentials.newBuilder()
                .setProjectId(projectId)
                .setPrivateKeyId(privateKeyId)
                .setPrivateKey(privateKey)
                .setClientEmail(clientEmail)
                .setClientId(clientId)
                .setScopes(List.of(DriveScopes.DRIVE_READONLY))
                .build();

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("drive_manager")
                .build();
    }

    private static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String base64 = pem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }
}
