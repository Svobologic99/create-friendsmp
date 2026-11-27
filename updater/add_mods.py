"""Add the Modrinth mods listed in updater/mods-to-add.txt (and their required dependencies)
to the packwiz pack, if they are not in it yet. Never removes or changes existing mods.

Runs on GitHub Actions (it needs api.modrinth.com).
Usage: python3 updater/add_mods.py <pack root>
"""
import hashlib
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

UA = "SvobologicStudios/friendsmp-updater (github.com/Svobologic99/create-friendsmp)"
MC = "1.21.1"
LOADER = "neoforge"

root = pathlib.Path(sys.argv[1])
mods = root / "mods"
wanted = [l.strip() for l in (root / "updater/mods-to-add.txt").read_text().splitlines()
          if l.strip() and not l.startswith("#")]


def api(path):
    req = urllib.request.Request("https://api.modrinth.com/v2/" + path, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def q(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


present = set()
for f in mods.glob("*.pw.toml"):
    m = re.search(r'(?m)^mod-id = "([^"]+)"', f.read_text(encoding="utf-8"))
    if m:
        present.add(m.group(1))

new_entries = {}


def add(slug_or_id):
    project = api("project/" + urllib.parse.quote(slug_or_id))
    if project["id"] in present:
        print(f"{project['title']}: already in the pack")
        return
    params = urllib.parse.urlencode({"loaders": json.dumps([LOADER]), "game_versions": json.dumps([MC])})
    versions = api(f"project/{project['id']}/version?{params}")
    if not versions:
        sys.exit(f"{project['title']}: no {LOADER} {MC} version on Modrinth")
    versions.sort(key=lambda v: ({"release": 2, "beta": 1}.get(v["version_type"], 0), v["date_published"]), reverse=True)
    v = versions[0]
    f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
    cs, ss = project.get("client_side"), project.get("server_side")
    side = "server" if cs == "unsupported" else "client" if ss == "unsupported" else "both"
    toml = (
        f"name = {q(project['title'])}\n"
        f"filename = {q(f['filename'])}\n"
        f"side = {q(side)}\n\n"
        f"[download]\n"
        f"url = {q(f['url'])}\n"
        f'hash-format = "sha1"\n'
        f"hash = {q(f['hashes']['sha1'])}\n\n"
        f"[update]\n[update.modrinth]\n"
        f"mod-id = {q(project['id'])}\n"
        f"version = {q(v['id'])}\n"
    )
    stem = re.sub(r"\.jar$", "", f["filename"])
    rel = f"mods/{stem}.pw.toml"
    (root / rel).write_bytes(toml.encode("utf-8"))
    new_entries[rel] = hashlib.sha256(toml.encode("utf-8")).hexdigest()
    present.add(project["id"])
    print(f"{project['title']}: added {v['version_number']} ({f['filename']}), side {side}")
    for dep in v.get("dependencies", []):
        if dep.get("dependency_type") == "required" and dep.get("project_id"):
            add(dep["project_id"])


for w in wanted:
    add(w)

if not new_entries:
    sys.exit(0)

index_path = root / "index.toml"
index = index_path.read_text(encoding="utf-8")
parts = re.split(r"(?m)^(?=\[\[files\]\])", index)
head, entries = parts[0], parts[1:]
entries = [e for e in entries if not any(f'file = "{rel}"' in e for rel in new_entries)]
for rel, h in new_entries.items():
    entries.append(f'[[files]]\nfile = "{rel}"\nhash = "{h}"\nmetafile = true\n')
new_index = head + "".join(e.rstrip("\n") + "\n\n" for e in entries)
new_index = new_index.rstrip("\n") + "\n"
index_path.write_bytes(new_index.encode("utf-8"))
index_hash = hashlib.sha256(new_index.encode("utf-8")).hexdigest()

pack_path = root / "pack.toml"
pack = pack_path.read_text(encoding="utf-8")
pack, n = re.subn(r'(\[index\][^\[]*?\nhash = ")[0-9a-f]+(")', lambda m: m.group(1) + index_hash + m.group(2), pack, flags=re.S)
if n != 1:
    sys.exit("could not find [index] hash in pack.toml")
pack_path.write_bytes(pack.encode("utf-8"))
