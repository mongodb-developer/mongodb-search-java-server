# MongoDB Search Server
A Java-based HTTP server `GET` interface for MongoDB Search, supporting filtering,
sorting, highlighting, pagination, and debugging.

This Search Server code was initially written to support the article
["How to Build a Search Service in Java with MongoDB"](https://www.mongodb.com/developer/products/atlas/atlas-search-java-server/)
and video[TBD].

## Building and running locally

The `jettyRun` target is used and searches the `movies_index`. The search
server code lives under `server/`

To run the search server locally, follow these steps:

* Add the [sample collections](https://www.mongodb.com/docs/atlas/sample-data/) to your Atlas cluster
    * If you're not already an Atlas user, [get started with Atlas](https://www.mongodb.com/docs/atlas/getting-started/)
* [Create an Atlas Search index](https://www.mongodb.com/docs/atlas/atlas-search/tutorial/create-index/) on the `movies` collection, named `movies_index`, using the index
  configuration below.
* Run the search service:
  `ATLAS_URI="<<insert your connection string here>>" ./gradlew jettyRun`
* Visit [http://localhost:8080](http://localhost:8080)

## `movies_index` configuration

```
{
  "analyzer": "lucene.english",
  "searchAnalyzer": "lucene.english",
  "mappings": {
    "dynamic": true,
    "fields": {
      "cast": [
        {
          "type": "token"
        },
        {
          "type": "string"
        },
        {
          "type": "stringFacet"
        }
      ],
      "genres": [
        {
          "type": "token"
        },
        {
          "type": "string"
        },
        {
          "type": "stringFacet"
        }
      ],
      "year": {
        "type": "numberFacet"
      }
    }
  }
}
```
