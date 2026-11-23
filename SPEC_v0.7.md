# REST Service Comparator — Specification v0.7

Spring Boot app that calls two versions of a REST service (or reads two JSON documents) and reports the
**semantic** differences between them. *Source* is the reference; *target* is what is checked against it.
The report says what differs, never which side is right.

## 1. What is new in 0.7
| # | Change |
|---|--------|
| 1 | **Property-level detail for arrays without id.** Objects that cannot be paired by id or equality are matched with their most similar counterpart, so the report names the property that differs (`$.rows[0: name=bob, city=Paris, age=3].age`) instead of "object missing in target" + "object missing in source" |
| 2 | **Request headers.** `POST /compare/custom` accepts `sourceHeaders` and `targetHeaders` (name → value); `POST /compare` accepts the same two fields in an optional body. They apply to that call only, no restart needed |
| 3 | **Configured headers.** `source_service.headers` / `target_service.headers` in `application.yml` are sent on every call to the configured service |
| 4 | Header values are secrets: never logged, saved, or returned (also not in error messages) |
| 5 | Version `0.7.0` (Maven, OpenAPI info) |

0.6 (see `SPEC_v0.6.md`): folder per path with `source.json`, `target.json`, `report.json` and a run `report.json`.
0.5: order-insensitive arrays with id pairing, `orderStrict` / `idProperties`, `null` is a value, `location`.
Older: 0.4 file upload, 0.3 `nullMatching`, 0.2 `typeMatching`, 0.1 `/compare/custom` and Swagger.

## 2. Stack
Spring Boot 3.5.x, Java 17, Maven. Standard Spring Boot features, `RestClient`, Jackson `JsonNode`; plus
`springdoc-openapi-starter-webmvc-ui` 2.8.x (Swagger UI). No diff library, no WebFlux. Records preferred.

## 3. Configuration (`application.yml`)
```yaml
spring.servlet.multipart: { max-file-size: 50MB, max-request-size: 100MB }   # /compare/files uploads
source_service: { base-url: ..., client-id: ${CLIENT_ID}, client-secret: ${CLIENT_SECRET},
                  connect-timeout: 5s, read-timeout: 30s, trusted-base-urls: [],
                  headers: { }                # optional, e.g. { Authorization: "Bearer ${SOURCE_TOKEN}" }
target_service: { base-url: ..., connect-timeout: 5s, read-timeout: 30s,
                  headers: { } }              # optional, same
paths: [ /customers/42/orders, /products/1001 ]      # relative, appended as written, no templating
output: { directory: ./output }                      # where live runs save responses
comparison:                                          # defaults for every endpoint
  type-matching: STRICT          # STRICT | LENIENT
  null-matching: STRICT          # STRICT | LENIENT
  array-order: UNORDERED         # UNORDERED (default) | ORDERED  -> default of orderStrict
  id-properties: [id, uuid, guid, key, code, sku]    # candidate ids, in priority order
  ignore-paths: []               # e.g. $.generatedAt, $.items[*].id
  unordered-arrays: []           # e.g. { path: $.items, key: sku }  (explicit id per path)
```
Bound with `@ConfigurationProperties`. Per-request query parameters override the `comparison.*` defaults.

## 4. Comparison rules
Applied identically by all endpoints. One recursive comparator, options passed as one `CompareOptions` record
(`typeMatching`, `nullMatching`, `orderStrict`, `idProperties`; unset = configured default).

### 4.1 Value rules
- Object property order is ignored. Numbers compare by value (`1` == `1.0`).
- `ignore-paths` (and everything below) are skipped. `[*]` = any index, `.*` = any property.
- A missing document is *absent*: absent vs present → `MISSING_IN_*` at `$`.

### 4.2 Choosing the difference type
Evaluated top to bottom for each pair of values at the same place:

| Situation | Type |
|-----------|------|
| present on one side only | `MISSING_IN_SOURCE` / `MISSING_IN_TARGET` |
| **either side is `null`, the other is not** (scalar, object or array) | **`VALUE_MISMATCH`** |
| different JSON kinds (number/string/boolean/object/array) and no conversion applies | `TYPE_MISMATCH` |
| same kind (or converted under `LENIENT`), different content | `VALUE_MISMATCH` |
| HTTP status codes differ (live comparison) | `STATUS_MISMATCH` at `$status` |

### 4.3 `typeMatching`
- `STRICT`: `11` vs `"11"` → `TYPE_MISMATCH`.
- `LENIENT`: a string vs a number/boolean is converted first: `"11"`==`11`, `"1.50"`==`1.5`, `"true"`==`true`;
  `"12"` vs `11` → `VALUE_MISMATCH`; not convertible (`"abc"`, `" 11"`) → `TYPE_MISMATCH`.
  Also applies to id matching (`"id":"1"` pairs with `"id":1`). Reported values are always the originals.

### 4.4 `nullMatching`
- `STRICT`: `{"a":null}` vs `{}` → `MISSING_IN_*` (both directions).
- `LENIENT`: that case is not a difference, at any depth. Only for object properties exactly `null`;
  `null` vs a value (`VALUE_MISMATCH`), missing non-null properties and missing array elements stay reported.

### 4.5 Arrays
`orderStrict` (default `false`, from `comparison.array-order`):
- **`orderStrict=true`**: element *i* of source vs element *i* of target. `[a,b,c]` vs `[a,b,e]` →
  `VALUE_MISMATCH` at `[2]`; extra elements → `MISSING_IN_*`. Paths listed in `unordered-arrays` stay unordered.
- **`orderStrict=false`**: order does not matter. Elements are paired in three steps:
  1. **By id.** The id property is the explicit `key` of the path (`unordered-arrays`) or detected: the first of
     `idProperties` that is present as a scalar, non-null value in **every** element of **both** arrays with
     **unique** values on each side. Paired objects are compared field by field.
  2. **By equality** for what is left (also the only step when no id qualifies, and for scalars).
  3. **By similarity**, only when the array has no usable id: the remaining objects are matched with the most
     similar object of the other side, if they share at least **50%** of their leaf values (`path=value`,
     array positions ignored; Jaccard). Best pairs first; ties are broken on the content of the two objects,
     never on their side. Matched objects are compared property by property.
  4. **Unmatched elements** are reported: an unmatched source element is `MISSING_IN_TARGET`
     (at its source index), an unmatched target element is `MISSING_IN_SOURCE` (at its target index).
     Objects with different ids are never matched by similarity, and neither are scalars.

  Example, `["a","b","c"]` vs `["a","b","e"]`:
  - `$.t[2]: element "c" is in source but has no match in target` (`MISSING_IN_TARGET`)
  - `$.t[2]: element "e" is in target but has no match in source` (`MISSING_IN_SOURCE`)

  Example, no id: `[{"name":"bob","city":"Paris","age":3}]` vs `[{"name":"bob","city":"Paris","age":4}]` →
  one `VALUE_MISMATCH` at `$[0].age` (not a missing + an extra object). Two objects with less than 50% in common
  stay one missing and one extra element. An explicit id (`idProperties`, `unordered-arrays.key`) is still the
  most reliable way to pair objects.
- Swapping source and target yields the mirrored result (types `MISSING_IN_*` exchanged, values exchanged);
  only array indexes differ, since each is relative to its own side. Covered by a randomized test.

## 5. Difference model
```json
{ "jsonPath": "$.orders[0].lines[1].qty",
  "location": "$.orders[id=7].lines[code=X].qty",
  "type": "VALUE_MISMATCH", "source": 1, "target": 2,
  "message": "$.orders[id=7].lines[code=X].qty: source=1, target=2" }
```
- `jsonPath`: exact position (source index for paired elements). Stable, machine-friendly.
- `location`: same path with each array element identified. Omitted when no array is on the path.
  - Element with an id → `[id=7]` (the id property used for pairing, else the first candidate present).
  - Element without id → `[3: name=bob, age=3]` (index + up to 3 scalar fields, values cut at 30 chars).
  - Scalar element → `[2]`.
- `message`: human readable, uses `location` when present. Values are never truncated (objects/arrays as JSON).
  - value: `<location>: source=<v>, target=<v>`; type: `<location>: type differs, source=<v> (number), target=<v> (string)`
  - missing property: `<location>: present in source (<v>), missing in target` / `missing in source, present in target (<v>)`
  - unmatched array element: `<location>: element <v> is in source but has no match in target` (and the target twin)
- `source` / `target` hold the original values, `null` when missing (the `type` tells missing from a real `null`).

## 6. API
All endpoints accept the optional query parameters `typeMatching=STRICT|LENIENT`, `nullMatching=STRICT|LENIENT`,
`orderStrict=true|false`, `idProperties=id,code,...` (comma separated, overrides `comparison.id-properties`).
Invalid values → `400`. All return the same report:
```json
{ "summary": { "total": 3, "identical": 1, "different": 1, "errors": 1, "typeMatching": "STRICT",
               "nullMatching": "STRICT", "orderStrict": false },
  "results": [
    { "path": "/products/1001", "status": "IDENTICAL", "summary": "identical" },
    { "path": "/customers/42/orders", "status": "DIFFERENT", "summary": "2 differences",
      "differenceCount": 2, "byType": { "VALUE_MISMATCH": 1, "MISSING_IN_TARGET": 1 },
      "differences": [ ... see section 5 ... ],
      "sourceFile": "20261118-135000/customers_42_orders/source.json",
      "targetFile": "20261118-135000/customers_42_orders/target.json",
      "reportFile": "20261118-135000/customers_42_orders/report.json" },
    { "path": "/customers/43/orders", "status": "ERROR", "summary": "target_service returned HTTP 500" } ] }
```
- `status`: `IDENTICAL` | `ERROR` | `DIFFERENT`. One result per path, always with the full list of differences
  (no cap, no truncation). `IDENTICAL` has no `differences`; the summary reports the options actually used.

| Endpoint | Input | Effect |
|----------|-------|--------|
| `POST /compare` | optional `{sourceHeaders?, targetHeaders?}` | calls both services for every configured path, saves responses |
| `POST /compare/custom` | `{sourceBaseUrl, targetBaseUrl, paths[], sourceHeaders?, targetHeaders?}` | same, services/paths/headers from the body; `400` on missing/invalid URL, empty or blank paths, invalid headers |
| `POST /compare/files` | multipart `sourceFile`, `targetFile` | compares two uploaded JSON files in memory; nothing saved. Invalid/empty JSON → `ERROR` result; `400` missing part, `415` not multipart, `413` too large |
| `POST /compare/json` | `{name?, source, target}` (any JSON values, `null` allowed, must be present) | compares inline documents; `400` if `source`/`target` missing |

### 6.1 Live comparison (`/compare`, `/compare/custom`)
1. GET `{base}{path}` on both services. Only source calls send `client_id` / `client_secret`; never logged or saved.
2. Failed call / timeout → `ERROR "{service} call failed: {reason}"`; non-2xx without JSON body →
   `ERROR "{service} returned HTTP {code}"`; 2xx with invalid JSON → `ERROR`. An `ERROR` never stops the run.
3. Otherwise compare: status codes (`STATUS_MISMATCH`), then bodies per section 4.
4. Save the run (section 6.2). A save failure is a warning, never fatal.

### 6.2 Saved files (live comparison only)
```
{output.directory}/
  20261120-101500/                    one folder per run (-2, -3 if several runs start in the same second)
    report.json                       the full report of the run (summary + every result)
    customers_42_orders/              one folder per path
      source.json                     response of the source (pretty printed), if it returned JSON
      target.json                     response of the target, if it returned JSON
      report.json                     the result of this path: status, differences, file locations
    products_1001/ ...
```
- Folder name = path without leading `/`, characters outside `[A-Za-z0-9._-]` → `_`, empty → `root`;
  two paths giving the same name get `_2`, `_3`. Nothing is ever overwritten.
- Responses are saved even when the status is not 2xx, as long as the body is JSON. A missing body is not saved.
- Every path gets a `report.json`: `IDENTICAL`, `DIFFERENT` and `ERROR` alike.
- The per-path `report.json` equals the result returned by the API for that path, `sourceFile`, `targetFile`
  and `reportFile` included (names relative to `output.directory`, `/` separators).
- Credentials never appear in any saved file. `/compare/files` and `/compare/json` save nothing.

### 6.3 Credentials and headers
- Configured source client always sends the credentials headers (warning at startup if unset, then no header).
- `/compare/custom`: credentials and `source_service.headers` are sent only if the source URL equals
  `source_service.base-url` or a `trusted-base-urls` entry (trailing `/` ignored, scheme/host case-insensitive);
  never to a target. `target_service.headers` are sent only to `target_service.base-url`.
- **Request headers** (`sourceHeaders` → every call to the source, `targetHeaders` → every call to the target):
  - sent to whatever URL the caller gave, since the caller chose both; they win over configured headers and
    credentials with the same name (case-insensitive: `client_id` can be overridden for one call)
  - valid name (RFC 7230 token), no `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade`,
    `TE`, `Trailer`, `Expect`; no duplicate names; no CR/LF/NUL in values; at most 20 per side → otherwise `400`
    (the message names the header, never its value)
  - apply to that request only; nothing is stored. Values are never logged, saved in `output.directory`,
    returned in a report or an error, or shown in `toString` (only header names are printed)

## 7. API documentation
Swagger UI `/swagger-ui.html`, OpenAPI `/v3/api-docs` (info version `0.7`). One tag `Comparison`; every
endpoint has summary, description, ready-to-run example, and documents the four query parameters. Schemas come
from the records; annotations only in the controller and the config class. Switch off with
`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`. No authentication: do not expose
publicly (nor `/compare/custom`, which calls any URL it is given).

## 8. Structure (layers)
Package `com.example.comparator`, one sub package per layer. A layer only uses the layers on its right:
`controller → service → client / comparator → config → model`.

| Package | Role | Classes |
|---------|------|---------|
| `controller` | HTTP API, request validation, Swagger annotations; no business logic | `CompareController`, `dto/CustomCompareRequest`, `dto/HeadersRequest` |
| `service` | Use cases: live / file / inline comparison, saving responses and reports | `CompareService`, `FileStorageService` |
| `client` | Calls to the compared services: `RestClient` construction, credentials and headers | `RestClientFactory`, `HttpClientConfig`, `RequestHeaders` (header validation) |
| `comparator` | Pure JSON comparison (no Spring, no I/O apart from its bean definition) | `JsonComparator`, `JsonPath`, `ComparatorConfig` |
| `config` | Configuration properties and shared beans | `ComparatorProperties`, `AppConfig`, `OpenApiConfig` |
| `model` | Immutable records and enums shared by all layers | `CompareOptions`, `CompareReport`, `PathResult`, `Difference`, `DifferenceType`, `ResultStatus`, `TypeMatching`, `NullMatching` |

`RestComparatorApplication` (root package) only starts the application. A test (`LayeringTest`) fails the build
when a layer imports a layer it must not depend on. Tests sit in the same package as the class they test.

## 9. Tests (must exist)
- **Comparator**: property order; numbers; ignore paths; each row of 4.2 (notably `"jjj"` vs `null`, `null` vs
  object/array → `VALUE_MISMATCH`); `typeMatching` and `nullMatching` matrices; arrays: strict order, unordered
  scalars, id detection and priority, duplicate/missing ids, `idProperties` override, `unordered-arrays` key,
  equality fallback, similarity pairing (property named, nested property, below 50% stays missing + extra,
  different ids never paired), nested arrays, `a,b,c` vs `a,b,e` in both modes.
- **Identification**: `location` with id, with field description, for scalars, omitted without arrays; message
  wording of 5 for every difference type.
- **Layering**: `LayeringTest` enforces the dependency directions of section 8.
- **Symmetry**: randomized source/target swap gives the mirrored result for all option combinations.
- **Endpoints** (fake source/target services): full detail, `ERROR` cases, `STATUS_MISMATCH`, saved files,
  credentials only to trusted sources, request headers per side (override, never saved or returned, `400` on
  invalid names/values), one folder per path with `source.json`, `target.json`, `report.json`
  (also for `ERROR` / `IDENTICAL`) and the run `report.json`, file upload cases, inline JSON, every query parameter incl. `400` on
  invalid values and `orderStrict` shown in the summary; Swagger documents the four parameters.

## 10. Done when
`mvn clean verify` passes; the app starts with the sample config; all four endpoints return the report above
with `orderStrict`, `location`, `reportFile` and property-level differences, accept request headers, and write the folder layout of 6.2; Swagger UI runs every example; README explains running,
env vars and the Swagger URL.
