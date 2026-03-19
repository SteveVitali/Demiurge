use serde::Serialize;
use std::sync::Mutex;
use std::time::Duration;
use tauri::AppHandle;
use tauri_plugin_shell::ShellExt;

const HEALTH_URL: &str = "http://127.0.0.1:19440/health";
const MAX_RESTART_ATTEMPTS: u32 = 3;
const HEALTH_POLL_INTERVAL_MS: u64 = 500;
const HEALTH_TIMEOUT_SECS: u64 = 300;
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
    dev_process_spawned: Mutex<bool>,
}

impl SidecarManager {
    pub fn new() -> Self {
        Self {
            status: Mutex::new(SidecarStatus::Stopped),
            restart_count: Mutex::new(0),
            app_handle: Mutex::new(None),
            dev_process_spawned: Mutex::new(false),
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
        // Guard: if already Starting or Running, don't spawn again
        {
            let status = self.status.lock().unwrap();
            match *status {
                SidecarStatus::Starting => return Ok(()),
                SidecarStatus::Running => return Ok(()),
                _ => {}
            }
        }
        {
            let mut status = self.status.lock().unwrap();
            *status = SidecarStatus::Starting;
        }

        eprintln!("[demiurge-desktop] Starting backend...");

        // First check if a backend is already running (e.g. started via CLI)
        if self.check_health_once().await {
            eprintln!("[demiurge-desktop] Backend already running.");
            let mut status = self.status.lock().unwrap();
            *status = SidecarStatus::Running;
            let mut count = self.restart_count.lock().unwrap();
            *count = 0;
            return Ok(());
        }

        // Only attempt to spawn if we haven't already launched a dev process
        let already_spawned = *self.dev_process_spawned.lock().unwrap();
        if !already_spawned {
            let spawned = self.try_spawn_backend().await;
            if spawned {
                *self.dev_process_spawned.lock().unwrap() = true;
            } else {
                eprintln!("[demiurge-desktop] Could not spawn backend process. Start manually with: bazel run //modules/cli:demiurge -- serve");
            }
        } else {
            eprintln!("[demiurge-desktop] Backend process already spawned, polling health...");
        }

        // Wait for backend to become ready
        match self.wait_for_ready().await {
            true => {
                eprintln!("[demiurge-desktop] Backend is ready.");
                let mut status = self.status.lock().unwrap();
                *status = SidecarStatus::Running;
                let mut count = self.restart_count.lock().unwrap();
                *count = 0;
                Ok(())
            }
            false => {
                let mut status = self.status.lock().unwrap();
                *status = SidecarStatus::Error(
                    "Backend not reachable. Start it with: bazel run //modules/cli:demiurge -- serve".into(),
                );
                Err("Backend not reachable".into())
            }
        }
    }

    /// Try all available spawn methods in order of speed.
    async fn try_spawn_backend(&self) -> bool {
        let repo_root = self.find_repo_root();

        // In dev mode (repo root found), skip the bundled sidecar placeholder
        // and go straight to the real backend spawn methods.
        if repo_root.is_none() {
            if self.try_spawn_sidecar().await {
                eprintln!("[demiurge-desktop] Spawned bundled sidecar binary.");
                return true;
            }
        } else {
            eprintln!("[demiurge-desktop] Dev mode detected (repo root found), skipping sidecar placeholder.");
        }

        // 2. Bazel wrapper script (instant, uses already-built output)
        if let Some(ref root) = repo_root {
            let wrapper = root.join("bazel-bin/modules/cli/demiurge");
            if wrapper.exists() {
                eprintln!("[demiurge-desktop] Found bazel wrapper at {:?}, spawning...", wrapper);
                if self.spawn_child_logged(
                    std::process::Command::new(wrapper.as_os_str())
                        .args(["serve", "--port", "19440", "--ws-port", "19441"])
                        .current_dir(root)
                        .stdout(std::process::Stdio::piped())
                        .stderr(std::process::Stdio::piped()),
                    "backend",
                ) {
                    return true;
                }
                eprintln!("[demiurge-desktop] Bazel wrapper script failed to spawn.");
            }
        }

        // 3. Deploy JAR via java -jar
        if let Some(ref root) = repo_root {
            let jar = root.join("bazel-bin/modules/cli/demiurge_deploy.jar");
            if jar.exists() {
                eprintln!("[demiurge-desktop] Found deploy JAR at {:?}, spawning via java -jar...", jar);
                if self.spawn_child_logged(
                    std::process::Command::new("java")
                        .args([
                            "-Xmx512m",
                            "-jar",
                            jar.to_str().unwrap_or_default(),
                            "serve",
                            "--port", "19440",
                            "--ws-port", "19441",
                        ])
                        .current_dir(root)
                        .stdout(std::process::Stdio::piped())
                        .stderr(std::process::Stdio::piped()),
                    "backend",
                ) {
                    return true;
                }
                eprintln!("[demiurge-desktop] Deploy JAR failed to spawn.");
            }
        }

        // 4. bazel run (slow — triggers full build)
        if let Some(ref root) = repo_root {
            eprintln!("[demiurge-desktop] Trying bazel run (this may take a while to build)...");
            if self.spawn_child_logged(
                std::process::Command::new("bazel")
                    .args(["run", "//modules/cli:demiurge", "--", "serve", "--port", "19440", "--ws-port", "19441"])
                    .current_dir(root)
                    .stdout(std::process::Stdio::piped())
                    .stderr(std::process::Stdio::piped()),
                "backend",
            ) {
                return true;
            }
            eprintln!("[demiurge-desktop] bazel run failed to spawn.");
        }

        if repo_root.is_none() {
            eprintln!("[demiurge-desktop] Could not find repo root (MODULE.bazel). CWD = {:?}", std::env::current_dir());
        }

        false
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
                        tauri::async_runtime::spawn(async move {
                            use tauri_plugin_shell::process::CommandEvent;
                            while let Some(event) = rx.recv().await {
                                match event {
                                    CommandEvent::Stdout(line) => {
                                        eprintln!("[backend] {}", String::from_utf8_lossy(&line));
                                    }
                                    CommandEvent::Stderr(line) => {
                                        eprintln!("[backend] {}", String::from_utf8_lossy(&line));
                                    }
                                    CommandEvent::Terminated(payload) => {
                                        eprintln!("[backend] Sidecar terminated: {:?}", payload);
                                        break;
                                    }
                                    _ => {}
                                }
                            }
                        });
                        true
                    }
                    Err(e) => {
                        eprintln!("[demiurge-desktop] Sidecar spawn error: {}", e);
                        false
                    }
                }
            }
            Err(_) => false,
        }
    }

    /// Walk up from CWD to find the repo root (directory containing MODULE.bazel).
    fn find_repo_root(&self) -> Option<std::path::PathBuf> {
        let start = std::env::current_dir().unwrap_or_default();
        start.ancestors()
            .find(|p| p.join("MODULE.bazel").exists())
            .map(|p| p.to_path_buf())
    }

    /// Spawn a child process and pipe its stdout/stderr to eprintln in background threads.
    /// Returns true if the process was spawned successfully.
    fn spawn_child_logged(&self, cmd: &mut std::process::Command, tag: &str) -> bool {
        match cmd.spawn() {
            Ok(mut child) => {
                let tag_err = tag.to_string();
                let tag_out = tag.to_string();
                if let Some(stderr) = child.stderr.take() {
                    std::thread::spawn(move || {
                        use std::io::{BufRead, BufReader};
                        let reader = BufReader::new(stderr);
                        for line in reader.lines().map_while(Result::ok) {
                            eprintln!("[{}] {}", tag_err, line);
                        }
                    });
                }
                if let Some(stdout) = child.stdout.take() {
                    std::thread::spawn(move || {
                        use std::io::{BufRead, BufReader};
                        let reader = BufReader::new(stdout);
                        for line in reader.lines().map_while(Result::ok) {
                            eprintln!("[{}] {}", tag_out, line);
                        }
                    });
                }
                true
            }
            Err(e) => {
                eprintln!("[demiurge-desktop] Failed to spawn {}: {}", tag, e);
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
        *self.dev_process_spawned.lock().unwrap() = false;
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

        eprintln!("[demiurge-desktop] Backend crashed, attempting restart {}/{}", count, MAX_RESTART_ATTEMPTS);
        tokio::time::sleep(Duration::from_millis(CRASH_RESTART_DELAY_MS)).await;
        *self.dev_process_spawned.lock().unwrap() = false;
        self.start().await
    }

    pub fn mark_running(&self) {
        let mut status = self.status.lock().unwrap();
        *status = SidecarStatus::Running;
        let mut count = self.restart_count.lock().unwrap();
        *count = 0;
    }

    pub async fn check_health_once(&self) -> bool {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap_or_default();

        matches!(client.get(HEALTH_URL).send().await, Ok(resp) if resp.status().is_success())
    }

    async fn wait_for_ready(&self) -> bool {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap_or_default();

        let max_attempts = (HEALTH_TIMEOUT_SECS * 1000) / HEALTH_POLL_INTERVAL_MS;
        for i in 0..max_attempts {
            match client.get(HEALTH_URL).send().await {
                Ok(resp) if resp.status().is_success() => return true,
                _ => {}
            }
            if i > 0 && i % 20 == 0 {
                eprintln!("[demiurge-desktop] Still waiting for backend... ({}s)", (i * HEALTH_POLL_INTERVAL_MS) / 1000);
            }
            tokio::time::sleep(Duration::from_millis(HEALTH_POLL_INTERVAL_MS)).await;
        }
        false
    }
}
