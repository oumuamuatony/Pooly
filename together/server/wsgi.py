"""WSGI entry point; configure environment before importing."""
from .flask_app import create_app

application = create_app()
