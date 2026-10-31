package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.R2Service;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/drive")
public class ProxyPrintController {

    private static final float PTS           = 72f;
    private static final float CARD_W        = 2.5f * PTS;
    private static final float CARD_H        = 3.5f * PTS;
    private static final float DEFAULT_MARGIN = 0.5f;   // inches

    private static final Logger log = LoggerFactory.getLogger(ProxyPrintController.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    private R2Service r2Service;

    /** The bucket's public address, the same the site loads the images from. */
    @Value("${r2.public-url:}")
    private String r2PublicUrl;

    @PostMapping(value = "/proxy-print", produces = "application/pdf")
    public ResponseEntity<byte[]> generateProxyPdf(@RequestBody ProxyPrintRequest request) throws Exception {
        PDRectangle pageSize = resolvePageSize(request.paperSize(), request.orientation());
        float margin = (request.margin() != null ? request.margin() : DEFAULT_MARGIN) * PTS;
        int cols    = (int) ((pageSize.getWidth()  - margin * 2) / CARD_W);
        int rows    = (int) ((pageSize.getHeight() - margin * 2) / CARD_H);
        int perPage = Math.max(1, cols * rows);

        float brightness = request.brightness() != null ? request.brightness() : 0f;
        float contrast   = request.contrast()   != null ? request.contrast()   : 0f;
        boolean adjust   = brightness != 0f || contrast != 0f;

        List<String> cardIds = request.cardIds();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(pageSize);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);

            for (int i = 0; i < cardIds.size(); i++) {
                if (i > 0 && i % perPage == 0) {
                    cs.close();
                    page = new PDPage(pageSize);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                }

                int pos = i % perPage;
                float x = margin + (pos % cols) * CARD_W;
                float y = pageSize.getHeight() - margin - (pos / cols + 1) * CARD_H;

                try {
                    byte[] imgBytes = image(cardIds.get(i));
                    if (adjust) imgBytes = adjustImage(imgBytes, brightness, contrast);
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, imgBytes, cardIds.get(i));
                    cs.drawImage(img, x, y, CARD_W, CARD_H);
                } catch (Exception e) {
                    log.warn("Proxy without image for card {}: {}", cardIds.get(i), e.toString());
                    cs.setStrokingColor(Color.LIGHT_GRAY);
                    cs.addRect(x, y, CARD_W, CARD_H);
                    cs.stroke();
                }
            }

            cs.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"proxies.pdf\"")
                    .body(baos.toByteArray());
        }
    }

    /**
     * The card's image: from the bucket's API, or else from its public address (the one the site shows), so a problem
     * with the API keys does not leave the sheet blank.
     */
    private byte[] image(String cardId) throws Exception {
        try {
            return r2Service.getImage(cardId);
        } catch (Exception e) {
            if (r2PublicUrl == null || r2PublicUrl.isBlank()) throw e;
            log.warn("R2 API read failed for card {} ({}); using the public address", cardId, e.toString());
            HttpRequest request = HttpRequest.newBuilder(URI.create(r2PublicUrl.strip().replaceAll("/+$", "") + "/cards/" + cardId + ".jpg"))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IllegalStateException("public image answered " + response.statusCode());
            return response.body();
        }
    }

    /**
     * Adjusts brightness and contrast using RescaleOp.
     * brightness: -100 to +100 (maps to -255..+255 pixel offset)
     * contrast:   -100 to +100 (maps to scale factor 0..2, midpoint-preserving)
     */
    private byte[] adjustImage(byte[] imageBytes, float brightness, float contrast) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (img == null) return imageBytes;

        float scale  = Math.max(0f, 1f + contrast / 100f);
        float offset = (brightness / 100f) * 255f + 128f * (1f - scale);

        int numBands = img.getRaster().getNumBands();
        float[] scales  = new float[numBands];
        float[] offsets = new float[numBands];
        Arrays.fill(scales,  scale);
        Arrays.fill(offsets, offset);
        if (numBands == 4) { scales[3] = 1f; offsets[3] = 0f; } // preserve alpha

        BufferedImage adjusted = new RescaleOp(scales, offsets, null).filter(img, null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(adjusted, "jpg", baos);
        return baos.toByteArray();
    }

    private PDRectangle resolvePageSize(String paperSize, String orientation) {
        PDRectangle base = switch (paperSize == null ? "" : paperSize.toLowerCase()) {
            case "a4"    -> PDRectangle.A4;
            case "legal" -> new PDRectangle(612, 1008);
            case "a3"    -> PDRectangle.A3;
            default      -> PDRectangle.LETTER;
        };
        return "landscape".equalsIgnoreCase(orientation)
                ? new PDRectangle(base.getHeight(), base.getWidth())
                : base;
    }

    public record ProxyPrintRequest(
            @JsonProperty("cardIds")     List<String> cardIds,
            @JsonProperty("paperSize")   String paperSize,
            @JsonProperty("orientation") String orientation,
            @JsonProperty("margin")      Float margin,
            @JsonProperty("brightness")  Float brightness,
            @JsonProperty("contrast")    Float contrast
    ) {}
}
