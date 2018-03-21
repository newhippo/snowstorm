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
mvn clean install
java -jar target/snowstorm*.jar
```

### Docker it
There is docker compose option which will run everything you need to use Snowstorm without the need to build anything.

The first time it's run, a SNOMED CT edition will need to be imported. Put the edition RF2 zip file in a relevant folder and change the location for the volumes in docker-compose-load.yml (replaces ~/releases with the absolute file location):
```
volumes:
  -  ~/releases:/opt
```

Then, run the import docker compose file (it will take a while to load SNOMED CT into the Elasticsearch container):
```
docker-compose up docker/docker-compose-load.yml up
```

All subsequent runs, use the other docker compose file otherwise you will be importing each time:

```
docker-compose up docker/docker-compose.yml up
```


## Documentation
Documentation is appearing, slowly but surely, and can be found in the [docs folder](docs/introduction.md)
