"""Add the Modrinth resource packs listed in updater/resourcepacks.txt to the packwiz pack.

Runs on GitHub Actions (it needs api.modrinth.com). For each slug it picks the newest version
for Minecraft 1.21.1 (falling back to the newest 1.21.x, then the newest overall), writes
resourcepacks/<slug>.pw.toml and refreshes index.toml and pack.toml hashes.
Usage: python3 updater/add_resourcepacks.py <pack root>
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

root = pathlib.Path(sys.argv[1])
slugs = [l.strip() for l in (root / "updater/resourcepacks.txt").read_text().splitlines()
         if l.strip() and not l.startswith("#")]


def api(path):
    req = urllib.request.Request("https://api.modrinth.com/v2/" + path, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def q(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def pick(versions):
    for test in (lambda gv: MC in gv, lambda gv: any(g.startswith("1.21") for g in gv), lambda gv: True):
        ok = [v for v in versions if test(v["game_versions"])]
        if ok:
            ok.sort(key=lambda v: v["date_published"], reverse=True)
            return ok[0]
    return None


rp_dir = root / "resourcepacks"
rp_dir.mkdir(exist_ok=True)
written = {}
for slug in slugs:
    project = api("project/" + urllib.parse.quote(slug))
    v = pick(api(f"project/{project['id']}/version"))
    if v is None:
        sys.exit(f"{slug}: no versions")
    f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
    toml = (
        f"name = {q(project['title'])}\n"
        f"filename = {q(f['filename'])}\n"
        f'side = "client"\n\n'
        f"[download]\n"
        f"url = {q(f['url'])}\n"
        f'hash-format = "sha1"\n'
        f"hash = {q(f['hashes']['sha1'])}\n\n"
        f"[update]\n[update.modrinth]\n"
        f"mod-id = {q(project['id'])}\n"
        f"version = {q(v['id'])}\n"
    )
    rel = f"resourcepacks/{slug}.pw.toml"
    (root / rel).write_bytes(toml.encode("utf-8"))
    written[rel] = hashlib.sha256(toml.encode("utf-8")).hexdigest()
    print(f"{slug}: {v['version_number']} ({', '.join(v['game_versions'][-3:])}) -> {f['filename']}")

# Drop pw.toml files for slugs that were removed from the list.
for old in rp_dir.glob("*.pw.toml"):
    if f"resourcepacks/{old.name}" not in written:
        old.unlink()

index_path = root / "index.toml"
index = index_path.read_text(encoding="utf-8")
parts = re.split(r"(?m)^(?=\[\[files\]\])", index)
head, entries = parts[0], parts[1:]
entries = [e for e in entries if 'file = "resourcepacks/' not in e]
for rel, h in written.items():
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
