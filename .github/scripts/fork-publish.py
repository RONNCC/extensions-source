"""
Publishes the fork's extensions to the `repo` branch checkout (the working directory).

Minimal fork variant of publish-repo.py: the extension list is the build output, nothing is
merged with extensions that were not built, and APKs/JARs are uploaded as releases of this
repo using the workflow's own token.

usage (cwd = checkout of the `repo` branch):
  fork-publish.py <artifacts-dir> <source-sha> <signing-key-sha256> <source-checkout-dir>
"""

import gzip
import hashlib
import html
import json
import os
import sys
from pathlib import Path

import index_pb2
from github_utils import run_gh
from google.protobuf import json_format

artifacts_dir = Path(sys.argv[1])
sha = sys.argv[2]
signing_key = sys.argv[3].lower()
source_dir = Path(sys.argv[4])

repo_name = os.environ["GITHUB_REPOSITORY"]
repo_dir = Path.cwd()
release_base_url = f"https://github.com/{repo_name}/releases/download"
icon_base_url = f"https://raw.githubusercontent.com/{repo_name}/{sha}"
icon_file = "res/mipmap-xhdpi/ic_launcher.png"


def get_icon_url(module: str, theme: str | None) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{icon_file}"
    if (source_dir / module_icon).exists():
        return f"{icon_base_url}/{module_icon}"
    if theme:
        theme_icon = f"lib-multisrc/{theme}/{icon_file}"
        if (source_dir / theme_icon).exists():
            return f"{icon_base_url}/{theme_icon}"
    return f"{icon_base_url}/core/src/main/{icon_file}"


remote_extensions: dict[str, index_pb2.Extension] = {}
index_json = repo_dir / "index.json"
if index_json.exists():
    remote_proto = json_format.Parse(index_json.read_text("utf-8"), index_pb2.Index())
    remote_extensions = {e.packageName: e for e in remote_proto.extensionList.extensions}

assets_path = repo_dir / "release-assets.json"
release_assets: dict[str, str] = (
    json.loads(assets_path.read_text("utf-8")) if assets_path.exists() else {}
)

built: list[tuple[index_pb2.Extension, Path, Path, bool]] = []
new_release_assets: dict[str, str] = {}
tag = sha[:7]

for info_file in artifacts_dir.glob("**/keiyoushi-source-info.json"):
    info = json.loads(info_file.read_text("utf-8"))
    package_name = info["packageName"]
    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
    if apk is None or jar is None:
        raise FileNotFoundError(f"{package_name}: apk or jar missing under {info_file.parent}")

    # same change detection as upstream: new version code or new content hash => new upload
    remote = remote_extensions.get(package_name)
    changed = (
        remote is None
        or remote.versionCode != info["versionCode"]
        or release_assets.get(package_name) != info["contentHash"]
    )
    new_release_assets[package_name] = info["contentHash"]

    ext = index_pb2.Extension(
        name=info["name"],
        packageName=package_name,
        resources=index_pb2.Resources(iconUrl=get_icon_url(info["module"], info.get("theme"))),
        extensionLib=info["extensionLib"],
        versionCode=info["versionCode"],
        versionName=info["versionName"],
        contentWarning=info["contentWarning"],
        sources=[
            index_pb2.Source(
                id=int(s["id"]),
                name=s["name"],
                language=s["lang"],
                homeUrl=s["baseUrl"],
                mirrorUrls=s.get("mirrorUrls", []),
            )
            for s in info["sources"]
        ],
    )
    if changed:
        ext.resources.apkUrl = f"{release_base_url}/{tag}/{apk.name}"
        ext.resources.jarUrl = f"{release_base_url}/{tag}/{jar.name}"
    else:
        ext.resources.apkUrl = remote.resources.apkUrl
        ext.resources.jarUrl = remote.resources.jarUrl
    built.append((ext, apk, jar, changed))

if not built:
    sys.exit("no keiyoushi-source-info.json found: nothing was built")

built.sort(key=lambda item: item[0].packageName)
extensions = [ext for ext, _, _, _ in built]

index = index_pb2.Index(
    name="RONNCC Keiyoushi fork",
    badgeLabel="FORK",
    signingKey=signing_key,
    contact=index_pb2.Contact(website=f"https://github.com/{repo_name}"),
    extensionList=index_pb2.ExtensionList(extensions=extensions),
)

(repo_dir / "index.json").write_text(
    json_format.MessageToJson(
        index, always_print_fields_with_no_presence=False, preserving_proto_field_name=True
    ),
    encoding="utf-8",
)
(repo_dir / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))
(repo_dir / "repo.json").write_text(
    json.dumps(
        {
            "index_v2": f"https://github.com/{repo_name}/raw/repo/index.pb",
            "meta": {
                "name": index.name,
                "website": index.contact.website,
                "signingKeyFingerprint": signing_key,
            },
        },
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)
# Mihon fetches a "[..." index.min.json, then repo.json and follows its index_v2 to index.pb,
# so the legacy URL works too; the list itself is unused (no legacy-format entries).
(repo_dir / "index.min.json").write_text("[]\n", encoding="utf-8")
assets_path.write_text(json.dumps(new_release_assets, indent=2, sort_keys=True) + "\n", encoding="utf-8")
with (repo_dir / "index.html").open("w", encoding="utf-8") as f:
    f.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for ext in extensions:
        f.write(f'<a href="{html.escape(ext.resources.apkUrl)}">{html.escape("Tachiyomi: " + ext.name)}</a>\n')
    f.write("</pre>\n</body>\n</html>\n")

# --- upload changed assets to a release tagged with the source sha ---
files = [f for _, apk, jar, changed in built if changed for f in (apk, jar)]
if not files:
    sys.exit(0)

if not run_gh("release", "view", tag, "--repo", repo_name, "--json", "tagName", success_errors=("release not found",)):
    run_gh(
        "release", "create", tag, "--repo", repo_name, "--draft",
        "--title", f"Repository Update {tag}",
        "--notes", f"Automated update from {repo_name}@{sha}",
    )

existing = {
    a["name"]: (a.get("digest") or "").removeprefix("sha256:")
    for a in json.loads(run_gh("release", "view", tag, "--repo", repo_name, "--json", "assets"))["assets"]
}
to_upload = [f for f in files if existing.get(f.name) != hashlib.sha256(f.read_bytes()).hexdigest()]
print(f"Uploading {len(to_upload)} assets to {tag}, skipping {len(files) - len(to_upload)}")
if to_upload:
    run_gh("release", "upload", tag, *map(str, to_upload), "--repo", repo_name, "--clobber")
run_gh("release", "edit", tag, "--repo", repo_name, "--draft=false")
