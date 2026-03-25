use serde::Serialize;
use std::sync::Mutex;
use std::time::Duration;
use tauri::AppHandle;
use tauri_plugin_shell::ShellExt;

const HEALTH_URL: &str = "http://127.0.0.1:19440/health";
const MAX_RESTART_ATTEMPTS: u32 = 3;
const HEALTH_POLL_INTERVAL_MS: u64 = 500;
const HEALTH_TIMEOUT_MS: u64 = 30000;
const CRASH_RESTART_DELAY_MS: u64 = 2000;

#[derive(Debug, Clone, Serialize)]
pub enum SidecarStatus {
    Stopped,
    Starting,
    Running,
    Error(String),
}

pub struct SidecarManager {
    status: Mutex<SidecarStatus>,
    restart_count: Mutex<u32>,
    app_handle: Mutex<Option<AppHandle>>,
}

impl SidecarManager {
    pub fn new() -> Self {
        Self {
            status: Mutex::new(SidecarStatus::Stopped),
            restart_count: Mutex::new(0),
            app_handle: Mutex::new(None),
        }
    }

    pub fn set_app_handle(&self, handle: AppHandle) {
        let mut h = self.app_handle.lock().unwrap();
        *h = Some(handle);
    }

    pub fn get_status(&self) -> SidecarStatus {
        self.status.lock().unwrap().clone()
    }

    pub async fn start(&self) -> Result<(), String> {
        {
            let mut status = self.status.lock().unwrap();
            *status = SidecarStatus::Starting;
        }

        // First check if a backend is already running (e.g. started via CLI)
        if self.wait_for_ready_quick().await {
            let mut status = self.status.lock().unwrap();
            *status = SidecarStatus::Running;
            let mut count = self.restart_count.lock().unwrap();
            *count = 0;
            return Ok(());
        }

        // Try to spawn the sidecar binary via Tauri shell plugin (§12.2)
        let spawned = self.try_spawn_sidecar().await;
        if !spawned {
            log::info!("Sidecar binary not available, waiting for external backend...");
        }

        // Wait for backend to become ready
        match self.wait_for_ready().await {
            true => {
                let mut status = self.status.lock().unwrap();
                *status = SidecarStatus::Running;
                let mut count = self.restart_count.lock().unwrap();
                *count = 0;
                Ok(())
            }
            false => {
                let mut status = self.status.lock().unwrap();
                *status = SidecarStatus::Error(
                    "Backend not reachable. Start it with `demiurge serve` or `demiurge run`.".into(),
                );
                Err("Backend not reachable".into())
            }
        }
    }

    async fn try_spawn_sidecar(&self) -> bool {
        let handle = {
            let h = self.app_handle.lock().unwrap();
            match h.as_ref() {
                Some(h) => h.clone(),
                None => return false,
            }
        };

        match handle.shell().sidecar("demiurge-sidecar") {
            Ok(cmd) => {
                let cmd = cmd.args(["serve", "--port", "19440", "--ws-port", "19441"]);
                match cmd.spawn() {
                    Ok((mut rx, _child)) => {
                        // Monitor sidecar output in background
                        tauri::async_runtime::spawn(async move {
                            use tauri_plugin_shell::process::CommandEvent;
                            while let Some(event) = rx.recv().await {
                                match event {
                                    CommandEvent::Stdout(line) => {
                                        log::info!("[sidecar] {}", String::from_utf8_lossy(&line));
                                    }
                                    CommandEvent::Stderr(line) => {
                                        log::warn!("[sidecar] {}", String::from_utf8_lossy(&line));
                                    }
                                    CommandEvent::Terminated(payload) => {
                                        log::error!("[sidecar] Process terminated: {:?}", payload);
                                        break;
                                    }
                                    _ => {}
                                }
                            }
                        });
                        true
                    }
                    Err(e) => {
                        log::warn!("Failed to spawn sidecar: {}", e);
                        false
                    }
                }
            }
            Err(e) => {
                log::warn!("Sidecar binary not found: {}", e);
                false
            }
        }
    }

    pub fn stop(&self) -> Result<(), String> {
        let mut status = self.status.lock().unwrap();
        *status = SidecarStatus::Stopped;
        Ok(())
    }

    pub async fn restart(&self) -> Result<(), String> {
        self.stop()?;
        tokio::time::sleep(Duration::from_millis(CRASH_RESTART_DELAY_MS)).await;
        self.start().await
    }

    pub async fn try_crash_recovery(&self) -> Result<(), String> {
        let count = {
            let mut c = self.restart_count.lock().unwrap();
            *c += 1;
            *c
        };

        if count > MAX_RESTART_ATTEMPTS {
            let mut status = self.status.lock().unwrap();
            *status = SidecarStatus::Error(format!(
                "Backend crashed {} times. Manual restart required.",
                MAX_RESTART_ATTEMPTS
            ));
            return Err("Max restart attempts exceeded".into());
        }

        log::warn!("Backend crashed, attempting restart {}/{}", count, MAX_RESTART_ATTEMPTS);
        tokio::time::sleep(Duration::from_millis(CRASH_RESTART_DELAY_MS)).await;
        self.start().await
    }

    async fn wait_for_ready_quick(&self) -> bool {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(1))
            .build()
            .unwrap_or_default();

        match client.get(HEALTH_URL).send().await {
            Ok(resp) if resp.status().is_success() => true,
            _ => false,
        }
    }

    async fn wait_for_ready(&self) -> bool {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap_or_default();

        let max_attempts = HEALTH_TIMEOUT_MS / HEALTH_POLL_INTERVAL_MS;
        for _ in 0..max_attempts {
            match client.get(HEALTH_URL).send().await {
                Ok(resp) if resp.status().is_success() => return true,
                _ => {}
            }
            tokio::time::sleep(Duration::from_millis(HEALTH_POLL_INTERVAL_MS)).await;
        }
        false
    }
}
