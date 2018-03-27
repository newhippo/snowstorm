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

### Docker

It is strongly recommended to use docker compose, instead of the snowstorm container on its own.

The docker-compose.yml in the repo option will run everything necessary to use Snowstorm without the need to build anything. However, **you will need the previously generated SNOMED CT elasticsearch indices** which you can either generate yourself, see the [snomed loading instructions here](docs/loading-snomed.md), or contact [techsupport@snomed.org](mailto::techsupport@snomed.org) to get access to a copy of the already generated indices.

Once you have the indices, you can either unzip them into a local ~/elastic folder or change the following line in [docker-compose.yml](docker-compose.yml) from ~/elastic to a local folder of your choice:
```    
    volumes:
      - ~/elastic:/usr/share/elasticsearch/data
```
Once done, then simply run:
```
docker-compose up -d
```


## Documentation
Documentation is appearing, slowly but surely, and can be found in the [docs folder](docs/introduction.md)
