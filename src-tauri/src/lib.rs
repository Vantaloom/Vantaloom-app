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

#[tauri::command]
fn easytier_version() -> String {
    easytier::VERSION.to_string()
}

/// Stable per-install device identifier, used by the Hub to dedup registrations
/// across app restarts. Persisted as a plain UUID in `<app_data_dir>/device-id`;
/// generated on first call, then read back verbatim on every subsequent launch.
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
    let id = uuid::Uuid::new_v4().to_string();
    std::fs::write(&path, &id).map_err(|e| e.to_string())?;
    Ok(id)
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
            easytier_version,
            device_id,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the Vantaloom control node");
}
