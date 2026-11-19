# TODO
* Process WARC Resource Types
* ~~Change docker Image Pulling Policy (so it updates the testing image automatically)~~
* Update the general docker-compose file
* ~~Document Development Proceedings (local)~~
* ~~Move page-search-api utils to Module Utils.~~
* Mock CDX Service Response, if we don't do that we need to always have access to preprod replay to do the integration tests... 
* ~~Enable Testing on Indexer code~~
* Iterate through all code TODO's
    * Add more complete records to test
    * Test Limit Cap (long enough record)
    * Add Records with Encoding Problems
* Limit the number of chars in title field
* ~~Generate new Solr Test Image to reflect the schema change to text_pt instead of text_general~~
    * ~~Create script that generates a new image and pull it to docker hub~~
* Limit the number of chars in anchors and remove duplicates
    * Dedup by phrase
* ~~Swagger / OpenAPI Clean Up~~
* ~~Add Test Coverage metrics to maven~~ - https://plugins.jenkins.io/code-coverage-api/
* ~~Make SolrClient only be instantiated one time~~
* ~~Make ExtractedText Endpoint to work~~
* ~~Add deduplication to Solr~~
* ~~Make Metadata Endpoint Work~~
* ~~Add IT tests to SolrService~~
* Prepare Test Collection to Improve Ranking
* Solr Schema change timestamp field type to Date instead of String
* Deduplication of Results
    * SimHash
    * ReRanking penalizing already top scored documents
    * Old Documents are 'better' 
* **versionHistory** just search StatusCode = 200 records. This should be deleted altogether.
* Add an uniform error reporting representation (https://tools.ietf.org/html/rfc7807)
