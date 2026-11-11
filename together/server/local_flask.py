"""Run the local Pooly Flask app with the departure reminder worker."""
import argparse
import threading

from werkzeug.serving import WSGIRequestHandler

from .flask_app import create_app


class PoolyRequestHandler(WSGIRequestHandler):
    def log_request(self, code="-", size="-"):
        # Invite tokens appear in request paths.
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8769)
    args = parser.parse_args()
    app = create_app()
    stop = threading.Event()

    def reminders():
        while not stop.wait(10):
            try:
                app.extensions["run_reminders"]()
            except Exception as error:
                app.logger.error("Reminder failure: %s", type(error).__name__)

    threading.Thread(target=reminders, name="pooly-reminders", daemon=True).start()
    try:
        app.run(host="127.0.0.1", port=args.port, use_reloader=False, request_handler=PoolyRequestHandler)
    finally:
        stop.set()


if __name__ == "__main__":
    main()
