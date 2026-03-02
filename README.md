# Drive Manager Service API Documentation

This document describes the HTTP endpoints exposed by the Drive Manager microservice.  It is intended to help developers understand how the system works and how to interact with it programmatically.

> The attached PNG illustrates the high‑level workflow between users, the Spring Boot service, Google Drive, MongoDB, and R2 storage. 

![API workflow](drive_manager/docs/api_flow.png)

---

## Overview

The service is built with Spring Boot and exposes REST APIs under the `/api` prefix.  There are four logical groups:

1. **Card CRUD** – manage individual card metadata stored in MongoDB.
2. **Drive Operations** – query Google Drive, persist listings, and synchronize card images to R2.
3. **Webhook Receiver** – accept push notifications from Google Drive about file changes.
4. **Staged Changes** – review and apply Drive changes before they update the primary dataset.

All endpoints return JSON and use standard HTTP status codes.

## 1. Card Controller (`/api/cards`)

These endpoints operate on the `Card` DTO and are backed by `CardService`.

| Method | Path | Description | Body / Params | Response |
|--------|------|-------------|---------------|----------|
| POST | `/api/cards` | Create a new card | `Card` JSON | 201 created, card object |
| GET | `/api/cards/{id}` | Retrieve a card by ID | - | 200 + card or 404 |
| GET | `/api/cards` | List all cards | - | 200 + array |
| PUT | `/api/cards/{id}` | Update card fields | `Card` JSON | 200 + card or 404 |
| DELETE | `/api/cards/{id}` | Delete card | - | 204 or 404 |
| GET | `/api/cards/search/name` | Search by name | `?cardName=` | 200 + array |
| GET | `/api/cards/search/type` | Search by type | `?cardType=` | 200 + array |
| GET | `/api/cards/search/edition` | Search by edition | `?edition=` | 200 + array |
| GET | `/api/cards/search/regulation` | Search by regulation | `?regulation=` | 200 + array |
| GET | `/api/cards/search/class` | Search by class | `?cardClass=` | 200 + array |
| GET | `/api/cards/search/starter` | Fetch starter cards | - | 200 + array |

> **Note:** the various search endpoints simply delegate to the service layer and return all matching documents.

## 2. Drive Controller (`/api/drive`)

Utilities for interacting with Google Drive and the persisted card data.

### Health Check

- **GET** `/api/drive/health` – returns `DriveController is healthy`.

### Query persisted cards

- **GET** `/api/drive/cards/db` – list cards saved in MongoDB. Optional query parameters:
  - `edition` (e.g. `E1`)
  - `color` (single-letter identity, e.g. `B`)
  - `subEdition` (folder/section name)

Multiple filters can be combined; all parameters are case-insensitive.

### Read from Drive (no persistence)

- **GET** `/api/drive/cards`
  - Query parameter: `folderUrl` (Google Drive folder link, optional)
  - Parses Drive contents and returns a `CardsResponse` object (defined in `DriveParser`).

### Synchronize to database

- **POST** `/api/drive/cards/map_to_db`
  - Accepts a JSON body with optional lists: `editions`, `subEditions`, `colors`.
  - If omitted or empty, the entire Drive is parsed.
  - Returns a `SyncResult` describing how many files were processed, added, updated, etc.

Example request body:

```json
{
  "editions": ["ST1","E1"],
  "subEditions": ["MAIN","SUB1"],
  "colors": ["B","G"]
}
```

> See comments in source for rules about ST vs. E editions and color folder structure.

## 3. Drive Webhook (`/api/drive/webhook`)

Receives POST requests from Google Drive push notifications.  Required headers:

- `X-Goog-Channel-ID` – channel identifier used when subscribing.
- `X-Goog-Resource-State` – change state (`sync` for initial handshake or another value for real updates).

The controller acknowledges `sync` messages and passes other notifications to `DriveWatchService.processChanges()`.

> The public HTTPS URL configured in `application.properties` property `drive.webhook-url` must point to this endpoint.

## 4. Staged Changes (`/api/drive/staged`)

Before applying Drive changes to the main `drive_cards` collection and uploading images to R2, all events are stored as `StagedChange` documents.  This controller allows inspection and manual approval.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/drive/staged` | List all pending staged changes |
| POST | `/api/drive/staged/apply` | Apply **all** staged changes; returns `{"applied": n}` |
| POST | `/api/drive/staged/apply/{fileId}` | Apply a single change by file ID |
| DELETE | `/api/drive/staged/{fileId}` | Discard a staged change |

Errors during application are logged and returned with `500` and an `error` property.

## DTOs & Repositories

- `Card` – fields include `id`, `name`, `type`, `edition`, etc.
- `DriveCard` – same as `card` plus R2 URL, stored in MongoDB.
- `StagedChange` – captures Drive webhook payloads, with `fileId`, `action`, `timestamp`.

The data layer uses Spring Data MongoDB repositories; details are in `repository/` package.

## Sequence Flow

Refer to the **API workflow diagram** at the top of this document.  The typical lifecycle is:

1. Service starts, registers a Drive watch channel (Ngrok or public URL).
2. Drive sends push notifications to `/api/drive/webhook` when files change.
3. Notifications are stored as staged changes; clients can query via `/api/drive/staged`.
4. When changes are approved, `/api/drive/staged/apply` syncs the listing, downloads images, uploads to R2, and updates MongoDB.
5. Users (or other services) consume card data via `/api/drive/cards/db` or search via `/api/cards` endpoints.

## Usage Tips

- A running instance can be started with `./mvnw spring-boot:run`.
- Configuration values live in `src/main/resources/application.properties`.
- Use Postman, curl, or similar tools to exercise the endpoints.  Example:

```bash
curl -X GET http://localhost:8080/api/cards/search/name?cardName=Dragon
```

---

For additional details, see the Javadoc comments on each controller class and the service layer implementations.

*Last updated: 2026‑03‑02*