# ${{ values.name }}

${{ values.description }}

A Quarkus service with a small web UI for create / read / update / delete over the
`${{ values.tableName }}` PostgreSQL table.

| Column        | Type           | Notes            |
|---------------|----------------|------------------|
| `id`          | `BIGSERIAL`    | primary key      |
| `first_name`  | `VARCHAR(100)` | required         |
| `second_name` | `VARCHAR(100)` | required         |
| `age`         | `INTEGER`      | 0–150            |
| `postcode`    | `VARCHAR(16)`  | optional         |

## Running locally

`quarkus dev` starts a throwaway PostgreSQL through Dev Services, so a container
runtime (Podman or Docker) needs to be running:

```shell
mvn quarkus:dev
```

Then open <http://localhost:8080/>.

To point at an existing database instead:

```shell
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/appdb
export QUARKUS_DATASOURCE_USERNAME=appuser
export QUARKUS_DATASOURCE_PASSWORD=...
mvn quarkus:dev
```

## Tests

```shell
mvn verify
```

Tests run against in-memory H2, so they need no container runtime.

## API

| Method   | Path               | Result                        |
|----------|--------------------|-------------------------------|
| `GET`    | `/api/people`      | all rows                      |
| `GET`    | `/api/people/{id}` | one row, or 404               |
| `POST`   | `/api/people`      | 201 + `Location`, or 400      |
| `PUT`    | `/api/people/{id}` | updated row, or 404           |
| `DELETE` | `/api/people/{id}` | 204, or 404                   |

Health probes are at `/q/health/live` and `/q/health/ready`.

## Building the image

Normally you don't: the Tekton pipeline in
[${{ values.gitopsRepo }}](https://${{ values.gitopsHost }}/${{ values.gitopsOwner }}/${{ values.gitopsRepo }})
clones this repository, packages it and pushes
`${{ values.imageUrl }}` on every ArgoCD sync. To re-run it without a sync:

```shell
tkn pipeline start ${{ values.name }}-build \
    --serviceaccount ${{ values.name }}-build \
    --workspace name=source,claimName=${{ values.name }}-build-workspace \
    --use-param-defaults --showlog
```

To build the same image locally instead:

```shell
mvn package
podman build -f src/main/docker/Dockerfile.jvm -t ${{ values.imageUrl }} .
podman push ${{ values.imageUrl }}
```

## Schema handling

`quarkus.hibernate-orm.schema-management.strategy=update` lets the app start whether or not the
GitOps init job has already created the table. Once the schema is settled, switch it to
`validate` so a drift between the entity and the table fails fast instead of silently
altering the table.
