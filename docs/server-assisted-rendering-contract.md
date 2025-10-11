# Server-Assisted Rendering Wire Contract

This document defines the wire contract for a future server-assisted rendering
microservice. The goal is to make the Android client stateless with respect to
rendering state by ensuring every request contains all of the context required to
render a page or region.

## Transport Overview

* **Protocol:** gRPC over HTTP/2 is the canonical transport. JSON over HTTPS
  must remain in sync as a fallback for environments where gRPC is not
  available.
* **Authentication:** OAuth 2.0 bearer tokens scoped for `pdf.render` or higher.
* **Compression:** Both protobuf and JSON payloads SHOULD be compressed with
  gzip when the payload exceeds 8 KiB.
* **Versioning:** The `api_version` field is required on every request. Servers
  MUST support at least the latest minor version for the major version selected
  by the client. Backwards-compatible additions should use new optional fields to
  avoid breaking older clients.

## Protobuf Contract

```proto
syntax = "proto3";

package novapdf.rendering.v1;

// RenderService defines the remote entry points for server-assisted rendering.
service RenderService {
  rpc RenderPage(RenderPageRequest) returns (RenderPageResponse);
  rpc PrefetchDocument(PrefetchDocumentRequest) returns (PrefetchDocumentResponse);
}

message RenderPageRequest {
  // Client-supplied identifier that correlates logs and responses.
  string request_id = 1;

  // Version of the contract the client expects.
  string api_version = 2; // e.g., "1.0".

  // Describes the document to render.
  DocumentDescriptor document = 3;

  // Desired page number, starting at 1.
  uint32 page_number = 4;

  // Optional region of interest for partial rendering.
  Viewport viewport = 5;

  // Desired rendering characteristics.
  RenderSettings settings = 6;

  // Context for telemetry and rate limiting; allows stateless clients.
  ClientContext context = 7;
}

message DocumentDescriptor {
  oneof source {
    // Permanent ID for documents already uploaded to the service.
    string document_id = 1;

    // Byte range locator for streaming sources.
    RemoteAsset remote_asset = 2;

    // Raw content hash for deduplication.
    bytes sha256 = 3;
  }

  // Human-readable file name for logging.
  string filename = 4;
}

message RemoteAsset {
  string url = 1;
  string http_etag = 2;
  string authorization_token = 3;
}

message Viewport {
  // Coordinates are expressed in PDF points.
  float x = 1;
  float y = 2;
  float width = 3;
  float height = 4;
  // Resolution hint in DPI; defaults to 72.
  uint32 dpi = 5;
}

message RenderSettings {
  enum ImageFormat {
    IMAGE_FORMAT_UNSPECIFIED = 0;
    IMAGE_FORMAT_PNG = 1;
    IMAGE_FORMAT_JPEG = 2;
    IMAGE_FORMAT_WEBP = 3;
  }

  ImageFormat format = 1;
  uint32 quality = 2; // 0-100 for lossy formats, ignored otherwise.
  bool include_annotations = 3;
  bool prefer_vector = 4; // If true, server may return vector payloads.
}

message ClientContext {
  string device_model = 1;
  string os_version = 2;
  string app_version = 3;
  string locale = 4;
  map<string, string> metadata = 5;
}

message RenderPageResponse {
  string request_id = 1;
  bytes payload = 2; // Image or vector bytes, format declared below.
  PayloadDescriptor descriptor = 3;
  repeated Annotation annotations = 4; // Optional overlay annotations.
  CacheControl cache_control = 5;
}

message PayloadDescriptor {
  RenderSettings.ImageFormat format = 1;
  uint32 width = 2;
  uint32 height = 3;
  string vector_mime_type = 4; // e.g., "application/pdf" when prefer_vector.
}

message Annotation {
  string id = 1;
  string type = 2;
  bytes data = 3; // Annotation payload (e.g., JSON, ink path, etc.).
}

message CacheControl {
  // Suggested TTL in seconds for this rendered payload.
  uint32 max_age_seconds = 1;
  bool allow_persistent_storage = 2;
}

message PrefetchDocumentRequest {
  string request_id = 1;
  string api_version = 2;
  DocumentDescriptor document = 3;
  repeated uint32 page_numbers = 4;
  ClientContext context = 5;
}

message PrefetchDocumentResponse {
  string request_id = 1;
  repeated PrefetchResult results = 2;
}

message PrefetchResult {
  uint32 page_number = 1;
  bool success = 2;
  string error_code = 3;
  string error_message = 4;
}
```

## JSON Contract

The JSON contract mirrors the protobuf messages. Field names use camelCase.
Binary fields are Base64-encoded. Example `RenderPageRequest` payload:

```json
{
  "requestId": "2cbe1c8d-5d13-4e52-8194-9f2f07d5472f",
  "apiVersion": "1.0",
  "document": {
    "documentId": "doc_12345",
    "filename": "sample.pdf"
  },
  "pageNumber": 5,
  "viewport": {
    "x": 0,
    "y": 0,
    "width": 612,
    "height": 792,
    "dpi": 144
  },
  "settings": {
    "format": "IMAGE_FORMAT_PNG",
    "quality": 90,
    "includeAnnotations": true,
    "preferVector": false
  },
  "context": {
    "deviceModel": "Pixel 8",
    "osVersion": "Android 15",
    "appVersion": "2.4.0",
    "locale": "en-US",
    "metadata": {
      "experiment": "renderer_v2"
    }
  }
}
```

Example `RenderPageResponse`:

```json
{
  "requestId": "2cbe1c8d-5d13-4e52-8194-9f2f07d5472f",
  "payload": "iVBORw0KGgoAAAANSUhEUgAA...",
  "descriptor": {
    "format": "IMAGE_FORMAT_PNG",
    "width": 1536,
    "height": 1984,
    "vectorMimeType": ""
  },
  "annotations": [
    {
      "id": "ann_001",
      "type": "highlight",
      "data": "eyJ4IjogMTAwfQ=="
    }
  ],
  "cacheControl": {
    "maxAgeSeconds": 300,
    "allowPersistentStorage": true
  }
}
```

## Stateless Client Requirements

To keep the client stateless with respect to rendering state:

1. **No session affinity:** Every request includes the full `DocumentDescriptor`,
   desired `pageNumber`, optional `viewport`, and `RenderSettings`. The server
   must not rely on prior requests to infer state.
2. **Idempotent retries:** Clients can retry a request using the same
   `requestId`; servers should treat duplicates as the same operation.
3. **Prefetch hints:** Clients may optimistically call `PrefetchDocument` for a
   page range before rendering, but the server must never assume prefetch has
   occurred.
4. **Server-managed cache:** Any cache keys derive from request payloads. The
   client simply respects the `CacheControl` directives, storing payloads locally
   when permitted.
5. **Telemetry isolation:** `ClientContext` provides diagnostic metadata without
   allowing the server to mutate client state.

By encoding every required piece of context into each RPC, the client can remain
stateless while still enabling rich server-driven rendering behavior.
