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

The default refresh interval is 300 seconds. External requests are made only for markets that are open according to local exchange calendars; all-closed periods only refresh local status. Configure market provider priority and the open-session delay with:

```sh
python server.py --cache-seconds 300 \
  --open-delay-seconds 60 \
  --market-providers "CN=eastmoney,sina;HK=global,yahoo;US=global,yahoo;JP=global,yahoo;KR=global,yahoo"
```
