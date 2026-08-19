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
from urllib.parse import parse_qs, quote, urlparse
from urllib.request import Request, urlopen
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

YAHOO_SYMBOLS = {
    "HK:HSI": "^HSI",
    "HK:HSTECH": "^HSTECH",
    "US:DJI": "^DJI",
    "US:SPX": "^GSPC",
    "US:NDX": "^NDX",
    "JP:N225": "^N225",
    "KR:KOSPI": "^KS11",
}

DEFAULT_MARKET_PROVIDERS = {
    "CN": ("eastmoney", "sina"),
    "HK": ("global", "yahoo"),
    "US": ("global", "yahoo"),
    "JP": ("global", "yahoo"),
    "KR": ("global", "yahoo"),
}

_calendar_lock = threading.Lock()
_calendars: dict[str, Any] = {}

_cache_lock = threading.Lock()
_cache: tuple[float, dict[str, Any]] | None = None
_last_quotes: dict[str, dict[str, str]] = {}
_last_snapshot: dict[str, Any] | None = None
_last_market_digest: str | None = None
_last_market_time = 0
_unavailable: set[str] = set()
_market_unavailable: set[str] = set()
_market_providers = DEFAULT_MARKET_PROVIDERS
_active_providers: dict[str, str] = {market: providers[0] for market, providers in DEFAULT_MARKET_PROVIDERS.items()}
_disabled_providers: dict[str, set[str]] = {market: set() for market in MARKET_CALENDARS}
_manual_closed_markets: set[str] = set()
_open_delay_seconds = 60.0
_closed_poll_seconds = 30.0


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


def _market_statuses(now: datetime) -> dict[str, str]:
    return {
        market: "OPEN" if any(_market_status(symbol, now) == "OPEN"
            for symbol in INSTRUMENTS if symbol.startswith(market + ":")) else "CLOSED"
        for market in MARKET_CALENDARS
    }


def _active_markets(now: datetime) -> set[str]:
    return {market for market, status in _market_statuses(now).items()
        if status == "OPEN" and market not in _manual_closed_markets}


def _parse_market_providers(value: str) -> dict[str, tuple[str, ...]]:
    result = {market: tuple(providers) for market, providers in DEFAULT_MARKET_PROVIDERS.items()}
    for item in value.split(";"):
        if "=" not in item:
            continue
        market, providers = item.split("=", 1)
        market = market.strip().upper()
        names = tuple(name.strip().lower() for name in providers.split(",") if name.strip())
        if market in MARKET_CALENDARS and names:
            result[market] = names
    return result


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


def _fetch_china_eastmoney(ak, now: datetime) -> dict[str, dict[str, str]]:
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


def _fetch_china_sina(ak, now: datetime) -> dict[str, dict[str, str]]:
    wanted = {symbol.split(":", 1)[1]: symbol for symbol in INSTRUMENTS if symbol.startswith("CN:")}
    result: dict[str, dict[str, str]] = {}
    try:
        frame = ak.stock_zh_index_spot_sina()
    except Exception:
        return result
    for row in frame.to_dict("records"):
        code = _text(_row_value(row, "代码", "code")).replace("sh", "").replace("sz", "").zfill(6)
        symbol = wanted.get(code)
        if symbol is None:
            continue
        try:
            result[symbol] = _quote(symbol, _text(_row_value(row, "名称", "name")) or symbol,
                _row_value(row, "最新价", "最新价格", "price"),
                _row_value(row, "涨跌幅", "changePercent"), now)
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


def _fetch_yahoo(symbol: str, now: datetime) -> dict[str, str] | None:
    request = Request(
        "https://query1.finance.yahoo.com/v8/finance/chart/"
        + quote(YAHOO_SYMBOLS[symbol], safe="") + "?range=1d&interval=1m",
        headers={"User-Agent": "ImyvmFinance/1.0"},
    )
    with urlopen(request, timeout=3) as response:
        meta = json.load(response)["chart"]["result"][0]["meta"]
    price = _number(meta.get("regularMarketPrice"))
    previous = _number(meta.get("previousClose"))
    if price is None or previous is None or previous <= 0:
        return None
    return _quote(symbol, _text(meta.get("shortName")) or symbol, price,
        (price - previous) * 100 / previous, now)


def _provider_quotes(provider: str, ak: Any, now: datetime, active: set[str]) -> dict[str, dict[str, str]]:
    if provider == "eastmoney" and "CN" in active:
        return _fetch_china_eastmoney(ak, now)
    if provider == "sina" and "CN" in active:
        return _fetch_china_sina(ak, now)
    if provider == "global" and active & {"HK", "US", "JP", "KR"}:
        return _fetch_global(ak, now)
    return {}


def _fetch_market_quotes(active: set[str], now: datetime) -> tuple[dict[str, dict[str, str]], set[str]]:
    failed_markets: set[str] = set()
    ak = None
    provider_cache: dict[str, dict[str, dict[str, str]]] = {}
    result: dict[str, dict[str, str]] = {}
    for market in sorted(active):
        expected = {symbol for symbol in INSTRUMENTS if symbol.startswith(market + ":")}
        providers = tuple(provider for provider in _market_providers.get(market, ())
            if provider not in _disabled_providers.get(market, set()))
        if not providers:
            continue
        current = _active_providers.get(market, providers[0])
        ordered_providers = (current,) + tuple(provider for provider in providers if provider != current)
        market_result: dict[str, dict[str, str]] = {}
        for provider in ordered_providers:
            try:
                candidate: dict[str, dict[str, str]] = {}
                if provider == "yahoo":
                    for symbol in expected - market_result.keys():
                        fallback = _fetch_yahoo(symbol, now)
                        if fallback is not None:
                            candidate[symbol] = fallback
                else:
                    if ak is None:
                        ak = _akshare()
                    if provider not in provider_cache:
                        provider_cache[provider] = _provider_quotes(provider, ak, now, active)
                    candidate = {symbol: quote_value for symbol, quote_value in provider_cache[provider].items()
                        if symbol in expected}
                if expected <= candidate.keys():
                    market_result = candidate
                else:
                    for symbol, quote_value in candidate.items():
                        market_result.setdefault(symbol, quote_value)
            except Exception as exc:
                print(f"[sidecar] provider {provider} failed for {market}: {exc}")
                continue
            if expected <= market_result.keys():
                if _active_providers.get(market) != provider:
                    print(f"[sidecar] provider for {market} switched to {provider}")
                    _active_providers[market] = provider
                break
        if expected - market_result.keys():
            missing = ",".join(sorted(expected - market_result.keys()))
            print(f"[sidecar] all providers failed for {market}; missing {missing}")
            failed_markets.add(market)
        result.update(market_result)
    return result, failed_markets


def _market_alerts(failed_markets: set[str]) -> list[str]:
    global _market_unavailable
    alerts = ["failed:market:" + market for market in sorted(failed_markets - _market_unavailable)]
    alerts.extend("recovered:market:" + market for market in sorted(_market_unavailable - failed_markets))
    _market_unavailable = set(failed_markets)
    return alerts


def _build_snapshot(quotes: dict[str, dict[str, str]], now: datetime, failed_markets: set[str] | None = None) -> dict[str, Any]:
    global _last_market_digest, _last_market_time, _last_quotes, _last_snapshot
    if failed_markets is None:
        failed_markets = set()
    _last_quotes.update(quotes)
    statuses = _market_statuses(now)
    for quote_value in _last_quotes.values():
        quote_value["marketStatus"] = statuses.get(quote_value["symbol"].split(":", 1)[0], "CLOSED")
    ordered = [_last_quotes[symbol] for symbol in INSTRUMENTS if symbol in _last_quotes]
    if not ordered:
        raise RuntimeError("no whitelisted quotes are available")
    digest = hashlib.sha256(
        json.dumps([{key: value for key, value in quote_value.items() if key != "marketStatus"}
                    for quote_value in ordered], ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()[:16]
    timestamp = int(now.timestamp() * 1000)
    if digest != _last_market_digest:
        _last_market_digest = digest
        _last_market_time = timestamp
    available = {quote_value["symbol"] for quote_value in ordered}
    failed = set(INSTRUMENTS) - available
    alerts = _market_alerts(failed_markets)
    alerts.extend("failed:" + symbol for symbol in sorted(failed - _unavailable))
    alerts.extend("recovered:" + symbol for symbol in sorted(_unavailable & available))
    _unavailable.clear()
    _unavailable.update(failed)
    snapshot = {
        "snapshotId": f"quotes-{timestamp}-{digest}",
        "source": "multi-source",
        "fetchedAt": timestamp,
        "marketTime": _last_market_time,
        "quotes": ordered,
        "alerts": alerts,
    }
    _last_snapshot = snapshot
    return snapshot


def fetch_snapshot() -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    active = _active_markets(now)
    if active:
        fresh, failed_markets = _fetch_market_quotes(active, now)
        if fresh:
            return _build_snapshot(fresh, now, failed_markets)
        if _last_snapshot is not None:
            snapshot = dict(_last_snapshot)
            snapshot["alerts"] = _market_alerts(failed_markets)
            return snapshot
    if _last_quotes:
        return _build_snapshot({}, now, set())
    raise RuntimeError("all markets are closed and quote cache is empty")


def cached_snapshot() -> dict[str, Any]:
    with _cache_lock:
        if _cache is None:
            raise RuntimeError("quote cache is not ready")
        return _cache[1]


def refresh_loop(refresh_seconds: float) -> None:
    global _cache
    was_active = False
    while True:
        active = _active_markets(datetime.now(timezone.utc))
        if not active:
            was_active = False
            if _last_quotes:
                with _cache_lock:
                    _cache = (time.monotonic(), fetch_snapshot())
            time.sleep(max(5.0, _closed_poll_seconds))
            continue
        if not was_active and _open_delay_seconds > 0:
            time.sleep(_open_delay_seconds)
            active = _active_markets(datetime.now(timezone.utc))
            if not active:
                was_active = False
                continue
        was_active = True
        try:
            snapshot = fetch_snapshot()
            with _cache_lock:
                _cache = (time.monotonic(), snapshot)
        except Exception as exc:
            print(f"[sidecar] quote refresh failed: {exc}")
        time.sleep(max(1.0, refresh_seconds))


class Handler(BaseHTTPRequestHandler):
    cache_seconds = 300.0

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send(200, {"status": "ok"})
            return
        if parsed.path == "/control/status":
            self._send(200, {
                "markets": {market: {"closed": market in _manual_closed_markets,
                    "providers": list(_market_providers.get(market, ())),
                    "disabledProviders": sorted(_disabled_providers.get(market, set()))}
                    for market in MARKET_CALENDARS}
            })
            return
        if parsed.path != "/quotes":
            self._send(404, {"error": "not found"})
            return
        try:
            self._send(200, cached_snapshot())
        except Exception as exc:
            self._send(503, {"error": str(exc)})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        market = query.get("market", [""])[0].upper()
        enabled_value = query.get("enabled", [""])[0].lower()
        if market not in MARKET_CALENDARS or enabled_value not in {"true", "false"}:
            self._send(400, {"error": "invalid market or enabled value"})
            return
        enabled = enabled_value == "true"
        if parsed.path == "/control/market":
            if enabled:
                _manual_closed_markets.discard(market)
            else:
                _manual_closed_markets.add(market)
            self._send(200, {"market": market, "closed": not enabled})
            return
        if parsed.path == "/control/provider":
            provider = query.get("provider", [""])[0].lower()
            if provider not in _market_providers.get(market, ()):
                self._send(400, {"error": "unknown provider for market"})
                return
            if enabled:
                _disabled_providers.setdefault(market, set()).discard(provider)
            else:
                _disabled_providers.setdefault(market, set()).add(provider)
                if _active_providers.get(market) == provider:
                    available = [candidate for candidate in _market_providers[market]
                        if candidate not in _disabled_providers[market]]
                    if available:
                        _active_providers[market] = available[0]
            self._send(200, {"market": market, "provider": provider, "disabled": not enabled})
            return
        self._send(404, {"error": "not found"})

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
    parser.add_argument("--cache-seconds", type=float, default=300.0)
    parser.add_argument("--market-providers", default="CN=eastmoney,sina;HK=global,yahoo;US=global,yahoo;JP=global,yahoo;KR=global,yahoo")
    parser.add_argument("--open-delay-seconds", type=float, default=60.0)
    parser.add_argument("--closed-poll-seconds", type=float, default=30.0)
    args = parser.parse_args()
    global _market_providers, _active_providers, _open_delay_seconds, _closed_poll_seconds
    _market_providers = _parse_market_providers(args.market_providers)
    _active_providers = {market: providers[0] for market, providers in _market_providers.items() if providers}
    _open_delay_seconds = max(0.0, args.open_delay_seconds)
    _closed_poll_seconds = max(5.0, args.closed_poll_seconds)
    Handler.cache_seconds = max(0.0, args.cache_seconds)
    threading.Thread(target=refresh_loop, args=(Handler.cache_seconds,), daemon=True).start()
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
