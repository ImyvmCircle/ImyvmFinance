# ImyvmFinance quote sidecar

Requires Python 3.11 or newer.

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python server.py
```

The service binds to `127.0.0.1:8765` and exposes `/health` and `/quotes`.
It returns HTTP 503 when AKShare cannot provide any whitelisted quote; it never fabricates data.
