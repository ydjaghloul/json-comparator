# REST Service Comparator

Full specification: [SPEC_v0.6.md](SPEC_v0.6.md).

Spring Boot 3.5 / Java 17 app that calls two versions of a REST service with the same relative paths and reports semantic JSON differences.

## Requirements
- Java 17+
- Maven 3.9+

## Required environment variables
| Variable | Used for |
|---|---|
| `CLIENT_ID` | `client_id` header sent to `source_service` only |
| `CLIENT_SECRET` | `client_secret` header sent to `source_service` only |

Credentials are never logged and never written to saved files. If a variable is missing, the app still starts and logs a warning; the header is then not sent.

`POST /compare/custom` sends the credentials only when its `sourceBaseUrl` equals `source_service.base-url` or one of `source_service.trusted-base-urls`; any other source URL is called without them. Credentials are never sent to a target URL.

## Build and run
```bash
mvn clean verify
export CLIENT_ID=... CLIENT_SECRET=...
mvn spring-boot:run
# or: java -jar target/rest-service-comparator-0.6.0.jar
```

## Configuration (`src/main/resources/application.yml`)
- `source_service.base-url`, `target_service.base-url` (required)
- `*.connect-timeout` (default `5s`), `*.read-timeout` (default `30s`)
- `paths`: relative paths appended to each base URL as written (no templating, no re-encoding; `{` `}` are not supported)
- `output.directory`: where each live run (`/compare`, `/compare/custom`) writes `{yyyyMMdd-HHmmss}/report.json` (whole run) and, for each path, a folder `{yyyyMMdd-HHmmss}/{name}/` with `source.json`, `target.json` and `report.json` (the report of that path)
- `source_service.trusted-base-urls`: other source base URLs allowed to receive the credentials (for `/compare/custom`)
- `comparison.type-matching`: default type matching, `STRICT` (default) or `LENIENT`
- `comparison.null-matching`: default null matching, `STRICT` (default) or `LENIENT`
- `comparison.ignore-paths`: JSON paths to skip, e.g. `$.generatedAt`, `$.items[*].id` (`*` matches any single property or index)
- `comparison.array-order`: `UNORDERED` (default, order of array elements does not matter) or `ORDERED` (by position); default of the `orderStrict` query parameter
- `comparison.id-properties`: candidate id properties used to pair array elements when order does not matter (default `id, uuid, guid, key, code, sku`). For each array the first candidate that is present as a scalar, with a unique value, in every element of both arrays is used; if none qualifies, elements are paired by equality. Paired elements are compared field by field.
- `comparison.unordered-arrays`: list of `{path, key}`; arrays at `path` are always compared regardless of order (even with `orderStrict=true`), paired by `key` when given, otherwise as above. Unpaired elements are reported at their own index (source index for `MISSING_IN_TARGET`, target index for `MISSING_IN_SOURCE`).

## Array comparison
By default the order of array elements is ignored. Objects are paired by an id property (see `comparison.id-properties`, or the `idProperties` query parameter, e.g. `idProperties=ref,code`), so a changed field is reported as a field difference at the source index (`$.items[1].qty`) instead of as a missing + extra element. Use `orderStrict=true` to compare by position. Both parameters are accepted by every endpoint, and the report summary states the `orderStrict` actually used.

## Reading a difference
Each difference has the exact `jsonPath` and, when an array is on the way, a `location` that names the elements by id (`$.orders[id=7].lines[code=X].qty`) or, without id, by index and main fields (`$.rows[3: name=bob, age=3]`). `null` against any other value is a `VALUE_MISMATCH` (never a type mismatch). With `["a","b","c"]` vs `["a","b","e"]`, order-insensitive comparison reports `c` as having no match in target and `e` as having no match in source.

## API
Swagger UI: http://localhost:8080/swagger-ui.html (OpenAPI JSON: http://localhost:8080/v3/api-docs). Each endpoint has a ready-to-run example body under "Try it out".

```bash
# Live comparison of all configured paths (responses saved under output.directory)
curl -X POST localhost:8080/compare

# Same, with services and paths given in the request (responses saved too)
curl -X POST localhost:8080/compare/custom -H 'Content-Type: application/json' \
  -d '{"sourceBaseUrl":"https://source-v2.example.com","targetBaseUrl":"https://target-v3.example.com","paths":["/customers/42/orders","/products/1001"]}'

# Comparison of two uploaded JSON files (nothing called, nothing saved)
curl -X POST localhost:8080/compare/files \
  -F sourceFile=@output/20261118-135000/customers_42_orders/source.json \
  -F targetFile=@output/20261118-135000/customers_42_orders/target.json

# Inline comparison (nothing called, nothing saved)
curl -X POST localhost:8080/compare/json -H 'Content-Type: application/json' \
  -d '{"name":"demo","source":{"total":100,"items":[1,2]},"target":{"items":[1,2],"total":105}}'
```
All four return `{summary, results}` with the same per-result detail; `summary.typeMatching` and `summary.nullMatching` show the modes used.

Every endpoint accepts `?typeMatching=STRICT|LENIENT` (uppercase; default `comparison.type-matching`):
- `STRICT`: `11` vs `"11"` is a `TYPE_MISMATCH`
- `LENIENT`: a string is converted before being compared with a number or boolean: `"11"` = `11`, `"1.50"` = `1.5`, `"true"` = `true`; `"12"` vs `11` is a `VALUE_MISMATCH`; non-convertible values (`"abc"` vs `11`, `" 11"`, `null` vs `"null"`, number vs boolean, object vs array) stay `TYPE_MISMATCH`. Also applies to key matching in unordered arrays. Reported values are always the originals.

Every endpoint also accepts `?nullMatching=STRICT|LENIENT` (default `comparison.null-matching`), combinable with `typeMatching` (`?typeMatching=LENIENT&nullMatching=LENIENT`):
- `STRICT`: `{"a": null}` vs `{}` is `MISSING_IN_TARGET` (and `{}` vs `{"a": null}` is `MISSING_IN_SOURCE`)
- `LENIENT`: a property that is `null` on one side and absent on the other is not a difference. Missing non-null values, `null` vs a value, and missing array elements are still reported.

`/compare/custom` returns `400` if a base URL is missing or not an absolute http(s) URL, or if `paths` is missing, empty or has a blank entry. It calls whatever URL it is given: do not expose the app publicly without access control. `/compare/files` takes `multipart/form-data` with two file parts, `sourceFile` and `targetFile` (in Swagger UI: two file pickers). An empty or invalid JSON file gives an `ERROR` result; a missing part gives `400`; files over 50MB (`spring.servlet.multipart.max-file-size`) give `413`. `/compare/json` returns `400` if `source` or `target` is missing.

### Result rules
- Status codes differ → `STATUS_MISMATCH` at `$status`, plus body differences.
- A call that fails or times out, a non-2xx response without a JSON body, or a 2xx body that is not valid JSON → `ERROR` for that path; the run continues.
- Numbers compare by value (`1` = `1.0`); `1` vs `"1"` is `TYPE_MISMATCH`; `null` vs absent is `MISSING_IN_*`.
