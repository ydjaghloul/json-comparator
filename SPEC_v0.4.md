# SPEC: REST Service Comparator — v0.4

## Changes from v0.3
- `POST /compare/files` now takes an upload of two JSON files (`multipart/form-data`, parts `sourceFile` and `targetFile`) instead of two paths under `output.directory`
- Uploaded files are compared in memory: nothing is read from or written to `output.directory`
- The path-based mode is removed, with its path resolution and traversal checks (no file system access left to protect)
- Upload size limits are configured (`spring.servlet.multipart.*`)

## Changes from v0.2
- New parameter `nullMatching` (`STRICT` | `LENIENT`) on every endpoint, with a configurable default. In `LENIENT` mode a property that is `null` on one side and absent on the other is not a difference
- The report `summary` also states the `nullMatching` mode used
- OpenAPI info version `0.3`; Swagger documents `nullMatching`

## Changes from v0.1
- New endpoint `POST /compare/custom`: base URLs of both services and the list of relative paths are given in the request instead of the configuration
- API documented with OpenAPI 3; Swagger UI at `/swagger-ui.html` (see "API documentation (Swagger)")
- New parameter `typeMatching` (`STRICT` | `LENIENT`) on every endpoint, with a configurable default. In `LENIENT` mode `"11"` equals `11` and `"true"` equals `true`
- The report `summary` states the `typeMatching` mode used (and, since v0.3, `nullMatching`)
- Behaviors left open in v0.1 are now specified (status codes vs `ERROR`, number equality, file name collisions, credentials)

## Goal
Spring Boot app that calls two versions of a REST service with the same relative paths and reports semantic JSON differences.

## Stack
- Spring Boot 3.5.x, Java 17, Maven
- Lombok allowed; prefer records where simpler
- Standard Spring Boot features only, plus one exception: `org.springdoc:springdoc-openapi-starter-webmvc-ui` 2.8.x for Swagger UI
  (2.8.x is the line for Spring Boot 3.x; springdoc 3.x requires Spring Boot 4)
- No diff library, no WebFlux/WebClient
- Reflection allowed only if really needed (should not be)

## Configuration (`application.yml`)
```yaml
spring:
  servlet:
    multipart:                 # limits for POST /compare/files uploads
      max-file-size: 50MB      # Spring Boot default is 1MB
      max-request-size: 100MB
source_service:
  base-url: https://source.example.com
  client-id: ${CLIENT_ID}
  client-secret: ${CLIENT_SECRET}
  connect-timeout: 5s          # optional, default 5s
  read-timeout: 30s            # optional, default 30s
  trusted-base-urls: []        # optional, see "Credentials"
target_service:
  base-url: https://target.example.com
  connect-timeout: 5s
  read-timeout: 30s
paths:
  - /customers/42/orders
  - /customers/43/orders
  - /products/1001
output:
  directory: ./output
comparison:                    # optional, applies to every endpoint
  type-matching: STRICT        # default typeMatching: STRICT | LENIENT
  null-matching: STRICT        # default nullMatching: STRICT | LENIENT
  ignore-paths: []             # e.g. $.generatedAt, $.items[*].id
  array-order: ORDERED         # ORDERED | UNORDERED, for arrays not listed below
  unordered-arrays: []         # e.g. { path: $.items, key: sku }
```
- Bind with `@ConfigurationProperties`
- `output.directory` = root folder where live comparisons (`/compare`, `/compare/custom`) save the responses
- `paths` = flat list of relative paths, parameters already included, used for both services
- No path templating: append each path to each base URL as is (no re-encoding)

## Behavior (live comparison: `/compare` and `/compare/custom`)
1. For each path, GET `{source base URL}{path}` and `{target base URL}{path}`
2. Only source calls may send headers `client_id` and `client_secret` (see "Credentials"). Never log them
3. Compare status codes, then JSON bodies semantically (not as text):
   - Object property order ignored
   - Array order: ordered by default; optional config to compare as unordered, optionally matched by a key field
   - Types: see "Type matching"
   - A property `null` on one side and absent on the other: see "Null matching"
   - Numbers compare by value: `1` == `1.0`
   - Optional config list of JSON paths to ignore (timestamps, generated IDs)
4. Result per path:
   - A failed call or timeout → `ERROR` (`"{service} call failed: {reason}"`)
   - A non-2xx response without a JSON body → `ERROR` (`"target_service returned HTTP 500"`)
   - A 2xx response whose body is not valid JSON → `ERROR`
   - Otherwise compared: different status codes give a `STATUS_MISMATCH` difference, plus the body differences
   - An `ERROR` never stops the run
5. Every JSON response received is saved to disk (see "Saving responses")

## Type matching
Parameter `typeMatching`, default `comparison.type-matching` (itself default `STRICT`).
- `STRICT`: types must match. `11` vs `"11"` → `TYPE_MISMATCH`
- `LENIENT`: a string and a number or boolean are compared after converting the string:
  - `"11"` == `11`, `"1.50"` == `1.5`, `"true"` == `true`
  - `"12"` vs `11` → `VALUE_MISMATCH`
  - `"abc"` vs `11` → `TYPE_MISMATCH` (not convertible)
  - number vs boolean, `null` vs a value, object vs array, scalar vs container → still `TYPE_MISMATCH`
  - The string must be an exact number (`"11 "` is not converted) or exactly `true` / `false`
  - Applies to key matching of unordered arrays too (`"id": "1"` pairs with `"id": 1`)
- The difference values in the report are always the original values (`"11"` stays a string)

## Null matching
Parameter `nullMatching`, default `comparison.null-matching` (itself default `STRICT`). Independent of `typeMatching`.
- `STRICT`: a property that is `null` on one side and absent on the other is a difference:
  ```json
  { "jsonPath": "$.discount", "type": "MISSING_IN_TARGET", "source": null, "target": null,
    "message": "$.discount: present in source (null), missing in target" }
  ```
- `LENIENT`: that case is not a difference, in both directions (`{"a": null}` vs `{}` and `{}` vs `{"a": null}`), at any depth, including inside unordered arrays
- `LENIENT` only concerns object properties whose value is exactly `null`. Still reported in every mode:
  - a missing property with a non-null value (`0`, `""`, `false`, `{}`, `[]` are not `null`) → `MISSING_IN_*`
  - `null` vs a value (`{"a": null}` vs `{"a": 1}`) → `TYPE_MISMATCH` (or as defined by `typeMatching`)
  - a missing array element, even `null` (`[1, null]` vs `[1]`) → `MISSING_IN_*`
  - a missing whole body (no response body vs a JSON body) → `MISSING_IN_*` at `$`
- In a reported difference, a missing value is shown as `"target": null` / `"source": null`; the `type` tells whether it is missing (`MISSING_IN_*`) or an actual `null`

## Credentials
- `source_service` (configured): the client always sends `client_id` / `client_secret` as default headers
- `/compare/custom`: credentials are sent to the given source base URL **only** if it equals `source_service.base-url` or one of `source_service.trusted-base-urls` (compared without trailing `/`, scheme and host case-insensitive). Otherwise the call is made without credentials and a warning is logged. Credentials are never sent to a target URL
- If `CLIENT_ID` / `CLIENT_SECRET` are not set, the app starts, logs a warning and does not send the header
- Never write credentials into any saved file or log

## Saving responses
- Each live run (`/compare`, `/compare/custom`) creates a folder `{output.directory}/{yyyyMMdd-HHmmss}/`; a second run in the same second uses `{yyyyMMdd-HHmmss}-2`, etc.
- For each path, save the two responses as pretty-printed JSON: `{name}.source.json` and `{name}.target.json`
- `{name}` = the path with the leading `/` removed and every character outside `[A-Za-z0-9._-]` replaced by `_`
  (e.g. `/customers/42/orders` -> `customers_42_orders.source.json`)
- If the sanitized name is empty (path `/`), use `root`
- If two paths of one run give the same name, the later ones get `_2`, `_3`... (no overwrite)
- Save the response even when the status code is not 2xx, as long as the body is valid JSON. Skip saving when there is no body
- A save failure is logged as a warning and never fails the comparison
- Add the saved file names (relative to `output.directory`) to each result (`sourceFile`, `targetFile`) when they exist

## Uploaded file comparison
- Compare two JSON files uploaded by the client, without calling any service
- The files are parsed in memory; nothing is written to disk and nothing is read from `output.directory`
- The JSON encoding (UTF-8/16/32) is detected from the content
- Same comparator and the same ignore / array-order / type-matching / null-matching settings as the live comparison
- Typical use: re-compare two files saved by an earlier live run, or files from another source

## HTTP clients
- `RestClient` built from the auto-configured `RestClient.Builder`
- One `RestClient` bean per configured service, each with its own base URL and timeouts
- `/compare/custom` builds two clients per request the same way, with the base URLs from the request and the timeouts of the configured `source_service` / `target_service`
- Auth headers set as default headers on source clients only (see "Credentials")
- Timeouts via Spring Boot HTTP client settings
- Read responses as `String`/`JsonNode`, never mapped DTOs

## JSON comparison
- Use the auto-configured `ObjectMapper` and `JsonNode`
- Small custom recursive comparator class; the options (`typeMatching`, `nullMatching`) are passed per comparison as one `CompareOptions` record, unset options fall back to the configuration

## API
All endpoints accept the optional query parameters `typeMatching=STRICT|LENIENT` and `nullMatching=STRICT|LENIENT` (invalid value → `400`; both can be combined) and return the same report:

```json
{
  "summary": { "total": 3, "identical": 1, "different": 1, "errors": 1,
               "typeMatching": "STRICT", "nullMatching": "STRICT" },
  "results": [
    { "path": "/products/1001", "status": "IDENTICAL", "summary": "identical" },
    {
      "path": "/customers/42/orders",
      "status": "DIFFERENT",
      "summary": "2 differences",
      "differenceCount": 2,
      "byType": { "VALUE_MISMATCH": 1, "MISSING_IN_TARGET": 1 },
      "differences": [
        { "jsonPath": "$.total", "type": "VALUE_MISMATCH", "source": 100, "target": 105,
          "message": "$.total: source=100, target=105" },
        { "jsonPath": "$.items[1].discount", "type": "MISSING_IN_TARGET", "source": 5, "target": null,
          "message": "$.items[1].discount: present in source (5), missing in target" }
      ],
      "sourceFile": "20261118-135000/customers_42_orders.source.json",
      "targetFile": "20261118-135000/customers_42_orders.target.json"
    },
    { "path": "/customers/43/orders", "status": "ERROR", "summary": "target_service returned HTTP 500" }
  ]
}
```
- One result per path, always with the full detail of what differs (never just a count)
- `status`: `IDENTICAL` | `DIFFERENT` | `ERROR`
- Difference `type`: `MISSING_IN_SOURCE` | `MISSING_IN_TARGET` | `VALUE_MISMATCH` | `TYPE_MISMATCH` | `STATUS_MISMATCH`
- Every difference is listed in full: no cap on the number of differences and no truncation of values (a value that is an object or array is shown as JSON)
- Each difference has: `jsonPath` (exact location, e.g. `$.items[1].discount`), `type`, `source` value, `target` value (`null` when missing), and a short human-readable `message`
- `source` is the reference. The report states what differs, not which side is right
- A different HTTP status code is a `STATUS_MISMATCH` difference (`jsonPath` = `$status`, with both codes)
- `differenceCount` and `byType` on each `DIFFERENT` result; `IDENTICAL` results have no `differences` field
- Full response bodies are not duplicated in the report: they are in the saved files
- In unordered arrays, an unpaired element is reported at its own index (source index for `MISSING_IN_TARGET`, target index for `MISSING_IN_SOURCE`)

### `POST /compare`
Runs all configured paths against the configured services, saves the responses, returns the report.

### `POST /compare/custom`
Same as `POST /compare`, with services and paths from the request body:
```json
{ "sourceBaseUrl": "https://source-v2.example.com",
  "targetBaseUrl": "https://target-v3.example.com",
  "paths": ["/customers/42/orders", "/products/1001?lang=fr"] }
```
- All three fields are required. `400` if a base URL is missing or not an absolute `http`/`https` URL, if `paths` is missing or empty, or if a path is blank
- Credentials: see "Credentials"
- Responses are saved exactly as for `POST /compare`

### `POST /compare/files`
Compares two uploaded JSON files. No service is called and nothing is saved.
- Request: `Content-Type: multipart/form-data` with two file parts:
  - `sourceFile`: the reference JSON file
  - `targetFile`: the JSON file to compare with it
- Example:
  ```bash
  curl -X POST 'http://localhost:8080/compare/files?typeMatching=LENIENT' \
    -F sourceFile=@output/20261118-135000/customers_42_orders.source.json \
    -F targetFile=@output/20261118-135000/customers_42_orders.target.json
  ```
- `path` in the result is the original name of the source file, without any directory part (`orders.json`); `source.json` if the client sent no name
- No `sourceFile` / `targetFile` fields in the result (nothing is saved)
- A file that is empty or not valid JSON → `ERROR` result (`"target file t.json is not valid JSON: ..."`), not an HTTP error
- `400` if a part is missing; `415` if the request is not `multipart/form-data`; `413` if a file exceeds `spring.servlet.multipart.max-file-size` or the request exceeds `max-request-size`

### `POST /compare/json`
Compares two JSON documents sent in the request body. No service is called and nothing is saved:
```json
{ "name": "optional label",
  "source": { "total": 100, "items": [1, 2] },
  "target": { "items": [1, 2], "total": 105 } }
```
- `source` and `target` are required and can be any JSON value (object, array, string, number, boolean, null)
- `name` is optional, defaults to `inline`, used as `path` in the result
- `400` if `source` or `target` is missing

## API documentation (Swagger)
- Library: `springdoc-openapi-starter-webmvc-ui` 2.8.x, version set by the Maven property `springdoc.version`
- URLs (default port 8080):
  - Swagger UI: `http://localhost:8080/swagger-ui.html` (redirects to `/swagger-ui/index.html`)
  - OpenAPI 3 description (JSON): `http://localhost:8080/v3/api-docs`
- API info: title `REST Service Comparator`, version `0.4`, short description, set in one `OpenAPI` bean
- All four endpoints are grouped under one tag `Comparison`, each with:
  - a `summary` and a short `description` (side effects: calls services, saves files, or nothing)
  - a ready-to-run example request body for "Try it out" (`/compare/custom`, `/compare/json`)
  - for `/compare/files`: a `multipart/form-data` body with two file pickers (`sourceFile`, `targetFile`)
  - the `typeMatching` and `nullMatching` query parameters documented as enums `STRICT` | `LENIENT`, with their defaults
- `/compare/json` takes a free JSON body: document it as a JSON object with an example, not as an internal type
- The report schema (`CompareReport`, `PathResult`, `Difference`, enums) is generated from the records; no hand-written schema
- Documentation annotations stay on the controller and the config class only; no annotations in the comparator or services
- Swagger can be switched off without code changes: `springdoc.api-docs.enabled: false` and `springdoc.swagger-ui.enabled: false`
- Swagger UI has no authentication. Like `/compare/custom`, do not expose it publicly as is

## Structure
Small flat layout: properties, client config + client factory, OpenAPI config, comparator, compare service, file storage service, controller, report records.

## Tests
- Comparator: reordered properties, reordered arrays (ordered and unordered modes, with and without key), type mismatch, missing field, `null` vs missing, ignored paths, number equality, `LENIENT` vs `STRICT` (`"11"`/`11`, `"12"`/`11`, `"abc"`/`11`, `"true"`/`true`, `null`/`"null"`, keyed arrays)
- File storage: name sanitization (including `/` and special characters), `.source.json` / `.target.json` naming, collisions, save failure not fatal
- `POST /compare`: fake source and target services; full detail, ERROR cases (500, timeout), `STATUS_MISMATCH`, saved files, credentials only sent to source and never saved
- `POST /compare/custom`: services and paths from the request, credentials only to trusted source URLs, `400` cases
- `POST /compare/files`: two uploaded files (identical and different, same detail as `/compare`), invalid and empty JSON (`ERROR`), nothing saved, directory part of the file name dropped, missing part (`400`), JSON body instead of multipart (`415`), `typeMatching` / `nullMatching` applied
- `POST /compare/json`: different property order, different documents, missing `source` or `target` (`400`), report format matches `POST /compare`
- `typeMatching`: query parameter changes the result on every endpoint, invalid value → `400`, mode shown in `summary`
- `nullMatching`: `STRICT` reports `null` vs missing both ways; `LENIENT` ignores it at any depth and in unordered arrays, but still reports missing non-null values, `null` vs value and missing array elements; combined with `typeMatching`; invalid value → `400`; mode shown in `summary`
- Every difference carries `jsonPath`, `type`, both values and `message`; long values are not truncated; `differenceCount` / `byType` match the list
- Swagger: `GET /v3/api-docs` returns `200` and lists the four endpoints with the `typeMatching` and `nullMatching` parameters; `GET /swagger-ui.html` does not return `404`

## Out of scope
UI, persistence, scheduling, authentication of the API itself. `/compare/custom` calls any URL it is given: do not expose the app publicly without adding access control or a URL allow-list.

## Done when
- `mvn clean verify` passes
- App starts with the sample config
- `POST /compare` and `POST /compare/custom` return the report format above and write `*.source.json` / `*.target.json` files under `output.directory`
- `POST /compare/files` compares two uploaded files and, like `POST /compare/json`, returns the same report format
- `typeMatching` and `nullMatching` work on all four endpoints
- Swagger UI opens at `http://localhost:8080/swagger-ui.html` and every endpoint can be run from it with its example
- Short README with run instructions, required env vars and the Swagger URL
