#!/usr/bin/env python3
"""Render the skeletons the way Backstage's scaffolder would, without Backstage.

Backstage's `fetch:template` runs each skeleton file through nunjucks with the
variable delimiters set to `${{ ... }}`. This covers the subset this template
uses -- `${{ values.x }}` and `${{ values.x | dump }}` -- which is enough to
materialise the output, build the Quarkus app and run `kubectl apply --dry-run`
over the manifests.

    ./scripts/render-locally.py --out /tmp/rendered --name my-app --table people

It is a development aid for iterating on the template. The real rendering is
always done by the scaffolder.
"""

import argparse
import json
import pathlib
import re
import shutil
import sys

PLACEHOLDER = re.compile(r"\$\{\{\s*values\.([A-Za-z0-9_]+)\s*(\|\s*dump\s*)?\}\}")


def render_text(text: str, values: dict, origin: pathlib.Path) -> str:
    missing = set()

    def substitute(match: re.Match) -> str:
        key, dump = match.group(1), match.group(2)
        if key not in values:
            missing.add(key)
            return match.group(0)
        return json.dumps(values[key]) if dump else str(values[key])

    rendered = PLACEHOLDER.sub(substitute, text)
    if missing:
        raise SystemExit(f"{origin}: no value supplied for {', '.join(sorted(missing))}")
    return rendered


def render_tree(source: pathlib.Path, target: pathlib.Path, values: dict) -> int:
    count = 0
    for path in sorted(source.rglob("*")):
        if path.is_dir():
            continue
        destination = target / path.relative_to(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            destination.write_text(render_text(path.read_text(), values, path))
        except UnicodeDecodeError:
            shutil.copyfile(path, destination)
        count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, type=pathlib.Path,
                        help="output directory, created if absent, contents replaced")
    parser.add_argument("--name", default="person-service")
    parser.add_argument("--table", default="people", dest="tableName")
    parser.add_argument("--description", default="Quarkus CRUD service over a PostgreSQL table")
    parser.add_argument("--owner", default="group:default/platform-team")
    parser.add_argument("--namespace", default="rabobank-demo")
    parser.add_argument("--argocd-namespace", default="openshift-gitops", dest="argocdNamespace")
    parser.add_argument("--db-name", default="appdb", dest="dbName")
    parser.add_argument("--db-user", default="appuser", dest="dbUser")
    parser.add_argument("--db-password", default="change-me-demo-only", dest="dbPassword")
    parser.add_argument("--base-domain", default="apps.example.com", dest="baseDomain")
    parser.add_argument("--image-registry", default="quay.io", dest="imageRegistry")
    parser.add_argument("--image-organization", default="rabobank-demo", dest="imageOrganization")
    parser.add_argument("--repo-host", default="github.com", dest="repoHost")
    parser.add_argument("--repo-owner", default="rabobank-demo", dest="repoOwner")
    args = parser.parse_args()

    root = pathlib.Path(__file__).resolve().parent.parent

    # Mirrors the `values:` blocks of the two fetch:template steps in template.yaml.
    shared = {
        "name": args.name,
        "description": args.description,
        "tableName": args.tableName,
        "imageRegistry": args.imageRegistry,
        "imageUrl": f"{args.imageRegistry}/{args.imageOrganization}/{args.name}:latest",
        "gitUrl": f"https://{args.repoHost}/{args.repoOwner}/{args.name}.git",
        "repoHost": args.repoHost,
        "repoOwner": args.repoOwner,
        "repoName": args.name,
        "gitopsHost": args.repoHost,
        "gitopsOwner": args.repoOwner,
        "gitopsRepo": f"{args.name}-gitops",
    }
    app_values = {**shared, "owner": args.owner}
    gitops_values = {
        **shared,
        "dbName": args.dbName,
        "dbUser": args.dbUser,
        "dbPassword": args.dbPassword,
        "namespace": args.namespace,
        "argocdNamespace": args.argocdNamespace,
        "baseDomain": args.baseDomain,
    }

    if args.out.exists():
        shutil.rmtree(args.out)

    app_files = render_tree(root / "skeleton", args.out / "app", app_values)
    gitops_files = render_tree(root / "gitops", args.out / "gitops", gitops_values)

    print(f"app:    {args.out / 'app'} ({app_files} files)")
    print(f"gitops: {args.out / 'gitops'} ({gitops_files} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
