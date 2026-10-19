# Quarkus CRUD + GitOps Backstage template

A Backstage software template. It asks for a service name and a database table name, then
produces two repositories:

- **`<name>`** — a Quarkus service with a web interface for create / read / update /
  delete over that table.
- **`<name>-gitops`** — manifests that run the Quarkus Pod and a PostgreSQL Pod, and
  create the table in it.

```
rabobankdemo/
├── template.yaml              the scaffolder template: parameters, steps, output
├── skeleton/                  → becomes the application repository
│   ├── pom.xml
│   ├── catalog-info.yaml
│   └── src/
│       ├── main/java/org/acme/{Person,PersonResource}.java
│       ├── main/resources/application.properties
│       ├── main/resources/META-INF/resources/index.html    the web UI
│       ├── main/docker/Dockerfile.jvm
│       └── test/java/org/acme/PersonResourceTest.java
├── gitops/                    → becomes the GitOps repository
│   ├── argocd/application.yaml
│   └── manifests/
│       ├── db-secret.yaml, db-service.yaml, db-deployment.yaml
│       ├── db-init-configmap.yaml, db-init-job.yaml
│       ├── app-deployment.yaml, app-service.yaml, app-ingress.yaml
│       └── tekton/            the build pipeline
│           ├── pipeline.yaml      two Tasks + the Pipeline
│           ├── pipelinerun.yaml   runs on every ArgoCD sync
│           ├── serviceaccount.yaml
│           └── workspace-pvc.yaml
└── scripts/render-locally.py  render the skeletons without a Backstage instance
```

## Parameters

| Parameter | Purpose |
|---|---|
| **Name** | names both repositories, the Kubernetes resources and the Maven artifact |
| Description, Owner | catalog metadata |
| **Table name** | the table the service reads and writes |
| Database name, user, password | PostgreSQL credentials |
| Namespace, ArgoCD namespace | where it is deployed and where the `Application` lives |
| Image registry, organization | together with the name, forms the image reference |
| Cluster ingress domain | the app is exposed at `<name>.<domain>` |
| Application repository | the GitOps repository is created alongside with a `-gitops` suffix |
| Visibility | private repositories need a git credential for the build pipeline to clone |

## The table

Whatever you call it, the table is created with these columns:

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | primary key |
| `first_name` | `VARCHAR(100)` | required |
| `second_name` | `VARCHAR(100)` | required |
| `age` | `INTEGER` | 0–150 |
| `postcode` | `VARCHAR(16)` | optional |

## What gets deployed

ArgoCD syncs `manifests/` in sync-wave order, so the pieces come up in a workable
sequence rather than racing:

| Wave | Resource | |
|---|---|---|
| -1 | `Secret` | database name, user, password |
| 0 | `Service`, `Deployment`, `ConfigMap` | **the PostgreSQL Pod** and the schema SQL |
| 0 | `ServiceAccount`, `PVC`, `Task`s, `Pipeline` | the build definitions |
| 1 | `Job` (sync hook) | waits for the database, applies `CREATE TABLE`, seeds three rows |
| 2 | `PipelineRun` (sync hook) | **builds and pushes the image** |
| 3 | `Deployment`, `Service`, `Ingress` | **the Quarkus Pod**, reachable from outside |

The database image is `quay.io/sclorg/postgresql-16-c9s`, which tolerates the arbitrary
UID that OpenShift's `restricted-v2` SCC assigns, so the manifests work unchanged on
OpenShift and on vanilla Kubernetes. External access is a plain `Ingress`; OpenShift's
ingress-to-route controller turns it into a `Route` automatically.

## The build

A Tekton Pipeline in the GitOps repository clones the application repository, runs
`mvn package`, and pushes the image with buildah — so the developer never builds it by
hand. It runs as a sync hook in wave 2, and ArgoCD understands `PipelineRun` health, so
the application in wave 3 waits for the image it needs.

Both Tasks are defined inline rather than referenced from Tekton Hub or as ClusterTasks,
which recent OpenShift Pipelines releases have removed. Nothing extra to install beyond
OpenShift Pipelines itself.

The pipeline needs two things that are deliberately **not** committed — the
`pipelines-scc` grant that lets buildah run, and registry (plus, for a private repo, git)
credentials linked to the build service account. The generated GitOps README spells out
the `oc` commands, and so does the template's completion screen.

## Installing the template

Register it in `app-config.yaml`:

```yaml
catalog:
  locations:
    - type: url
      target: https://github.com/<org>/<repo>/blob/main/template.yaml
      rules:
        - allow: [Template]
```

Or from the Backstage UI: **Create → Register Existing Component**, pointing at
`template.yaml`.

The template uses only built-in scaffolder actions (`fetch:template`, `publish:github`,
`catalog:register`), so it needs no extra plugins. To publish somewhere other than
GitHub, swap the two `publish:github` steps for `publish:gitlab` / `publish:bitbucket`
and change `allowedHosts` on the `repoUrl` parameter.

## Trying it without Backstage

`scripts/render-locally.py` applies the same substitutions the scaffolder would, so you
can build the output before putting the template in front of anyone:

```shell
./scripts/render-locally.py --out /tmp/rendered --name person-service --table customer_records
cd /tmp/rendered/app && mvn verify
```

It handles the `${{ values.x }}` subset this template uses — enough to materialise, build
and inspect the output. The real rendering is always done by the scaffolder.

## Verified

Against a rendered copy (`--name person-service --table customer_records`):

- the Quarkus app builds and its 7 tests pass (H2, no container runtime needed);
- the init SQL applies cleanly to a real `postgresql-16-c9s` container, and applies again
  without error — ArgoCD re-syncs the hook on every sync;
- Hibernate's `update` strategy issues no DDL against the job-created table, so the
  entity and the SQL agree;
- both pipeline steps run in the exact images the Tasks name: `mvn package` in
  `ubi9/openjdk-21` (which has Maven but no git, hence the separate clone step), and
  `buildah --storage-driver=vfs bud` in `quay.io/buildah/stable:v1.43`;
- the resulting image runs against PostgreSQL and serves the full CRUD cycle and the UI.

Not verified, because they need a cluster: the ArgoCD sync and its wave ordering, the
`Ingress`, the `pipelines-scc` grant, and Tekton's credential handling for the registry
and git secrets.

## Before using this beyond a demo

- **The database password is plain text in the generated GitOps repository.** Replace
  `manifests/db-secret.yaml` with a Sealed Secret, an External Secrets `ExternalSecret`,
  or an ArgoCD Vault Plugin reference.
- **Database storage is an `emptyDir`** — data is lost on Pod restart. Use a PVC.
- **The image tag is mutable** (`:latest` with `imagePullPolicy: Always`), which gives
  ArgoCD nothing to diff. The pipeline emits an `image-digest` result — feed that into
  the Deployment with an image updater rather than relying on `:latest`.
- **The build only runs on an ArgoCD sync.** There is no webhook, so a push to the
  application repository does not rebuild anything. Add Tekton Triggers, or an
  `EventListener` behind a repository webhook.
- The Java package is fixed at `org.acme`. Parameterising it means templating directory
  names, which is doable but was left out to keep the template simple.
