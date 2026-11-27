"""Put the freshly built updater jar into the packwiz pack and refresh the hashes.

Usage: python3 updater/publish.py <pack root> <built jar>
"""
import hashlib
import pathlib
import re
import shutil
import sys

root = pathlib.Path(sys.argv[1])
jar = pathlib.Path(sys.argv[2])
mods = root / "mods"

for old in mods.glob("friendsmp-updater-*.jar"):
    old.unlink()
dest = mods / jar.name
shutil.copyfile(jar, dest)
jar_hash = hashlib.sha256(dest.read_bytes()).hexdigest()

index_path = root / "index.toml"
index = index_path.read_text(encoding="utf-8")
parts = re.split(r"(?m)^(?=\[\[files\]\])", index)
head, entries = parts[0], parts[1:]
entries = [e for e in entries if 'file = "mods/friendsmp-updater-' not in e]
entries.append(f'[[files]]\nfile = "mods/{jar.name}"\nhash = "{jar_hash}"\n')
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
print(f"added {dest} ({jar_hash}), index hash {index_hash}")
