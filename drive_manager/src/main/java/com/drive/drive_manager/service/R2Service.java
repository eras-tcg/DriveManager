package com.drive.drive_manager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Service
public class R2Service {

    private static final Logger logger = LoggerFactory.getLogger(R2Service.class);
    private static final String KEY_PREFIX = "cards/";

    private final S3Client r2Client;

    @Value("${r2.bucket-name}")
    private String bucketName;

    @Value("${r2.public-url}")
    private String publicBaseUrl;

    public R2Service(S3Client r2Client) {
        this.r2Client = r2Client;
    }

    /**
     * Uploads image bytes to R2. Key: cards/{fileId}.jpg
     *
     * @return the public URL of the uploaded object
     */
    public String upload(String fileId, InputStream content, long size) {
        String key = KEY_PREFIX + fileId + ".jpg";
        r2Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("image/jpeg")
                        .contentLength(size)
                        .build(),
                RequestBody.fromInputStream(content, size));

        String url = publicBaseUrl.stripTrailing() + "/" + key;
        logger.info("Uploaded to R2: {}", url);
        return url;
    }

    /**
     * Uploads to R2 using the full key as-is (no prefix added).
     *
     * @return the public URL of the uploaded object
     */
    public String uploadRaw(String key, InputStream content, long size) {
        r2Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("image/jpeg")
                        .contentLength(size)
                        .build(),
                RequestBody.fromInputStream(content, size));

        String url = publicBaseUrl.stripTrailing() + "/" + key;
        logger.info("Uploaded to R2: {}", url);
        return url;
    }

    /**
     * Deletes the image from R2. Silently ignores if the object does not exist.
     */
    public void delete(String fileId) {
        String key = KEY_PREFIX + fileId + ".jpg";
        r2Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
        logger.info("Deleted from R2: {}", key);
    }
}
