# Arquivo.pt Page Search System

The full-text search API of Arquivo.pt, backed by Solr.

## Compile Page Search

Build and run with **Java 25**.

```
$ git clone https://github.com/arquivo/pagesearch.git
$ mvn clean install -f pagesearch/pom.xml
```

### Installing Java 25

On Ubuntu 24.04, install it from the default repositories:

```
$ sudo apt update && sudo apt install -y openjdk-25-jdk
```

### Building with Java 25 without changing your system default

If you have other JDKs installed, set `JAVA_HOME` for the build instead of
switching the system-wide default:

```
$ JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn clean install -f pagesearch/pom.xml
```

Alternatively, switch the default JVM for your whole session with:

```
$ sudo update-alternatives --config java
$ sudo update-alternatives --config javac
```

### Run without integration Tests

To do this just run mvn install with the following profile deactivated:
```
$ mvn clean install -f pagesearch/pom.xml -P !docker-integration-tests
```

### Generate new arquivo/pagesearch-solr-test docker image for the integration tests

Note: you need log with hub.docker.com to be able to publish an image to Arquivo's docker hub repository.

```
$ cd pagesearch/scripts
$ ./build-solr-test-image.sh
```

## Run Page Search API Locally

`page-search-api` is a Spring Boot app packaged as a WAR (deployed onto Tomcat in
production, see `page-search-api/Dockerfile`), but for local development you don't
need to package or deploy it at all — run it directly with the Spring Boot Maven
plugin, which boots an embedded Tomcat:

```
$ JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -f page-search-api/pom.xml spring-boot:run
```

By default this listens on port `8081` (see `server.port` in
`page-search-api/src/main/resources/application.properties`).

### Auto-reload on code change

`spring-boot:run` alone doesn't watch your source files. To get an auto-restarting
dev loop, opt into the `devtools` Maven profile (kept out of the default build so
it never ends up in the production WAR — `optional=true`/`provided` scope aren't
enough here, since the executable WAR's own launcher re-adds `provided` jars at
runtime):

`spring-boot-devtools` restarts the app whenever `target/classes` changes, but
something still needs to recompile on save — `entr` (`sudo apt install entr`)
reruns the compile each time a watched file changes. Both as a single command
line, backgrounding the app and foregrounding the watcher, with a `trap` to
stop the backgrounded app together with the watcher on Ctrl+C:

```
$ JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -f page-search-api/pom.xml spring-boot:run -Pdevtools & BOOT_PID=$!; trap "kill $BOOT_PID" EXIT; find page-search-api/src/main/java -name '*.java' | entr -r mvn -f page-search-api/pom.xml compile -q -Pdevtools
```

### Pointing at a Solr backend

`SolrSearchService` reads the Solr base URL from
`searchpages.textsearch.service.bean.solr.link`, e.g.
`http://<host>:<port>/solr/<collection>`.

To point at a different Solr instance/collection without editing the committed
`application.properties`, pass the property as a Spring Boot run argument:

```
$ JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -f page-search-api/pom.xml spring-boot:run \
    -Dspring-boot.run.arguments="--searchpages.textsearch.service.bean.solr.link=http://<host>:<port>/solr/<collection>"
```

The same property can be overridden the same way when running the packaged
WAR directly instead of through Maven (e.g. after `mvn -f page-search-api/pom.xml package`),
which is handy for quickly comparing behaviour against a different
environment's Solr without touching `application.properties`:

```
$ java -jar page-search-api/target/page-search-api-1.0.0-SNAPSHOT.war \
    --searchpages.textsearch.service.bean.solr.link=http://<host>:<port>/solr/<collection>
```

Once running, verify it's serving real results:

```
$ curl "http://localhost:8081/textsearch?q=test&maxItems=1"
```

Note: other deployed environments (dev/preprod/prod) may point at different Solr
hosts/collections than the one committed in `application.properties` — that
per-environment configuration lives in each environment's own deployment setup,
not in this repository. Check with your team for the actual dev/preprod/prod
Solr endpoints.

Highlighting always requests Solr's `hl.method=unified` explicitly (see
`SolrSearchService#convertSearchQuery`), rather than relying on Solr's default
`fastVector` highlighter, which needs full term vectors our index doesn't carry
(arquivo/pwa-technologies#1609).

`searchpages.solr.timeallowed.ms` (default `10000`) caps how long Solr is allowed to spend
processing a single query (via Solr's `timeAllowed` parameter), applied to every request made
to Solr. This protects Solr from being overwhelmed by slow-running queries; 10s is considered
the maximum time that is acceptable for users to wait for a search query.

## Page Search API Architecture 

![](docs/img/PageSearchArchitecture.png)

### Api Documentation

The app exposes its OpenAPI 3 docs under `/textsearch/api-docs` (configured via
`springdoc.api-docs.path`/`springdoc.swagger-ui.path` in
`page-search-api/src/main/resources/application.properties`), matching the
public URL convention proposed in
[pwa-technologies#1588](https://github.com/arquivo/pwa-technologies/issues/1588)
— so an Apache reverse proxy only needs a straight passthrough, no path
rewriting. Relative to wherever the app is mounted, e.g. locally:

- Swagger UI: http://localhost:8081/textsearch/api-docs
- OpenAPI 3 spec (JSON): http://localhost:8081/textsearch/api-docs/v3

Note: `/textsearch/api-docs` itself is a redirect to `/textsearch/swagger-ui/index.html`
(springdoc always serves the UI's static assets one level up from the
configured path), so this only works behind a reverse proxy that forwards the
whole `/textsearch` prefix rather than that single literal path. Apache
already does this today for the search endpoint itself, e.g.:

```
ProxyPass "/textsearch" "{{ httpd_tomcat_textsearch_endpoint }}/textsearch"
```

so no proxy changes are needed for the docs to work — this note just makes
the existing assumption explicit.

Note: how (or whether) these paths are exposed publicly through Apache in
dev/preprod/prod is per-environment reverse-proxy configuration, not part of
this repository.

### Health Check

`GET /textsearch/healthcheck` pings the Solr instance configured via
`searchpages.textsearch.service.bean.solr.link` and reports its connectivity.
Returns `200 {"solr": "ok"}` when Solr is reachable, or
`503 {"solr": "unreachable"}` otherwise. The ping's connection and socket
timeouts are configurable via `searchpages.healthcheck.solr.connectiontimeout.ms`
(default 2000) and `searchpages.healthcheck.solr.sockettimeout.ms` (default
3000), so a Solr that's up but hanging still fails the check quickly. It's mapped under the `/textsearch`
prefix for the same reverse-proxy reason as the API docs above, so it's public
at `arquivo.pt/textsearch/healthcheck` with no proxy changes needed. See
[pwa-technologies#1613](https://github.com/arquivo/pwa-technologies/issues/1613).

This is meant as a rolling-deploy gate (e.g. for the webapp's aggregate
backend healthcheck, pwa-technologies#1606), not as this container's own
`HEALTHCHECK` — see the note in `docker-compose.yml` for why that one stays a
shallow TCP check.

## Page Search Indexer

### How to index a collection of WARC files 

Available jobs:
* HdfsPageSearchDataDriver
* PageSearchDataDriver
* InvertLinksDriver
* SolrPageDocDriver

Example of expected workflow:
```shell script
$ yarn jar pagesearch-index-job-0.0.1-jar-with-dependencies.jar HdfsPageSearchDataDriver -D collection="TESTE" input.txt output
$ yarn jar pagesearch-index-job-0.0.1-jar-with-dependencies.jar InvertLinksDriver -D mapred.reduce.tasks=<nr_reduces> output
$ yarn jar pagesearch-index-job-0.0.1-jar-with-dependencies.jar SolrPageDocDriver -D mapred.reduce.tasks=<nr_reduces> output
```

### Full Indexing Workflow script:

[https://github.com/arquivo/page-search/blob/master/scripts/index-pagesearch.sh](https://github.com/arquivo/page-search/blob/master/scripts/index-pagesearch.sh)

```shell script
$ ./index-pagesearch.sh <hdfs_warcfiles_folder> <hdfs_output_folder> <collection_name>
```
Example:
```shell script
$ ./index-pagesearch.sh /user/dbicho/AWP2 /user/dbicho/output_AWP2 AWP2
```


### Configuring which records to process and index

Write a reference.conf with the parsing configurations. The default configurations of the parser are:
```
{
    "warc" : {
        "solr":{
            "server": "http://localhost:8983/solr/searchpages"
        },
        "index":{
            "extract":{
                # Restrict record types:
                "record_type_include" : [
                    response, revisit
                ],
                "record_response_include" : [
                    "2"
                ],
                "record_primary_mimetype_include" : [
                    text, application
                ],
                "record_mimetype_exclude" : [
                    xml, css, javascript, x-javascript, json
                ]
            }
        }
    }
}


