package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.StagedChange;
import com.drive.drive_manager.repository.StagedChangeRepository;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DriveParser {

    private static final Logger logger = LoggerFactory.getLogger(DriveParser.class);
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String STRUCTURES_FOLDER = "STRUCTURES";
    private static final String STRUCTURES_PREFIX = "ST";
    private static final String EDITION_PREFIX = "E";
    private static final String[] EDITION_SECTIONS = {"MAIN", "SUB1", "SUB2", "SUB3"};
    private static final String[] EDITION_COLORS = {"B", "G", "P", "R", "W"};

    // Pattern: {edition}-{color}{number} {name}.jpg
    // edition = ST\d+ | E\d+ | E\d+\.\d+
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "^((?:ST|E)\\d+(?:\\.\\d+)?)-([BGPRW])(\\d+)\\s+(.+)\\.jpe?g$",
            Pattern.CASE_INSENSITIVE
    );

    private final DriveClientFactory driveClientFactory;
    private final String baseFolderId;
    private final StagedChangeRepository stagedChangeRepository;

    public DriveParser(
            DriveClientFactory driveClientFactory,
            @Value("${drive.base-folder-id}") String baseFolderId,
            StagedChangeRepository stagedChangeRepository
    ) {
        this.driveClientFactory = driveClientFactory;
        this.baseFolderId = baseFolderId;
        this.stagedChangeRepository = stagedChangeRepository;
    }

    // ── Existing: full drive listing ─────────────────────────────────────────

    public CardsResponse listCards(String folderUrl) throws IOException {
        String rootFolderId = resolveFolderId(folderUrl);
        try {
            Drive drive = driveClientFactory.create();
            Map<String, List<ImageDoc>> structures = buildStructures(drive, rootFolderId);
            Map<String, Map<String, Map<String, List<ImageDoc>>>> editions = buildEditions(drive, rootFolderId);
            return new CardsResponse(structures, editions);
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error creating Drive client", e);
        }
    }

    public String resolveFolderId(String folderUrl) {
        if (folderUrl == null || folderUrl.isBlank()) {
            return baseFolderId;
        }
        String extracted = extractFolderId(folderUrl);
        if (extracted == null || extracted.isBlank()) {
            throw new IllegalArgumentException("Invalid folder URL: " + folderUrl);
        }
        return extracted;
    }

    // ── New: filtered sync with MongoDB persistence ───────────────────────────

    /**
     * Scans Google Drive and stages all matching files into staged_changes.
     * No images are downloaded here — apply the staged changes afterwards to
     * download from Drive, upload to R2, and write to drive_cards.
     *
     * @param editions    folder names to include, e.g. ["ST1","E1"]. Empty = all.
     * @param subEditions section names to include, e.g. ["MAIN","SUB1"]. Empty = all.
     * @param colors      color letters to include, e.g. ["B","G"]. Empty = all.
     */
    public SyncResult syncCards(List<String> editions, List<String> subEditions, List<String> colors)
            throws IOException {
        try {
            Drive drive = driveClientFactory.create();

            List<String> eds = normalize(editions);
            List<String> subs = normalize(subEditions);
            List<String> cols = normalize(colors);

            boolean noEditionFilter = eds.isEmpty() && subs.isEmpty();

            List<StagedChange> staged = new ArrayList<>();
            Instant now = Instant.now();

            boolean processStructures = noEditionFilter || eds.stream().anyMatch(e -> e.startsWith(STRUCTURES_PREFIX));
            if (processStructures) {
                staged.addAll(stageStructures(drive, eds, cols, now));
            }

            boolean processEditions = noEditionFilter || eds.stream().anyMatch(e -> e.startsWith(EDITION_PREFIX));
            if (processEditions) {
                staged.addAll(stageEditions(drive, eds, subs, cols, now));
            }

            stagedChangeRepository.saveAll(staged);
            logger.info("Staged {} file(s) from Drive bulk scan.", staged.size());
            return new SyncResult(staged.size(), staged);

        } catch (GeneralSecurityException e) {
            throw new IOException("Security error creating Drive client", e);
        }
    }

    /**
     * Scans STRUCTURES → ST* → .jpg files and returns StagedChange entries.
     */
    private List<StagedChange> stageStructures(Drive drive, List<String> editionFilter,
                                               List<String> colorFilter, Instant now) throws IOException {
        Map<String, FolderInfo> rootFolders = listChildFoldersByName(drive, baseFolderId);
        FolderInfo structuresFolder = rootFolders.get(STRUCTURES_FOLDER);
        if (structuresFolder == null) {
            logger.warn("STRUCTURES folder not found under root: {}", baseFolderId);
            return List.of();
        }

        List<FolderInfo> stFolders = listChildFolders(drive, structuresFolder.id);
        stFolders.removeIf(f -> !f.name.startsWith(STRUCTURES_PREFIX));

        if (!editionFilter.isEmpty()) {
            Set<String> stFilter = editionFilter.stream()
                    .filter(e -> e.startsWith(STRUCTURES_PREFIX))
                    .collect(Collectors.toSet());
            stFolders.removeIf(f -> !stFilter.contains(f.name));
        }

        List<StagedChange> staged = new ArrayList<>();
        for (FolderInfo stFolder : stFolders) {
            for (RawFile file : listRawFiles(drive, stFolder.id)) {
                ParsedFileName parsed = parseFileName(file.name);
                if (parsed == null) {
                    logger.warn("Could not parse filename: {}", file.name);
                    continue;
                }
                if (!colorFilter.isEmpty() && !colorFilter.contains(parsed.color)) {
                    continue;
                }
                staged.add(new StagedChange(
                        file.id, file.name,
                        parsed.name, parsed.number, parsed.color,
                        parsed.edition, null, "upsert", now
                ));
            }
        }
        return staged;
    }

    /**
     * Scans E* → {MAIN,SUB1,SUB2,SUB3} → {B,G,P,R,W} → .jpg files and returns StagedChange entries.
     */
    private List<StagedChange> stageEditions(Drive drive, List<String> editionFilter,
                                             List<String> subEditionFilter, List<String> colorFilter,
                                             Instant now) throws IOException {
        List<FolderInfo> rootFolders = listChildFolders(drive, baseFolderId);
        rootFolders.removeIf(f -> !f.name.startsWith(EDITION_PREFIX));

        if (!editionFilter.isEmpty()) {
            Set<String> eFilter = editionFilter.stream()
                    .filter(e -> e.startsWith(EDITION_PREFIX))
                    .collect(Collectors.toSet());
            rootFolders.removeIf(f -> !eFilter.contains(f.name));
        }

        String[] sections = subEditionFilter.isEmpty()
                ? EDITION_SECTIONS
                : subEditionFilter.toArray(new String[0]);
        String[] colors = colorFilter.isEmpty()
                ? EDITION_COLORS
                : colorFilter.toArray(new String[0]);

        List<StagedChange> staged = new ArrayList<>();
        for (FolderInfo editionFolder : rootFolders) {
            Map<String, FolderInfo> sectionFolders = listChildFoldersByName(drive, editionFolder.id);

            for (String section : sections) {
                FolderInfo sectionFolder = sectionFolders.get(section);
                if (sectionFolder == null) continue;

                String subEditionValue = section.equals("MAIN") ? null : section.replace("SUB", "");
                Map<String, FolderInfo> colorFolders = listChildFoldersByName(drive, sectionFolder.id);

                for (String color : colors) {
                    FolderInfo colorFolder = colorFolders.get(color);
                    if (colorFolder == null) continue;

                    for (RawFile file : listRawFiles(drive, colorFolder.id)) {
                        ParsedFileName parsed = parseFileName(file.name);
                        if (parsed == null) {
                            logger.warn("Could not parse filename: {}", file.name);
                            continue;
                        }
                        staged.add(new StagedChange(
                                file.id, file.name,
                                parsed.name, parsed.number, parsed.color,
                                parsed.edition, subEditionValue, "upsert", now
                        ));
                    }
                }
            }
        }
        return staged;
    }

    // ── Filename parsing ─────────────────────────────────────────────────────

    public ParsedFileName parseFileName(String filename) {
        Matcher m = FILE_NAME_PATTERN.matcher(filename);
        if (!m.matches()) return null;

        String fullEdition = m.group(1).toUpperCase(Locale.ROOT);
        String color = m.group(2).toUpperCase(Locale.ROOT);
        int number = Integer.parseInt(m.group(3));
        String name = m.group(4);

        int dotIndex = fullEdition.indexOf('.');
        String edition = dotIndex >= 0 ? fullEdition.substring(0, dotIndex) : fullEdition;
        String subEdition = dotIndex >= 0 ? fullEdition.substring(dotIndex + 1) : null;

        return new ParsedFileName(edition, subEdition, color, number, name);
    }

    private List<String> normalize(List<String> list) {
        if (list == null) return List.of();
        return list.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    // ── Drive helpers ────────────────────────────────────────────────────────

    private Map<String, List<ImageDoc>> buildStructures(Drive drive, String rootFolderId) throws IOException {
        Map<String, FolderInfo> rootFolders = listChildFoldersByName(drive, rootFolderId);
        FolderInfo structuresFolder = rootFolders.get(STRUCTURES_FOLDER);
        if (structuresFolder == null) {
            logger.warn("STRUCTURES folder not found under root: {}", rootFolderId);
            return Map.of();
        }

        List<FolderInfo> structureFolders = listChildFolders(drive, structuresFolder.id);
        structureFolders.removeIf(folder -> !folder.name.startsWith(STRUCTURES_PREFIX));
        structureFolders.sort(Comparator.comparing(folder -> folder.name));

        Map<String, List<ImageDoc>> structures = new LinkedHashMap<>();
        for (FolderInfo folder : structureFolders) {
            structures.put(folder.name, listJpgImages(drive, folder.id));
        }
        return structures;
    }

    private Map<String, Map<String, Map<String, List<ImageDoc>>>> buildEditions(Drive drive, String rootFolderId)
            throws IOException {
        List<FolderInfo> rootFolders = listChildFolders(drive, rootFolderId);
        rootFolders.removeIf(folder -> !folder.name.startsWith(EDITION_PREFIX));
        rootFolders.sort(Comparator.comparing(folder -> folder.name));

        Map<String, Map<String, Map<String, List<ImageDoc>>>> editions = new LinkedHashMap<>();
        for (FolderInfo edition : rootFolders) {
            Map<String, FolderInfo> editionFolders = listChildFoldersByName(drive, edition.id);
            Map<String, Map<String, List<ImageDoc>>> sections = new LinkedHashMap<>();

            for (String section : EDITION_SECTIONS) {
                FolderInfo sectionFolder = editionFolders.get(section);
                Map<String, List<ImageDoc>> colorMap = new LinkedHashMap<>();

                if (sectionFolder != null) {
                    Map<String, FolderInfo> colorFolders = listChildFoldersByName(drive, sectionFolder.id);
                    for (String color : EDITION_COLORS) {
                        FolderInfo colorFolder = colorFolders.get(color);
                        colorMap.put(color, colorFolder != null ? listJpgImages(drive, colorFolder.id) : List.of());
                    }
                } else {
                    for (String color : EDITION_COLORS) {
                        colorMap.put(color, List.of());
                    }
                }
                sections.put(section, colorMap);
            }
            editions.put(edition.name, sections);
        }
        return editions;
    }

    private List<ImageDoc> listJpgImages(Drive drive, String folderId) throws IOException {
        String pageToken = null;
        List<ImageDoc> images = new ArrayList<>();
        do {
            FileList fileList = drive.files().list()
                    .setQ("'" + folderId + "' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, webContentLink, webViewLink)")
                    .setPageToken(pageToken)
                    .execute();
            if (fileList.getFiles() != null) {
                for (com.google.api.services.drive.model.File file : fileList.getFiles()) {
                    if (isJpg(file)) {
                        images.add(new ImageDoc(file.getName(), buildDownloadUrl(file)));
                    }
                }
            }
            pageToken = fileList.getNextPageToken();
        } while (pageToken != null);

        images.sort(Comparator.comparing(ImageDoc::filename));
        return images;
    }

    private List<RawFile> listRawFiles(Drive drive, String folderId) throws IOException {
        String pageToken = null;
        List<RawFile> files = new ArrayList<>();
        do {
            FileList fileList = drive.files().list()
                    .setQ("'" + folderId + "' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, webContentLink)")
                    .setPageToken(pageToken)
                    .execute();
            if (fileList.getFiles() != null) {
                for (com.google.api.services.drive.model.File file : fileList.getFiles()) {
                    if (isJpg(file)) {
                        files.add(new RawFile(file.getId(), file.getName(), buildDownloadUrl(file)));
                    }
                }
            }
            pageToken = fileList.getNextPageToken();
        } while (pageToken != null);

        files.sort(Comparator.comparing(f -> f.name));
        return files;
    }

    private boolean isJpg(com.google.api.services.drive.model.File file) {
        String mimeType = file.getMimeType();
        if (mimeType != null && mimeType.equalsIgnoreCase("image/jpeg")) return true;
        String name = file.getName();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private String buildDownloadUrl(com.google.api.services.drive.model.File file) {
        if (file.getWebContentLink() != null && !file.getWebContentLink().isBlank()) {
            return file.getWebContentLink();
        }
        return "https://drive.google.com/uc?export=download&id=" + file.getId();
    }

    private List<FolderInfo> listChildFolders(Drive drive, String parentId) throws IOException {
        String pageToken = null;
        List<FolderInfo> folders = new ArrayList<>();
        do {
            FileList fileList = drive.files().list()
                    .setQ("'" + parentId + "' in parents and trashed = false and mimeType = '" + FOLDER_MIME + "'")
                    .setFields("nextPageToken, files(id, name)")
                    .setPageToken(pageToken)
                    .execute();
            if (fileList.getFiles() != null) {
                for (com.google.api.services.drive.model.File file : fileList.getFiles()) {
                    folders.add(new FolderInfo(file.getId(), file.getName()));
                }
            }
            pageToken = fileList.getNextPageToken();
        } while (pageToken != null);
        return folders;
    }

    private Map<String, FolderInfo> listChildFoldersByName(Drive drive, String parentId) throws IOException {
        List<FolderInfo> folders = listChildFolders(drive, parentId);
        Map<String, FolderInfo> map = new LinkedHashMap<>();
        for (FolderInfo folder : folders) {
            map.put(folder.name, folder);
        }
        return map;
    }

    private String extractFolderId(String folderUrl) {
        String trimmed = folderUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            String path = uri.getPath();
            if (path != null) {
                int folderIndex = path.indexOf("/folders/");
                if (folderIndex >= 0) {
                    String remainder = path.substring(folderIndex + "/folders/".length());
                    int end = findDelimiterIndex(remainder);
                    return end >= 0 ? remainder.substring(0, end) : remainder;
                }
            }
            String query = uri.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    if (pair.startsWith("id=")) {
                        return pair.substring("id=".length());
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // fall through to raw string parsing
        }

        int folderIndex = trimmed.indexOf("/folders/");
        if (folderIndex >= 0) {
            String remainder = trimmed.substring(folderIndex + "/folders/".length());
            int end = findDelimiterIndex(remainder);
            return end >= 0 ? remainder.substring(0, end) : remainder;
        }

        int idIndex = trimmed.indexOf("id=");
        if (idIndex >= 0) {
            String remainder = trimmed.substring(idIndex + "id=".length());
            int end = findDelimiterIndex(remainder);
            return end >= 0 ? remainder.substring(0, end) : remainder;
        }
        return null;
    }

    private int findDelimiterIndex(String value) {
        int question = value.indexOf('?');
        int slash = value.indexOf('/');
        int amp = value.indexOf('&');
        int hash = value.indexOf('#');
        int min = -1;
        if (question >= 0) min = question;
        if (slash >= 0 && (min == -1 || slash < min)) min = slash;
        if (amp >= 0 && (min == -1 || amp < min)) min = amp;
        if (hash >= 0 && (min == -1 || hash < min)) min = hash;
        return min;
    }

    // ── Records ──────────────────────────────────────────────────────────────

    private record FolderInfo(String id, String name) {}

    private record RawFile(String id, String name, String url) {}

    public record ParsedFileName(String edition, String subEdition, String color, int number, String name) {}

    public record ImageDoc(String filename, String url) {}

    public record CardsResponse(
            Map<String, List<ImageDoc>> structures,
            Map<String, Map<String, Map<String, List<ImageDoc>>>> editions
    ) {}

    public record SyncResult(int stagedCount, List<StagedChange> staged) {}
}
