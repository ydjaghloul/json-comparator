# SPEC: REST Service Comparator

## Goal
Spring Boot app that calls two versions of a REST service with the same relative paths and reports semantic JSON differences.

## Stack
- Spring Boot 3.5.x, Java 17, Maven
- Lombok allowed; prefer records where simpler
- Standard Spring Boot features only. No diff library, no WebFlux/WebClient
- Reflection allowed only if really needed (should not be)

## Configuration (`application.yml`)
```yaml
source_service:
  base-url: https://source.example.com
  client-id: ${CLIENT_ID}
  client-secret: ${CLIENT_SECRET}
target_service:
  base-url: https://target.example.com
paths:
  - /customers/42/orders
  - /customers/43/orders
  - /products/1001
output:
  directory: ./output
```
- Bind with `@ConfigurationProperties`
- `output.directory` = root folder where responses are saved and where offline comparison reads from
- `paths` = flat list of relative paths, parameters already included, used for both services
- No path templating: append each path to each base URL as is

## Behavior
1. For each path, GET `{source base-url}{path}` and `{target base-url}{path}`
2. Only `source_service` calls send headers `client_id` and `client_secret`. Never log them
3. Compare status codes, then JSON bodies semantically (not as text):
   - Object property order ignored
   - Array order: ordered by default; optional config to compare as unordered, optionally matched by a key field
   - Types matter: `1` != `"1"`, `null` != missing field
   - Optional config list of JSON paths to ignore (timestamps, generated IDs)
4. A failed call or timeout produces `ERROR` for that path and does not stop the run
5. Every JSON response received is saved to disk (see "Saving responses")

## Saving responses
- Each run creates a folder `{output.directory}/{yyyyMMdd-HHmmss}/`
- For each path, save the two responses as pretty-printed JSON:
  - `{name}.source.json`
  - `{name}.target.json`
- `{name}` = the path with the leading `/` removed and every character outside `[A-Za-z0-9._-]` replaced by `_`
  (e.g. `/customers/42/orders` -> `customers_42_orders.source.json`)
- If the sanitized name is empty (path `/`), use `root`
- Save the response even when the status code is not 2xx, as long as the body is valid JSON. Skip saving when there is no body
- A save failure is logged as a warning and never fails the comparison
- Never write credentials into any saved file
- Add the saved file names to each result (`sourceFile`, `targetFile`) when they exist

## Offline comparison
- Compare two JSON files that are already on disk, without calling any service
- Both files must be inside `output.directory` (resolve the path and reject anything outside it, including `..` traversal)
- Use exactly the same comparator and the same ignore / array-order settings as the live comparison
- Return the same report format as a live run (`summary` + `results`), with one entry in `results`

## HTTP clients
- `RestClient` built from the auto-configured `RestClient.Builder`
- One `RestClient` bean per service, each with its own base URL and timeouts
- Auth headers set as default headers on the source client only
- Timeouts via Spring Boot HTTP client settings
- Read responses as `String`/`JsonNode`, never mapped DTOs

## JSON comparison
- Use the auto-configured `ObjectMapper` and `JsonNode`
- Small custom recursive comparator class

## API
### `POST /compare`
Runs all configured paths, saves the responses, and returns:

```json
{
  "summary": { "total": 3, "identical": 1, "different": 1, "errors": 1 },
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
- Each difference has: `jsonPath` (exact location, e.g. `$.items[1].discount`), `type`, `source` value, `target` value, and a short human-readable `message` (e.g. `$.total: source=100, target=105`)
- `source` is the reference. The report states what differs, not which side is right
- A different HTTP status code is reported as a difference of type `STATUS_MISMATCH` (`jsonPath` = `$status`, with both codes)
- Add `differenceCount` to each `DIFFERENT` result, and `byType` counts (e.g. `{ "VALUE_MISMATCH": 1, "MISSING_IN_TARGET": 1 }`)
- Full response bodies are not duplicated in the report: they are in the saved files (`sourceFile` / `targetFile`)
- The three endpoints (`/compare`, `/compare/files`, `/compare/json`) return exactly this same level of detail
- `IDENTICAL` results have no `differences` field

### `POST /compare/files`
Compares two already downloaded files. Request body:
```json
{ "sourceFile": "20261118-135000/customers_42_orders.source.json",
  "targetFile": "20261118-135000/customers_42_orders.target.json" }
```
- Paths are relative to `output.directory`
- Returns the same report format as `POST /compare` (`summary` + `results`), with one entry in `results` whose `path` is the source file name
- `400` if a path is outside `output.directory`, `404` if a file does not exist, `ERROR` result if a file is not valid JSON

### `POST /compare/json`
Compares two JSON documents sent directly in the request body. No service is called and nothing is saved. Request body:
```json
{ "name": "optional label",
  "source": { "total": 100, "items": [1, 2] },
  "target": { "items": [1, 2], "total": 105 } }
```
- `source` and `target` are required and can be any valid JSON value (object, array, string, number, boolean, null)
- `name` is optional and defaults to `inline`. It is used as `path` in the result
- Uses the same comparator and the same ignore / array-order settings as the live comparison
- Returns exactly the same report format as `POST /compare` (`summary` + `results`), with one entry in `results`
- `400` if `source` or `target` is missing

## Structure
Small flat layout: properties, client config, comparator, compare service, file storage service, controller, report records.

## Tests
Unit tests for the comparator: reordered properties, reordered arrays (ordered and unordered modes), type mismatch, missing field, ignored paths.
Unit tests for file storage: name sanitization (including `/` and special characters), `.source.json` / `.target.json` naming, path traversal rejected for offline comparison.
Test for `POST /compare/files` with two temporary files (identical and different).
Test that every difference carries `jsonPath`, `type`, both values and `message`, that long values are not truncated, and that `differenceCount` / `byType` match the list.
Test for `POST /compare/json`: identical documents with different property order, different documents, missing `source` or `target` (400), and the report format matches `POST /compare`.

## Out of scope
UI, persistence, scheduling.

## Done when
- `mvn clean verify` passes
- App starts with the sample config
- `POST /compare` returns the report format above and writes `*.source.json` / `*.target.json` files under `output.directory`
- `POST /compare/files` compares two saved files and returns the same report format
- `POST /compare/json` compares two JSON documents from the request body and returns the same report format
- Short README with run instructions and required env vars
