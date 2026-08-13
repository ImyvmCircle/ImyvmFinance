#!/usr/bin/env python3
"""Local quote sidecar for ImyvmFinance.

The sidecar owns all external market-data access. It never fabricates a quote:
unavailable instruments are omitted and an empty snapshot returns HTTP 503.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
import exchange_calendars as xcals
import pandas as pd

INSTRUMENTS = (
    "CN:000001",
    "CN:399001",
    "CN:399006",
    "CN:000300",
    "CN:000905",
    "HK:HSI",
    "HK:HSTECH",
    "US:DJI",
    "US:SPX",
    "US:NDX",
    "JP:N225",
    "KR:KOSPI",
)

CHINA_CATEGORIES = ("沪深重要指数", "上证系列指数", "深证系列指数", "中证系列指数")
GLOBAL_ALIASES = {
    "HK:HSI": {"HSI", "恒生指数", "HANG SENG"},
    "HK:HSTECH": {"HSTECH", "恒生科技指数", "HANG SENG TECH"},
    "US:DJI": {"DJI", "DJIA", "道琼斯", "DOW JONES"},
    "US:SPX": {"SPX", "GSPC", "标普500", "S&P 500"},
    "US:NDX": {"NDX", "NASDAQ 100", "纳斯达克100"},
    "JP:N225": {"N225", "日经225", "NIKKEI 225"},
    "KR:KOSPI": {"KOSPI", "韩国综合指数"},
}

MARKET_CALENDARS = {
    "CN": "XSHG",
    "HK": "XHKG",
    "US": "XNYS",
    "JP": "XTKS",
    "KR": "XKRX",
}

_calendar_lock = threading.Lock()
_calendars: dict[str, Any] = {}

_cache_lock = threading.Lock()
_quote_request_slots = threading.BoundedSemaphore(1)
_cache: tuple[float, dict[str, Any]] | None = None


def _akshare():
    try:
        import akshare as ak
    except ImportError as exc:
        raise RuntimeError("akshare is not installed; install sidecar/requirements.txt") from exc
    return ak


def _text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _number(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _market_status(symbol: str, now: datetime) -> str:
    market = symbol.split(":", 1)[0]
    try:
        with _calendar_lock:
            calendar = _calendars.get(market)
            if calendar is None:
                calendar = xcals.get_calendar(MARKET_CALENDARS[market])
                _calendars[market] = calendar
        return "OPEN" if calendar.is_trading_minute(pd.Timestamp(now)) else "CLOSED"
    except Exception:
        return "CLOSED"


def _quote(symbol: str, name: str, price: Any, change: Any, now: datetime) -> dict[str, str]:
    numeric_price = _number(price)
    numeric_change = _number(change)
    if not name or numeric_price is None or numeric_price <= 0 or numeric_change is None:
        raise ValueError(f"invalid quote for {symbol}")
    return {
        "symbol": symbol,
        "name": name,
        "price": format(numeric_price, ".10f").rstrip("0").rstrip("."),
        "changePercent": format(numeric_change, ".10f").rstrip("0").rstrip("."),
        "marketStatus": _market_status(symbol, now),
    }


def _row_value(row: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in row:
            return row[name]
    return None


def _fetch_china(ak, now: datetime) -> dict[str, dict[str, str]]:
    wanted = {symbol.split(":", 1)[1]: symbol for symbol in INSTRUMENTS if symbol.startswith("CN:")}
    result: dict[str, dict[str, str]] = {}
    for category in CHINA_CATEGORIES:
        try:
            frame = ak.stock_zh_index_spot_em(symbol=category)
        except Exception:
            continue
        for row in frame.to_dict("records"):
            code = _text(_row_value(row, "代码", "code")).zfill(6)
            symbol = wanted.get(code)
            if symbol is None or symbol in result:
                continue
            try:
                result[symbol] = _quote(
                    symbol,
                    _text(_row_value(row, "名称", "name")) or symbol,
                    _row_value(row, "最新价", "最新价格", "price"),
                    _row_value(row, "涨跌幅", "changePercent"),
                    now,
                )
            except ValueError:
                continue
    return result


def _global_symbol(row: dict[str, Any]) -> str | None:
    code = _text(_row_value(row, "代码", "code")).upper()
    name = _text(_row_value(row, "名称", "name")).upper()
    for symbol, aliases in GLOBAL_ALIASES.items():
        if code in aliases or name in {alias.upper() for alias in aliases}:
            return symbol
    return None


def _fetch_global(ak, now: datetime) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    try:
        frame = ak.index_global_spot_em()
    except Exception:
        return result
    for row in frame.to_dict("records"):
        symbol = _global_symbol(row)
        if symbol is None or symbol in result:
            continue
        try:
            result[symbol] = _quote(
                symbol,
                _text(_row_value(row, "名称", "name")) or symbol,
                _row_value(row, "最新价", "最新价格", "price"),
                _row_value(row, "涨跌幅", "changePercent"),
                now,
            )
        except ValueError:
            continue
    return result


def fetch_snapshot() -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    ak = _akshare()
    quotes = _fetch_china(ak, now)
    quotes.update(_fetch_global(ak, now))
    ordered = [quotes[symbol] for symbol in INSTRUMENTS if symbol in quotes]
    if not ordered:
        raise RuntimeError("no whitelisted quotes are available")
    digest = hashlib.sha256(
        json.dumps(ordered, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()[:16]
    timestamp = int(now.timestamp() * 1000)
    return {
        "snapshotId": f"akshare-{timestamp}-{digest}",
        "source": "akshare",
        "fetchedAt": timestamp,
        "marketTime": timestamp,
        "quotes": ordered,
    }


def cached_snapshot(cache_seconds: float) -> dict[str, Any]:
    global _cache
    with _cache_lock:
        now = time.monotonic()
        if _cache is not None and now - _cache[0] < cache_seconds:
            return _cache[1]
        snapshot = fetch_snapshot()
        _cache = (time.monotonic(), snapshot)
        return snapshot


class Handler(BaseHTTPRequestHandler):
    cache_seconds = 60.0

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send(200, {"status": "ok"})
            return
        if self.path != "/quotes":
            self._send(404, {"error": "not found"})
            return
        if not _quote_request_slots.acquire(blocking=False):
            self._send(503, {"error": "quote refresh is in progress"})
            return
        try:
            self._send(200, cached_snapshot(self.cache_seconds))
        except Exception as exc:
            self._send(503, {"error": str(exc)})
        finally:
            _quote_request_slots.release()
    
    def _send(self, status: int, body: dict[str, Any]) -> None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"[sidecar] {format % args}")


def main() -> None:
    parser = argparse.ArgumentParser(description="ImyvmFinance quote sidecar")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cache-seconds", type=float, default=60.0)
    args = parser.parse_args()
    Handler.cache_seconds = max(0.0, args.cache_seconds)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"ImyvmFinance quote sidecar listening on http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
