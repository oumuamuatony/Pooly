"""Package deployable source/assets; never include databases, tokens, or venvs."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "together"
OUTPUT = APP / "artifacts/pooly-pythonanywhere.zip"
files = {APP / "requirements.txt"}
for directory in ["server", "tests", "deploy"]:
    files.update(p for p in (APP / directory).rglob("*") if p.suffix in {".py", ".md", ".html"} and "__pycache__" not in p.parts)
for name in ["phone.html", "mobile-web.js", "mobile-web.css", "pickup-heatmap.js", "car.html", "car-adapter.js", "core.js", "pleos.js", "guidance-notices.js", "boarding.js", "maps.js", "layout.js", "app.css"]:
    files.add(APP / "web" / name)
for directory in ["vendor", "assets"]:
    files.update(p for p in (APP / "web" / directory).rglob("*") if p.is_file() and not p.is_symlink())
OUTPUT.parent.mkdir(exist_ok=True)
with ZipFile(OUTPUT, "w", ZIP_DEFLATED) as archive:
    for path in sorted(files):
        if path.is_symlink() or not path.is_file():
            raise RuntimeError("Expected regular source file: " + str(path))
        archive.write(path, path.relative_to(ROOT))
print(f"{OUTPUT}\n{len(files)} files; {OUTPUT.stat().st_size} bytes. No runtime database or virtualenv included.")
