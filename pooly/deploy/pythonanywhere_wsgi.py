"""Paste into PythonAnywhere's Web > WSGI configuration file; edit USERNAME."""
import os
import sys

project_root = "/home/USERNAME/pooly"
if project_root not in sys.path:
    sys.path.insert(0, project_root)

os.environ["POOLY_PUBLIC_ORIGIN"] = "https://USERNAME.pythonanywhere.com"
os.environ["POOLY_DATABASE_PATH"] = project_root + "/pooly/data/pooly-flask.sqlite3"
os.environ["POOLY_SQLITE_JOURNAL_MODE"] = "DELETE"
# Optional; keep this server-side. Never put it in mobile-web.js.
# os.environ["VWORLD_API_KEY"] = "your-key"
# os.environ["VWORLD_DOMAIN"] = "https://USERNAME.pythonanywhere.com"
# os.environ["KAKAO_REST_API_KEY"] = "your-key"

from pooly.server.wsgi import application
