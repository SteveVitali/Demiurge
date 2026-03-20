use std::sync::Arc;
use tauri::State;
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
pub async fn open_folder_dialog() -> Result<Option<String>, String> {
    // Phase 1: placeholder — actual native dialog will be wired via tauri-plugin-dialog
    Ok(None)
}
