# ❄️ Snowstorm Terminology Server

## (Not Production Ready)

SNOMED CT Authoring server built on Elasticsearch.

## Development Setup

### Install Elasticsearch
  - Download and unzip [Elasticsearch **6.0.1**](https://www.elastic.co/downloads/past-releases/elasticsearch-6-0-1) (Must be this version)
  - Update _config/jvm.options_ with `-Xms4g` and `-Xmx4g`.
  - Start with _./bin/elasticsearch_

### Run Snowstorm
Once Elasticsearch is running build and run Snowstorm:
```
mvn clean install dockerfile:build
java -jar target/snowstorm*.jar
```

### Docker it
There is docker compose option which will run everything you need to use Snowstorm without the need to build anything. However, you will need the pre-generated elasticsearch indexes which you can either generate yourself, see the [snomed loading instructions here](docs/loading-snomed.md), or contact [techsupport@snomed.org](mailto::techsupport@snomed.org) to get a copy of the indexes.

To build the docker file (whilst it has not yet been uploaded to Docker Hub)
```
mvn clean install dockerfile:build
```

All subsequent runs, use the other docker compose file otherwise you will be importing each time:
```
mvn clean install dockerfile:build
docker-compose up -d
```


## Documentation
Documentation is appearing, slowly but surely, and can be found in the [docs folder](docs/introduction.md)
