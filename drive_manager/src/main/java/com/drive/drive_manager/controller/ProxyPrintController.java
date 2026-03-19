package com.drive.drive_manager.controller;

import com.drive.drive_manager.service.R2Service;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

@RestController
@RequestMapping("/api/drive")
public class ProxyPrintController {

    private static final float PTS    = 72f;
    private static final float CARD_W = 2.5f * PTS;   // 180 pts
    private static final float CARD_H = 3.5f * PTS;   // 252 pts
    private static final float MARGIN = 0.1f * PTS;   //   7.2 pts

    @Autowired
    private R2Service r2Service;

    @PostMapping(value = "/proxy-print", produces = "application/pdf")
    public ResponseEntity<byte[]> generateProxyPdf(@RequestBody ProxyPrintRequest request) throws Exception {
        PDRectangle pageSize = resolvePageSize(request.paperSize(), request.orientation());
        int cols    = (int) ((pageSize.getWidth()  - MARGIN * 2) / CARD_W);
        int rows    = (int) ((pageSize.getHeight() - MARGIN * 2) / CARD_H);
        int perPage = Math.max(1, cols * rows);

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
                float x = MARGIN + (pos % cols) * CARD_W;
                float y = pageSize.getHeight() - MARGIN - (pos / cols + 1) * CARD_H;

                try {
                    byte[] imgBytes = r2Service.getImage(cardIds.get(i));
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, imgBytes, cardIds.get(i));
                    cs.drawImage(img, x, y, CARD_W, CARD_H);
                } catch (Exception e) {
                    // No image — draw a placeholder border
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
            @JsonProperty("orientation") String orientation
    ) {}
}
