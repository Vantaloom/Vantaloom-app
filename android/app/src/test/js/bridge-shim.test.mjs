import assert from "node:assert/strict"
import { createHmac } from "node:crypto"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const bearerToken = "a".repeat(64)
const capabilityKey = "b".repeat(64)
const capabilityExpiration = "2000043200"
// The per-launch secret gating the privileged native methods: the origin-scoped
// shim (this copy) presents it on every gated call. Mirror bridgeShim()'s
// placeholder substitution so the extracted shim carries a concrete secret.
const bridgeSecret = "f".repeat(64)
const sourcePath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../main/java/online/timefiles/vantaloom/MainActivity.kt"
)
const source = fs.readFileSync(sourcePath, "utf8")
const match = source.match(
  /private val BRIDGE_SHIM_TEMPLATE = """\r?\n(?<js>[\s\S]*?)\r?\n"""\.trimIndent\(\)/
)
assert.ok(match?.groups?.js, "BRIDGE_SHIM_TEMPLATE not found")
const shim = match.groups.js.replaceAll(
  "${LoopbackAuth.queryParameter}",
  "__vantaloom_loopback_token"
).replaceAll(
  "${LoopbackAuth.expirationQueryParameter}",
  "__vantaloom_loopback_exp"
).replaceAll("${BRIDGE_SECRET_PLACEHOLDER}", bridgeSecret)

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
  // Gated methods take the launch secret as the leading argument.
  setToken(secret) {
    calls.setTokenSecret = secret
  },
  setChrome() {},
  startNode() {},
  connect() {},
  disconnect() {},
  stopNode() {},
  pickImages() {},
  pickFiles() {},
  pickFolder() {},
  storageAccessGranted: () => true,
  requestStorageAccess(secret, callbackId) {
    calls.storageAccessArgs = [...arguments]
    void secret
    void callbackId
  },
  shareFile() {},
  startLocalRuntime() {},
  stopLocalRuntime() {},
  authorizeLocalRuntimeUrl(secret, raw) {
    calls.authorizeSecret = secret
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
  appVersionName: () => "0.0.0-test",
  appUpdateStatus: () => '{"phase":"idle"}',
  checkAppUpdate(callbackId) {
    calls.checkUpdateArgs = [...arguments]
    calls.checkUpdateCallbackId = callbackId
  },
  installAppUpdate(secret, callbackId) {
    calls.installUpdateArgs = [...arguments]
    calls.installUpdateSecret = secret
    calls.installUpdateCallbackId = callbackId
  },
  notificationTarget: () => '{"conversationId":"conv-x"}',
  phoneControlStatus: () => '{"available":true,"serviceState":"disabled"}',
  openPhoneControlSetup() {
    calls.phoneSetupArgs = [...arguments]
  },
  stopPhoneControl() {
    calls.phoneStopArgs = [...arguments]
  },
  restoreLocalRuntimeAuth(secret) {
    calls.restore += 1
    calls.restoreSecret = secret
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

// The shim must present the launch secret on gated native calls; a preview
// iframe (which never receives this shim) cannot, so its calls are refused.
assert.equal(calls.restoreSecret, bridgeSecret, "restore did not carry the bridge secret")
assert.equal(calls.authorizeSecret, bridgeSecret, "authorize did not carry the bridge secret")
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
// 应用更新四件套的适配形状。checkAppUpdate 不特权（只发一个 callId），
// installAppUpdate 特权（secret 在前、callId 在后）——把 secret 漏掉或顺序写反，
// 表现都是「点了更新没反应」，而这是用户唯一的升级入口。
assert.equal(typeof window.__loomBridge.appVersionName, "function")
assert.equal(window.__loomBridge.appVersionName(), "0.0.0-test")
assert.equal(window.__loomBridge.appUpdateStatus(), '{"phase":"idle"}')
window.__loomBridge.checkAppUpdate("loom-check-1")
assert.deepEqual(calls.checkUpdateArgs, ["loom-check-1"])
window.__loomBridge.installAppUpdate("loom-install-1")
assert.deepEqual(calls.installUpdateArgs, [bridgeSecret, "loom-install-1"])
assert.equal(calls.installUpdateSecret, bridgeSecret)

// 手机文件夹授权那一跳：requestStorageAccess 是特权方法（secret 在前）。漏掉
// secret 的表现是「点同意 → 系统设置页没弹出来 → 用户以为坏了」。
assert.equal(window.__loomBridge.storageAccessGranted(), true)
window.__loomBridge.requestStorageAccess("loom-storage-1")
assert.deepEqual(calls.storageAccessArgs, [bridgeSecret, "loom-storage-1"])

// 手机操控三件套：status 只读不带 secret；打开无障碍设置与停止都是特权方法
// （secret 在前、callId 在后）——顺序写反的表现是「点了开关没反应」。
assert.equal(
  window.__loomBridge.phoneControlStatus(),
  '{"available":true,"serviceState":"disabled"}'
)
window.__loomBridge.openPhoneControlSetup("loom-phone-setup-1")
assert.deepEqual(calls.phoneSetupArgs, [bridgeSecret, "loom-phone-setup-1"])
window.__loomBridge.stopPhoneControl("loom-phone-stop-1")
assert.deepEqual(calls.phoneStopArgs, [bridgeSecret, "loom-phone-stop-1"])

const helperDescriptor = Object.getOwnPropertyDescriptor(
  window,
  "__loomAuthorizeLocalRuntimeURL"
)
assert.equal(helperDescriptor?.configurable, false)
assert.equal(helperDescriptor?.writable, false)
assert.deepEqual(calls.open, ["https://example.com/preview"])
// Notification tap target: ungated passthrough (a conversation id the runtime
// itself just emitted — no capability, no secret).
assert.equal(
  window.__loomBridge.notificationTarget(),
  '{"conversationId":"conv-x"}'
)

console.log("bridge shim harness passed")
