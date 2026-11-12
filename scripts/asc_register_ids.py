"""Register the app's IDs with Apple (App Store Connect API). Safe to run again."""
import json, os, sys, time, urllib.request, urllib.error
import jwt  # PyJWT

KEY_ID = os.environ["ASC_KEY_ID"]; ISSUER = os.environ["ASC_ISSUER_ID"]; P8 = os.environ["ASC_KEY_P8"]
IDS = [("com.wood29.nuvioplus", "Nuvio Plus"), ("com.wood29.nuvioplus.DownloadsWidget", "Nuvio Plus Downloads Widget")]

def token():
    now = int(time.time())
    return jwt.encode({"iss": ISSUER, "iat": now, "exp": now + 1000, "aud": "appstoreconnect-v1"}, P8,
                      algorithm="ES256", headers={"kid": KEY_ID, "typ": "JWT"})

def call(method, path, body=None):
    req = urllib.request.Request("https://api.appstoreconnect.apple.com" + path, method=method,
                                 data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")

status, data = call("GET", "/v1/bundleIds?limit=200")
if status != 200:
    print("The key doesn't work:", status, json.dumps(data)[:500]); sys.exit(1)
have = {b["attributes"]["identifier"] for b in data.get("data", [])}
for ident, name in IDS:
    if ident in have:
        print("already registered:", ident); continue
    s, d = call("POST", "/v1/bundleIds", {"data": {"type": "bundleIds", "attributes": {"identifier": ident, "name": name, "platform": "IOS"}}})
    print(("registered: " if s == 201 else f"failed ({s}): ") + ident, "" if s == 201 else json.dumps(d)[:400])
    if s != 201: sys.exit(1)
s, apps = call("GET", "/v1/apps?filter[bundleId]=com.wood29.nuvioplus")
print("App Store Connect app entry:", "found" if apps.get("data") else "not created yet")
