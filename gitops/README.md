# ${{ values.name }}-gitops

Deployment manifests for
[${{ values.name }}](https://${{ values.repoHost }}/${{ values.repoOwner }}/${{ values.repoName }}),
a Quarkus CRUD service over the `${{ values.tableName }}` table.

ArgoCD syncs `manifests/` into the `${{ values.namespace }}` namespace, producing:

| Resource                      | Wave | Purpose                                             |
|-------------------------------|------|-----------------------------------------------------|
| `Secret/${{ values.name }}-db`       | -1   | database name, user, password                        |
| `Service/${{ values.name }}-db`      | 0    | stable address for the database                      |
| `Deployment/${{ values.name }}-db`   | 0    | **the database Pod** (PostgreSQL 16)                 |
| `ConfigMap/${{ values.name }}-db-init` | 0  | `CREATE TABLE ${{ values.tableName }}` + seed rows   |
| `ServiceAccount`, `PVC`, `Task`s, `Pipeline` | 0 | the build definitions, in `manifests/tekton/`   |
| `Job/${{ values.name }}-db-init`     | 1    | applies the schema, sync hook                        |
| `PipelineRun`                        | 2    | **builds and pushes the image**, sync hook           |
| `Deployment/${{ values.name }}`      | 3    | **the Quarkus Pod**                                  |
| `Service/${{ values.name }}`         | 3    | cluster-internal access on 8080                      |
| `Ingress/${{ values.name }}`         | 3    | external access (becomes a Route on OpenShift)       |

The sync waves matter: the database comes up, the schema job runs to completion against
it, the pipeline builds the image, and only then does the application roll out. ArgoCD
understands `PipelineRun` health, so wave 3 genuinely waits for the build.

## The build pipeline

`manifests/tekton/pipeline.yaml` defines two Tasks and a Pipeline:

1. **`${{ values.name }}-package`** — clones
   [the application repository](${{ values.gitUrl }}) and runs `mvn package`. The Maven
   cache lives on the workspace PVC, so repeat builds skip the downloads.
2. **`${{ values.name }}-image`** — `buildah bud` against
   `src/main/docker/Dockerfile.jvm`, then pushes `${{ values.imageUrl }}`.

The Tasks are defined inline rather than referenced from Tekton Hub or as ClusterTasks
(removed in recent OpenShift Pipelines), so there is nothing else to install.

Re-run the build without an ArgoCD sync:

```shell
tkn pipeline start ${{ values.name }}-build -n ${{ values.namespace }} \
    --serviceaccount ${{ values.name }}-build \
    --workspace name=source,claimName=${{ values.name }}-build-workspace \
    --use-param-defaults --showlog
```

## Install

Requires [OpenShift Pipelines](https://docs.openshift.com/pipelines/) (or upstream
Tekton) in the cluster.

**First, grant the build service account what it needs.** None of this is committed,
because none of it belongs in Git. The namespace has to exist for these to land:

```shell
oc new-project ${{ values.namespace }}

# buildah needs more than restricted-v2 allows; needs cluster-admin
oc adm policy add-scc-to-user pipelines-scc -z ${{ values.name }}-build -n ${{ values.namespace }}

# credentials to push the image
oc create secret docker-registry ${{ values.name }}-registry \
    --docker-server=${{ values.imageRegistry }} \
    --docker-username=<user> --docker-password=<token> -n ${{ values.namespace }}
oc secrets link ${{ values.name }}-build ${{ values.name }}-registry -n ${{ values.namespace }}
```

If the application repository is private, the pipeline also needs to authenticate to
clone it. Tekton picks up a `basic-auth` secret annotated with `tekton.dev/git-0`:

```shell
oc create secret generic ${{ values.name }}-git \
    --type=kubernetes.io/basic-auth \
    --from-literal=username=<user> --from-literal=password=<token> -n ${{ values.namespace }}
oc annotate secret ${{ values.name }}-git \
    tekton.dev/git-0=https://${{ values.repoHost }} -n ${{ values.namespace }}
oc secrets link ${{ values.name }}-build ${{ values.name }}-git -n ${{ values.namespace }}
```

**Then hand the manifests to ArgoCD:**

```shell
kubectl apply -n ${{ values.argocdNamespace }} -f argocd/application.yaml
```

The Application has `CreateNamespace=true`, so it will also create
`${{ values.namespace }}` if you skipped `oc new-project` — but then the secrets above
have to be created between the first failed sync and a retry, which is more awkward than
just creating the namespace first.

## Reaching the app

```
https://${{ values.name }}.${{ values.baseDomain }}
```

If the cluster has no ingress controller, delete `manifests/app-ingress.yaml` and use:

```shell
kubectl -n ${{ values.namespace }} port-forward svc/${{ values.name }} 8080:8080
```

## Before using this for real

- **The database password is plain text in `manifests/db-secret.yaml`.** Replace it with
  a Sealed Secret, an External Secrets `ExternalSecret`, or an ArgoCD Vault Plugin
  reference.
- **Storage is an `emptyDir`.** The data is lost on every Pod restart. Swap the volume in
  `manifests/db-deployment.yaml` for a PersistentVolumeClaim.
- **The image tag is mutable.** `${{ values.imageUrl }}` with `imagePullPolicy: Always`
  is convenient for a demo but gives ArgoCD nothing to diff. Use immutable tags or
  digests, updated by an image updater.
- **The database is a single replica with no backups.**
