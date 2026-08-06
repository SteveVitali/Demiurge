mod commands;
mod sidecar;
mod tray;

use std::sync::Arc;
use tauri::{Emitter, Listener, Manager};
use sidecar::SidecarManager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let sidecar_manager = Arc::new(SidecarManager::new());

    tauri::Builder::default()
        // Single-instance must be registered first (before deep-link)
        .plugin(tauri_plugin_single_instance::init(|_app, _args, _cwd| {}))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_store::Builder::default().build())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_process::init())
        .manage(sidecar_manager)
        .invoke_handler(tauri::generate_handler![
            commands::start_backend,
            commands::stop_backend,
            commands::restart_backend,
            commands::get_backend_status,
            commands::open_folder_dialog,
            commands::create_log_window,
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            tray::setup_tray(&handle)?;

            // Listen for deep link events (demiurge://auth-callback?...) and emit to frontend
            let deep_link_handle = handle.clone();
            app.listen("deep-link://new-url", move |event: tauri::Event| {
                if let Some(window) = deep_link_handle.get_webview_window("main") {
                    let _ = window.emit("deep-link-received", event.payload());
                }
            });

            // Give sidecar the AppHandle so it can spawn the JVM binary (§12.2)
            let sidecar = app.state::<Arc<SidecarManager>>().inner().clone();
            sidecar.set_app_handle(handle.clone());

            // Try to connect to (or spawn) backend on startup
            tauri::async_runtime::spawn(async move {
                let _ = sidecar.start().await;
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
