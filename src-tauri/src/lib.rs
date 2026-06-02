// Vantaloom mobile control node — Tauri shell.
//
// Runs the EasyTier mesh IN-PROCESS (no spawned binary, no root) and exposes a
// small command surface to the web UI. On Android the TUN device is provided by
// the system VpnService via `tauri-plugin-vpnservice`; the resulting fd is fed
// to the in-process core through `mesh_set_tun_fd`. The phone joins the
// workgroup mesh as a CONTROL-ONLY node: it runs no api/agent, it only dials
// peers and drives their runtimes over the tunnel (the web UI's "VIP mode").
//
// The EasyTier integration mirrors references/easytier/easytier-gui, which is
// proven on Android. Lines marked `VERIFY` depend on the pinned easytier
// version's exact API and should be reconciled on the first real
// `tauri android build` (this scaffold is authored without the Android
// toolchain installed, so it has not yet been compiled).

use once_cell::sync::Lazy;
use std::sync::Arc;
use tauri::Manager;
use tokio::sync::RwLock;

use easytier::common::config::{ConfigFileControl, TomlConfigLoader};
use easytier::instance_manager::NetworkInstanceManager;
use easytier::launcher::NetworkInstanceRunningInfo;

// A single in-process mesh manager for the whole app. A control node only ever
// runs one network instance, but the manager keeps the lifecycle tidy.
static MANAGER: Lazy<RwLock<Option<Arc<NetworkInstanceManager>>>> =
    Lazy::new(|| RwLock::new(None));

async fn manager() -> Arc<NetworkInstanceManager> {
    {
        let g = MANAGER.read().await;
        if let Some(m) = g.as_ref() {
            return m.clone();
        }
    }
    let mut g = MANAGER.write().await;
    if let Some(m) = g.as_ref() {
        return m.clone();
    }
    let m = Arc::new(NetworkInstanceManager::new());
    *g = Some(m.clone());
    m
}

/// Mesh credentials handed out by the Hub (`GET /api/network/config`): the
/// workgroup's EasyTier network name + derived secret + relay/peer URIs.
#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct MeshCreds {
    network_name: String,
    network_secret: String,
    #[serde(default)]
    peers: Vec<String>,
    #[serde(default)]
    hostname: Option<String>,
    #[serde(default)]
    instance_name: Option<String>,
}

/// Render a control-only EasyTier config (canonical TomlConfigLoader schema, see
/// the EasyTier magisk config.toml). No listeners (the phone dials peers, it
/// does not accept inbound), DHCP virtual IP, and a TUN (fd supplied later by
/// VpnService on Android).
fn build_toml(creds: &MeshCreds) -> String {
    let hostname = creds
        .hostname
        .clone()
        .unwrap_or_else(|| "vantaloom-phone".to_string());
    let inst = creds
        .instance_name
        .clone()
        .unwrap_or_else(|| "vantaloom".to_string());

    let mut peers_block = String::new();
    for p in &creds.peers {
        let p = p.trim();
        if !p.is_empty() {
            peers_block.push_str("[[peer]]\nuri = \"");
            peers_block.push_str(p);
            peers_block.push_str("\"\n\n");
        }
    }

    format!(
        "instance_name = \"{inst}\"\n\
         hostname = \"{hostname}\"\n\
         dhcp = true\n\
         listeners = []\n\
         mapped_listeners = []\n\
         exit_nodes = []\n\
         \n\
         [network_identity]\n\
         network_name = \"{network_name}\"\n\
         network_secret = \"{network_secret}\"\n\
         \n\
         {peers_block}\
         [flags]\n\
         no_tun = false\n\
         enable_encryption = true\n\
         enable_ipv6 = true\n\
         mtu = 1300\n\
         latency_first = true\n\
         disable_p2p = false\n",
        inst = inst,
        hostname = hostname,
        network_name = creds.network_name,
        network_secret = creds.network_secret,
        peers_block = peers_block,
    )
}

/// Join the workgroup mesh. Returns the new instance id (a UUID string) that the
/// frontend uses for the VpnService handshake and status polling.
#[tauri::command]
async fn mesh_join(creds: MeshCreds) -> Result<String, String> {
    let toml = build_toml(&creds);
    let cfg = TomlConfigLoader::new_from_str(&toml).map_err(|e| e.to_string())?;
    let m = manager().await;
    // watch_event = false: keep the instance alive even before it has a TUN
    // (the fd arrives later from VpnService). VERIFY the ConfigFileControl
    // variant name against the pinned easytier version.
    let id = m
        .run_network_instance(cfg, false, ConfigFileControl::STATIC_CONFIG)
        .map_err(|e| e.to_string())?;
    Ok(id.to_string())
}

/// Live node info (virtual IP, routes, peers, running flag). Returned in the
/// same JSON shape the EasyTier frontend lib expects, so the VpnService
/// orchestration in the web client can read `my_node_info.virtual_ipv4` and
/// `routes` directly.
#[tauri::command]
async fn mesh_collect_info(
    instance_id: String,
) -> Result<Option<NetworkInstanceRunningInfo>, String> {
    let id = instance_id
        .parse()
        .map_err(|e: uuid::Error| e.to_string())?;
    Ok(manager().await.get_network_info(&id).await)
}

/// Hand the Android VpnService TUN fd to the in-process core. Called by the
/// frontend after `start_vpn` succeeds and the `vpn_service_start` event
/// delivers the fd.
#[tauri::command]
async fn mesh_set_tun_fd(instance_id: String, fd: i32) -> Result<(), String> {
    let id = instance_id
        .parse()
        .map_err(|e: uuid::Error| e.to_string())?;
    manager()
        .await
        .set_tun_fd(&id, fd)
        .map_err(|e| e.to_string())
}

/// Leave the mesh and drop the instance.
#[tauri::command]
async fn mesh_leave(instance_id: String) -> Result<(), String> {
    let id = instance_id
        .parse()
        .map_err(|e: uuid::Error| e.to_string())?;
    manager()
        .await
        .delete_network_instance(vec![id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// Whether a mesh instance is currently live IN THIS PROCESS. False after a cold
/// start / process kill (the static manager is empty), true after a mere WebView
/// reload (the native instance survives). The frontend uses this to drop a
/// stale, persisted runtime target and return to the connect screen instead of
/// driving a dead tunnel. Reads the static directly so it never spins up a
/// manager as a side effect.
#[tauri::command]
async fn mesh_active() -> bool {
    let g = MANAGER.read().await;
    match g.as_ref() {
        Some(m) => !m.list_network_instance_ids().is_empty(),
        None => false,
    }
}

#[tauri::command]
fn easytier_version() -> String {
    easytier::VERSION.to_string()
}

/// The Android SSAID (`Settings.Secure.ANDROID_ID`). Unlike a file in app data,
/// it SURVIVES uninstall/reinstall as long as the signing key is unchanged — and
/// our APKs use a fixed committed keystore, so it is stable for the life of the
/// device. Returns None on non-Android, when the value is missing, or the known
/// buggy constant some old devices report.
///
/// The whole JNI attempt is wrapped in catch_unwind: if the ndk context isn't
/// initialised, `android_context()` panics — we swallow it and fall back rather
/// than crash the command.
#[cfg(target_os = "android")]
fn android_ssaid() -> Option<String> {
    std::panic::catch_unwind(android_ssaid_inner)
        .ok()
        .flatten()
}

#[cfg(target_os = "android")]
fn android_ssaid_inner() -> Option<String> {
    use jni::objects::{JObject, JString, JValue};

    let ctx = ndk_context::android_context();
    if ctx.vm().is_null() || ctx.context().is_null() {
        return None;
    }
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }.ok()?;
    let mut env = vm.attach_current_thread().ok()?;
    let context = unsafe { JObject::from_raw(ctx.context().cast()) };

    // resolver = context.getContentResolver()
    let resolver = env
        .call_method(
            &context,
            "getContentResolver",
            "()Landroid/content/ContentResolver;",
            &[],
        )
        .ok()?
        .l()
        .ok()?;

    // Settings.Secure.getString(resolver, "android_id").
    // Pass args as explicit JValue::Object; &JString coerces to &JObject via
    // Deref, which avoids relying on a From<&JString> impl.
    let key = env.new_string("android_id").ok()?;
    let key_obj: &JObject = &key;
    let class = env.find_class("android/provider/Settings$Secure").ok()?;
    let value = env
        .call_static_method(
            class,
            "getString",
            "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&resolver), JValue::Object(key_obj)],
        )
        .ok()?
        .l()
        .ok()?;

    if value.is_null() {
        return None;
    }
    let jstr = unsafe { JString::from_raw(value.into_raw()) };
    let s = env.get_string(&jstr).ok()?;
    let s = s.to_str().ok()?.trim().to_string();
    // 9774d56d682e549c is a notorious non-unique value on some old/rooted devices.
    if s.is_empty() || s == "9774d56d682e549c" {
        None
    } else {
        Some(s)
    }
}

/// Stable per-device identifier the Hub uses to dedup registrations. Resolution
/// order: (1) a value cached in `<app_data_dir>/device-id` — stable within an
/// install; (2) on Android, the hardware SSAID, which survives reinstall thanks
/// to our fixed signing key (then cached so it never drifts); (3) a persisted
/// random UUID fallback (non-Android, or SSAID unavailable).
#[tauri::command]
async fn device_id(app: tauri::AppHandle) -> Result<String, String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join("device-id");

    if let Ok(existing) = std::fs::read_to_string(&path) {
        let trimmed = existing.trim();
        if !trimmed.is_empty() {
            return Ok(trimmed.to_string());
        }
    }

    #[cfg(target_os = "android")]
    {
        if let Some(ssaid) = android_ssaid() {
            let id = format!("android-{ssaid}");
            // Best-effort cache; even if the write fails we still return the
            // SSAID, which is itself stable, so the id stays consistent.
            let _ = std::fs::write(&path, &id);
            return Ok(id);
        }
    }

    let id = uuid::Uuid::new_v4().to_string();
    std::fs::write(&path, &id).map_err(|e| e.to_string())?;
    Ok(id)
}

/// Compile-/runtime diagnostics surfaced on-device by the connect flow. The
/// single most important bit is `cfg_mobile`: EasyTier's in-process TUN handoff
/// (`run_routine_for_mobile`) is gated on `#[cfg(mobile)]`, so if this is false
/// the core can NEVER consume the VpnService fd and the data plane is dead no
/// matter what the JS side does.
#[derive(serde::Serialize)]
struct MeshDiag {
    cfg_mobile: bool,
    cfg_android: bool,
    easytier_version: String,
}

#[tauri::command]
fn mesh_diag() -> MeshDiag {
    MeshDiag {
        cfg_mobile: cfg!(mobile),
        cfg_android: cfg!(target_os = "android"),
        easytier_version: easytier::VERSION.to_string(),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_os::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_vpnservice::init());

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        builder = builder.plugin(tauri_plugin_single_instance::init(|_app, _args, _cwd| {}));
    }

    builder
        .invoke_handler(tauri::generate_handler![
            mesh_join,
            mesh_collect_info,
            mesh_set_tun_fd,
            mesh_leave,
            mesh_active,
            mesh_diag,
            easytier_version,
            device_id,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the Vantaloom control node");
}
