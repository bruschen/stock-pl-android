"""透過 Chrome DevTools Protocol 操作 App 內的 WebView，驗證新版介面的功能與計算。

用法：python3 cdp_test.py <輸出資料夾>
前提：已用 adb forward 把 WebView 偵錯 socket 轉到 localhost:9222。
結果寫入 <輸出資料夾>/results.md 與 results.json，關鍵畫面截圖存成 PNG。
"""
import json
import math
import subprocess
import sys
import time
import urllib.request

import websocket  # pip install websocket-client

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
FEE_RATE, FEE_MIN = 0.001425, 20
results = []


# ── 期望值：依規格獨立計算，不重用網頁裡的程式 ──────────────────
def floor_int(x):
    return math.floor(x + 1e-6)


def expected(buy, shares, now, fee_on, tax_rate):
    fee = (lambda a: max(FEE_MIN, floor_int(a * FEE_RATE))) if fee_on else (lambda a: 0)
    buy_amt = round(buy * shares, 2)
    cost = round(buy_amt + fee(buy_amt), 2)
    sell_amt = round(now * shares, 2)
    net = round(sell_amt - fee(sell_amt) - floor_int(sell_amt * tax_rate), 2)
    pl = round(net - cost, 2)
    return {"pl": pl, "ret": pl / cost}


def fmt_money(x):
    s = f"{abs(x):,.2f}".rstrip("0").rstrip(".")
    return ("+" if x > 0 else "−" if x < 0 else "") + s


def fmt_pct(x):
    return ("+" if x > 0 else "−" if x < 0 else "") + f"{abs(x * 100):.2f}%"


# ── CDP 連線 ─────────────────────────────────────────────────
pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json"))
page = next(p for p in pages if p.get("type") == "page")
ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=30)
_id = 0


def js(expr):
    global _id
    _id += 1
    ws.send(json.dumps({"id": _id, "method": "Runtime.evaluate",
                        "params": {"expression": expr, "returnByValue": True, "awaitPromise": True}}))
    while True:
        msg = json.loads(ws.recv())
        if msg.get("id") == _id:
            r = msg.get("result", {})
            if "exceptionDetails" in r:
                raise RuntimeError(r["exceptionDetails"].get("text", "JS error"))
            return r.get("result", {}).get("value")


def wait_for(expr, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        if js(expr):
            return True
        time.sleep(0.5)
    return False


def set_val(id_, value):
    js(f"""(() => {{ const el = document.getElementById({json.dumps(id_)});
        el.value = {json.dumps(str(value))};
        el.dispatchEvent(new Event('input', {{bubbles: true}})); }})()""")


def text(id_):
    return js(f"document.getElementById({json.dumps(id_)}).textContent.trim()")


def click(selector):
    js(f"document.querySelector({json.dumps(selector)}).click()")


def go_and_wait():
    click("#go")
    time.sleep(0.3)
    wait_for("!document.getElementById('go').disabled", 25)
    time.sleep(0.5)


def shot(name):
    js("window.scrollTo(0, 0)")
    time.sleep(0.5)
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)


def check(name, ok, detail=""):
    results.append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)


def price_now():
    return float(text("qPrice").replace(",", ""))


def verify_calc(label, buy, shares, fee_on, tax_rate):
    now = price_now()
    e = expected(buy, shares, now, fee_on, tax_rate)
    got_pl, got_ret = text("pl").replace("元", "").strip(), text("ret")
    ok = got_pl == fmt_money(e["pl"]) and got_ret == fmt_pct(e["ret"])
    check(label, ok, f"現價 {now}；App 盈虧 {got_pl}、報酬率 {got_ret}；期望 {fmt_money(e['pl'])}、{fmt_pct(e['ret'])}")


# ── 測試案例 ─────────────────────────────────────────────────
wait_for("document.readyState === 'complete'", 15)

check("1. 頁面載入、偵測到 App 報價通道",
      js("typeof window.AndroidQuote?.fetch === 'function'") and text("srcLabel") == "App 報價 · 非即時",
      f"來源標示：{text('srcLabel')}")

go_and_wait()
err = text("err")
check("2. 欄位空白時按 GO 會提示錯誤", all(k in err for k in ["股票代號", "買入價", "股數"]), err)

set_val("code", "2330")
found = wait_for("!!document.querySelector('#org .name')", 20)
check("3. 輸入代號 2330 自動查到公司", found, text("org"))

set_val("buy", "2200")
click('#quick button[data-shares="1000"]')
check("4. 「1 張」快捷鍵帶入 1000 股", js("document.getElementById('shares').value") == "1000")

go_and_wait()
got_quote = js("!document.getElementById('quoteBox').hidden") and text("pill") in ("賺了", "虧了", "打平")
check("5. 按 GO 取得現價並出結果", got_quote, f"現價 {text('qPrice')}；{text('qMeta')}；{text('pill')}")
if got_quote:
    verify_calc("6. 盈虧計算正確（預設：不計手續費、證交稅 0.3%）", 2200, 1000, False, 0.003)
    shot("1-result-default")

    click("#feeOn")
    time.sleep(0.5)
    verify_calc("7. 打開「計入手續費」後重算正確", 2200, 1000, True, 0.003)

    click('#kind button[data-tax="0.001"]')
    time.sleep(0.5)
    verify_calc("8. 切換為 ETF（證交稅 0.1%）後重算正確", 2200, 1000, True, 0.001)

    click('#quick button[data-shares="2000"]')
    time.sleep(0.5)
    verify_calc("9. 改成「2 張」後重算正確", 2200, 2000, True, 0.001)
    js("document.querySelector('details.detail').open = true")
    shot("2-result-fee-etf")

    click('#kind button[data-tax="0.003"]')
    click("#feeOn")
    time.sleep(0.3)

set_val("code", "2317")
time.sleep(0.5)
check("10. 改代號後舊結果作廢、提示重新按 GO",
      text("pill") == "—" and "代號已變更" in text("insight"), text("insight"))

set_val("code", "6488")
wait_for("!!document.querySelector('#org .name')", 20)
set_val("buy", "400")
set_val("shares", "1000")
go_and_wait()
ok_otc = js("!document.getElementById('quoteBox').hidden") and text("pill") != "—"
check("11. 上櫃股票 6488 查得到報價", ok_otc, f"公司：{text('org')}；現價 {text('qPrice')}；{text('err')}")
if ok_otc:
    verify_calc("12. 上櫃股票盈虧計算正確", 400, 1000, False, 0.003)
shot("3-otc")

set_val("code", "9999")
time.sleep(2.5)
org_text = text("org")
go_and_wait()
err = text("err")
manual_shown = js("!document.getElementById('manualWrap').hidden")
check("13. 不存在的代號 9999：代號欄位提示「查無此代號」", "查無此代號" in org_text, org_text)
check("14. 不存在的代號 9999：按 GO 顯示錯誤並開放手動現價",
      bool(err) and manual_shown, f"代號提示：{org_text}；錯誤列：{err}")
shot("4-invalid-code")

set_val("buy", "100")
set_val("shares", "1000")
set_val("now", "110")
go_and_wait()
check("15. 手動輸入現價 110 後可以試算", text("qMeta") == "手動輸入" and text("pill") == "賺了",
      f"{text('qMeta')}；{text('pill')} {text('pl')}")
if text("pill") == "賺了":
    e = expected(100, 1000, 110, False, 0.003)
    got = text("pl").replace("元", "").strip()
    check("16. 手動價格計算正確", got == fmt_money(e["pl"]), f"App {got}；期望 {fmt_money(e['pl'])}")
shot("5-manual-price")

ws.close()

passed = sum(r["ok"] for r in results)
with open(f"{OUT}/results.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
with open(f"{OUT}/results.md", "w", encoding="utf-8") as f:
    f.write(f"# 驗證結果：{passed}/{len(results)} 通過\n\n")
    for r in results:
        f.write(f"- {'✅' if r['ok'] else '❌'} {r['name']}" + (f"\n  - {r['detail']}" if r["detail"] else "") + "\n")
print(f"{passed}/{len(results)} passed")
