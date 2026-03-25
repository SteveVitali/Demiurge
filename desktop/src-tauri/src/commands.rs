use std::sync::Arc;
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindowBuilder};
use crate::sidecar::{SidecarManager, SidecarStatus};

#[tauri::command]
pub async fn start_backend(sidecar: State<'_, Arc<SidecarManager>>) -> Result<(), String> {
    sidecar.start().await
}

#[tauri::command]
pub async fn stop_backend(sidecar: State<'_, Arc<SidecarManager>>) -> Result<(), String> {
    sidecar.stop()
}

#[tauri::command]
pub async fn restart_backend(sidecar: State<'_, Arc<SidecarManager>>) -> Result<(), String> {
    sidecar.restart().await
}

#[tauri::command]
pub async fn get_backend_status(sidecar: State<'_, Arc<SidecarManager>>) -> Result<SidecarStatus, String> {
    Ok(sidecar.get_status())
}

#[tauri::command]
pub async fn open_folder_dialog(app: AppHandle) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    use tokio::sync::oneshot;

    let (tx, rx) = oneshot::channel();
    app.dialog().file().pick_folder(move |folder| {
        let _ = tx.send(folder.map(|p| p.to_string()));
    });
    rx.await.map_err(|_| "Dialog cancelled".to_string())?
        .map_or(Ok(None), |path| Ok(Some(path)))
}

// Desktop Phase 5 — §12.5: Create a detached log window for a specific service.
// Opens a new native window pointing to /logs/:runId/:serviceId route.
#[tauri::command]
pub async fn create_log_window(
    app: AppHandle,
    run_id: String,
    service_id: String,
) -> Result<(), String> {
    let label = format!("logs-{}-{}", &run_id[..8.min(run_id.len())], &service_id);
    let title = format!("Logs: {}", service_id);
    let url = format!("/logs/{}/{}", run_id, service_id);

    // Check if window already exists — focus it instead of creating a new one
    if let Some(existing) = app.get_webview_window(&label) {
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(());
    }

    WebviewWindowBuilder::new(&app, &label, WebviewUrl::App(url.into()))
        .title(&title)
        .inner_size(800.0, 600.0)
        .min_inner_size(400.0, 300.0)
        .resizable(true)
        .decorations(true)
        .center()
        .build()
        .map_err(|e| format!("Failed to create log window: {}", e))?;

    Ok(())
}
