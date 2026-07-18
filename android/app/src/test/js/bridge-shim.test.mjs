import assert from "node:assert/strict"
import { createHmac } from "node:crypto"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const bearerToken = "a".repeat(64)
const capabilityKey = "b".repeat(64)
const capabilityExpiration = "2000043200"
const sourcePath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../main/java/online/timefiles/vantaloom/MainActivity.kt"
)
const source = fs.readFileSync(sourcePath, "utf8")
const match = source.match(
  /private val BRIDGE_SHIM = """\r?\n(?<js>[\s\S]*?)\r?\n"""\.trimIndent\(\)/
)
assert.ok(match?.groups?.js, "BRIDGE_SHIM not found")
const shim = match.groups.js.replaceAll(
  "${LoopbackAuth.queryParameter}",
  "__vantaloom_loopback_token"
).replaceAll(
  "${LoopbackAuth.expirationQueryParameter}",
  "__vantaloom_loopback_exp"
)

const calls = {
  authorize: [],
  fetch: [],
  eventSource: [],
  webSocket: [],
  xhr: [],
  open: [],
  restore: 0,
  restoreArgs: [],
}
globalThis.window = globalThis
window.top = window
window.location = {
  href: "http://vantaloom.localhost/index.html",
  assign(url) {
    calls.open.push(String(url))
  },
}
window.__loomNative = {
  isNative: () => true,
  deviceId: () => "android-test",
  loopbackPort: () => 0,
  statusJSON: () => '{"state":"idle"}',
  setToken() {},
  setChrome() {},
  startNode() {},
  connect() {},
  disconnect() {},
  stopNode() {},
  pickImages() {},
  pickFiles() {},
  pickFolder() {},
  shareFile() {},
  startLocalRuntime() {},
  stopLocalRuntime() {},
  authorizeLocalRuntimeUrl(raw) {
    const original = String(raw)
    const url = new URL(original)
    if (
      !["http:", "ws:"].includes(url.protocol) ||
      url.hostname !== "127.0.0.1" ||
      url.port !== "8780"
    ) {
      return original
    }
    const requestTarget = `${url.pathname}${url.search}`
    const signature = createHmac(
      "sha256",
      Buffer.from(capabilityKey, "hex")
    )
      .update(`${requestTarget}\n${capabilityExpiration}`)
      .digest("hex")
    const separator = url.search ? "&" : "?"
    const signed =
      `${url.protocol}//${url.host}${requestTarget}${separator}` +
      `__vantaloom_loopback_exp=${capabilityExpiration}&` +
      `__vantaloom_loopback_token=${signature}`
    calls.authorize.push({ original, requestTarget, signed, signature })
    return signed
  },
  restoreLocalRuntimeAuth() {
    calls.restore += 1
    const args = ["http://127.0.0.1:8780", bearerToken]
    calls.restoreArgs.push(args)
    window.__loomInstallLocalRuntimeAuth(...args)
  },
}
window.fetch = async (input, init) => {
  const request = new Request(input, init)
  calls.fetch.push({
    url: request.url,
    authorization: request.headers.get("Authorization"),
  })
  return { ok: true }
}
class MockXHR {
  constructor() {
    this.headers = new Map()
  }
  open(_method, url) {
    this.url = String(url)
  }
  setRequestHeader(name, value) {
    this.headers.set(name, value)
  }
  send() {
    calls.xhr.push({
      url: this.url,
      authorization: this.headers.get("Authorization"),
    })
  }
}
window.XMLHttpRequest = MockXHR
window.EventSource = class {
  constructor(url) {
    calls.eventSource.push(String(url))
  }
}
window.WebSocket = class {
  constructor(url) {
    calls.webSocket.push(String(url))
  }
}

eval(shim)

assert.equal(calls.restore, 1, "reload did not request native auth restore")
await window.fetch("http://127.0.0.1:8780/v1/hub/status")
const resourceUrl = window.__loomAuthorizeLocalRuntimeURL(
  "http://127.0.0.1:8780/v1/files/raw?path=%2Fdata%2Fa.png&x=2#private"
)
await window.fetch(resourceUrl)
const remoteUrl = "https://example.com/data"
assert.equal(window.__loomAuthorizeLocalRuntimeURL(remoteUrl), remoteUrl)
await window.fetch(remoteUrl)

const xhr = new XMLHttpRequest()
xhr.open("GET", "http://127.0.0.1:8780/v1/test")
xhr.send()
new EventSource("http://127.0.0.1:8780/v1/events?x=1")
new WebSocket("ws://127.0.0.1:8780/v1/ws")
window.open("https://example.com/preview")
window.open("javascript:alert(1)")

assert.deepEqual(calls.restoreArgs, [["http://127.0.0.1:8780", bearerToken]])
assert.equal(calls.fetch[0].authorization, `Bearer ${bearerToken}`)
assert.equal(calls.fetch[1].authorization, null, "resource fetch carried dual auth")
assert.equal(new URL(resourceUrl).hash, "", "signed URL retained a fragment")
assert.equal(
  new URL(calls.fetch[1].url).searchParams.get("__vantaloom_loopback_exp"),
  capabilityExpiration
)
const resourceSignature = new URL(calls.fetch[1].url).searchParams.get(
  "__vantaloom_loopback_token"
)
assert.notEqual(
  resourceSignature,
  bearerToken
)
assert.notEqual(resourceSignature, capabilityKey)
assert.equal(resourceSignature, calls.authorize[0].signature)
assert.equal(calls.fetch[2].authorization, null, "remote fetch leaked auth")
assert.equal(calls.xhr[0].authorization, `Bearer ${bearerToken}`)
for (const url of [...calls.eventSource, ...calls.webSocket]) {
  const parsed = new URL(url)
  const expires = parsed.searchParams.get("__vantaloom_loopback_exp")
  const signature = parsed.searchParams.get("__vantaloom_loopback_token")
  assert.equal(expires, capabilityExpiration)
  assert.match(signature, /^[0-9a-f]{64}$/)
  assert.notEqual(signature, bearerToken)
  assert.notEqual(signature, capabilityKey)
}
assert.ok(
  !/capability(?:Token|Key)/.test(shim),
  "capability key contract leaked into JavaScript state"
)
const helperDescriptor = Object.getOwnPropertyDescriptor(
  window,
  "__loomAuthorizeLocalRuntimeURL"
)
assert.equal(helperDescriptor?.configurable, false)
assert.equal(helperDescriptor?.writable, false)
assert.deepEqual(calls.open, ["https://example.com/preview"])

console.log("bridge shim harness passed")
