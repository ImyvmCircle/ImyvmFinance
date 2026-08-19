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

The sidecar reads at hourly minute 01 and every five minutes afterward, with a random jitter of plus or minus 15 seconds. The Java process polls at the nominal node plus 17 seconds. Use --random-seed to reproduce a schedule. External requests are made only for markets that are open according to local exchange calendars; all-closed periods only refresh local status. Configure market provider priority and the read schedule with:

```sh
python server.py --read-interval-minutes 5 \
  --read-jitter-seconds 15 \
  --random-seed 20260819 \
  --market-providers "CN=eastmoney,sina;HK=global,yahoo;US=global,yahoo;CRYPTO=binance,coinbase"
```
