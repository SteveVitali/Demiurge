use serde::Serialize;
use std::sync::Mutex;
use std::time::Duration;

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
}

impl SidecarManager {
    pub fn new() -> Self {
        Self {
            status: Mutex::new(SidecarStatus::Stopped),
            restart_count: Mutex::new(0),
        }
    }

    pub fn get_status(&self) -> SidecarStatus {
        self.status.lock().unwrap().clone()
    }

    pub async fn start(&self) -> Result<(), String> {
        {
            let mut status = self.status.lock().unwrap();
            *status = SidecarStatus::Starting;
        }

        // In Phase 1, we don't bundle the sidecar binary.
        // The backend is expected to be started separately (via CLI)
        // or the app gracefully handles its absence.
        // Try to connect to an already-running backend.
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
