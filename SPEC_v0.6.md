# REST Service Comparator — Specification v0.6

Spring Boot app that calls two versions of a REST service (or reads two JSON documents) and reports the
**semantic** differences between them. *Source* is the reference; *target* is what is checked against it.
The report says what differs, never which side is right.

## 1. What is new in 0.6
| # | Change |
|---|--------|
| 1 | **One folder per compared path** in each live run, holding `source.json`, `target.json` and `report.json` (the report of that path, including the locations of its siblings) |
| 2 | **A `report.json` for the whole run** at the root of the run folder |
| 3 | Results gain `reportFile`; `sourceFile` / `targetFile` now point into the path folder (`{run}/{name}/source.json`) |
| 4 | `ERROR` and `IDENTICAL` paths are saved too, so every path of a run leaves a report |
| 5 | Version `0.6.0` (Maven, OpenAPI info) |

0.5 (see `SPEC_v0.5.md`): order-insensitive arrays with id pairing, `orderStrict` / `idProperties`, `null` is a value,
`location` on differences, explicit unmatched-element messages. Older: 0.4 file upload, 0.3 `nullMatching`,
0.2 `typeMatching`, 0.1 `/compare/custom` and Swagger.

## 2. Stack
Spring Boot 3.5.x, Java 17, Maven. Standard Spring Boot features, `RestClient`, Jackson `JsonNode`; plus
`springdoc-openapi-starter-webmvc-ui` 2.8.x (Swagger UI). No diff library, no WebFlux. Records preferred.

## 3. Configuration (`application.yml`)
```yaml
spring.servlet.multipart: { max-file-size: 50MB, max-request-size: 100MB }   # /compare/files uploads
source_service: { base-url: ..., client-id: ${CLIENT_ID}, client-secret: ${CLIENT_SECRET},
                  connect-timeout: 5s, read-timeout: 30s, trusted-base-urls: [] }
target_service: { base-url: ..., connect-timeout: 5s, read-timeout: 30s }
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
  3. **Unmatched elements** are reported, never guessed: an unmatched source element is `MISSING_IN_TARGET`
     (at its source index), an unmatched target element is `MISSING_IN_SOURCE` (at its target index).

  Example, `["a","b","c"]` vs `["a","b","e"]`:
  - `$.t[2]: element "c" is in source but has no match in target` (`MISSING_IN_TARGET`)
  - `$.t[2]: element "e" is in target but has no match in source` (`MISSING_IN_SOURCE`)

  Consequence: two objects *without* a usable id that differ in any field are reported as one missing and one
  extra element, not as a field difference. Configure `idProperties` or `unordered-arrays.key` to get field-level
  detail.
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
| `POST /compare` | none (configuration) | calls both services for every configured path, saves responses |
| `POST /compare/custom` | `{sourceBaseUrl, targetBaseUrl, paths[]}` | same, services/paths from the body; `400` on missing/invalid URL, empty or blank paths |
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

### 6.3 Credentials
- Configured source client always sends the credentials headers (warning at startup if unset, then no header).
- `/compare/custom`: sent only if the source URL equals `source_service.base-url` or a `trusted-base-urls` entry
  (trailing `/` ignored, scheme/host case-insensitive); never to a target.

## 7. API documentation
Swagger UI `/swagger-ui.html`, OpenAPI `/v3/api-docs` (info version `0.6`). One tag `Comparison`; every
endpoint has summary, description, ready-to-run example, and documents the four query parameters. Schemas come
from the records; annotations only in the controller and the config class. Switch off with
`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`. No authentication: do not expose
publicly (nor `/compare/custom`, which calls any URL it is given).

## 8. Structure
Flat package: properties, HTTP client config + factory, OpenAPI config, `JsonComparator` (+ `JsonPath`),
`CompareService`, `FileStorageService`, `CompareController`, report records (`CompareReport`, `PathResult`,
`Difference`, enums, `CompareOptions`).

## 9. Tests (must exist)
- **Comparator**: property order; numbers; ignore paths; each row of 4.2 (notably `"jjj"` vs `null`, `null` vs
  object/array → `VALUE_MISMATCH`); `typeMatching` and `nullMatching` matrices; arrays: strict order, unordered
  scalars, id detection and priority, duplicate/missing ids, `idProperties` override, `unordered-arrays` key,
  equality fallback, nested arrays, `a,b,c` vs `a,b,e` in both modes.
- **Identification**: `location` with id, with field description, for scalars, omitted without arrays; message
  wording of 5 for every difference type.
- **Symmetry**: randomized source/target swap gives the mirrored result for all option combinations.
- **Endpoints** (fake source/target services): full detail, `ERROR` cases, `STATUS_MISMATCH`, saved files,
  credentials only to trusted sources, one folder per path with `source.json`, `target.json`, `report.json`
  (also for `ERROR` / `IDENTICAL`) and the run `report.json`, file upload cases, inline JSON, every query parameter incl. `400` on
  invalid values and `orderStrict` shown in the summary; Swagger documents the four parameters.

## 10. Done when
`mvn clean verify` passes; the app starts with the sample config; all four endpoints return the report above
with `orderStrict`, `location` and `reportFile`, and write the folder layout of 6.2; Swagger UI runs every example; README explains running,
env vars and the Swagger URL.
