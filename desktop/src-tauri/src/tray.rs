use serde::Deserialize;
use std::sync::Mutex;
use tauri::{
    AppHandle, Emitter, Listener, Manager,
    menu::{Menu, MenuItem, PredefinedMenuItem, Submenu},
    tray::TrayIconBuilder,
};

// Desktop Phase 5 — §12.4: Dynamic system tray with state-aware icon color,
// active run info, recent runs submenu, and quick actions.

#[derive(Debug, Clone, Deserialize)]
pub struct TrayRunState {
    pub status: String,       // "idle" | "running" | "succeeded" | "failed"
    pub task: Option<String>,
    pub elapsed: Option<String>,
    pub run_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TrayRecentRun {
    pub run_id: String,
    pub task: String,
    pub verdict: String, // "Pass" | "Exhausted" | "Cancelled" | etc.
}

#[derive(Debug, Clone, Deserialize)]
pub struct TrayRecentRunsPayload {
    pub runs: Vec<TrayRecentRun>,
}

pub struct TrayState {
    pub run_state: Mutex<TrayRunState>,
    pub recent_runs: Mutex<Vec<TrayRecentRun>>,
}

impl Default for TrayState {
    fn default() -> Self {
        Self {
            run_state: Mutex::new(TrayRunState {
                status: "idle".into(),
                task: None,
                elapsed: None,
                run_id: None,
            }),
            recent_runs: Mutex::new(Vec::new()),
        }
    }
}

pub fn setup_tray(app: &AppHandle) -> Result<(), Box<dyn std::error::Error>> {
    let tray_state = TrayState::default();
    app.manage(tray_state);

    // Build initial menu
    let menu = build_tray_menu(app, &TrayRunState {
        status: "idle".into(),
        task: None,
        elapsed: None,
        run_id: None,
    }, &[])?;

    let tray = TrayIconBuilder::new()
        .menu(&menu)
        .tooltip("Demiurge — Idle")
        .on_menu_event(|app, event| {
            handle_menu_event(app, event.id.as_ref());
        })
        .build(app)?;

    // Listen for run state changes from the frontend
    let app_handle = app.clone();
    let tray_id = tray.id().clone();
    app.listen("tray:run-state-changed", move |event| {
        if let Ok(state) = serde_json::from_str::<TrayRunState>(event.payload()) {
            let tray_state_mgr = app_handle.state::<TrayState>();
            {
                let mut current = tray_state_mgr.run_state.lock().unwrap();
                *current = state.clone();
            }
            let recent = tray_state_mgr.recent_runs.lock().unwrap().clone();
            update_tray(&app_handle, &tray_id, &state, &recent);
        }
    });

    // Listen for recent runs updates from the frontend
    let app_handle2 = app.clone();
    let tray_id2 = tray.id().clone();
    app.listen("tray:recent-runs-updated", move |event| {
        if let Ok(payload) = serde_json::from_str::<TrayRecentRunsPayload>(event.payload()) {
            let tray_state_mgr = app_handle2.state::<TrayState>();
            {
                let mut recent = tray_state_mgr.recent_runs.lock().unwrap();
                *recent = payload.runs.clone();
            }
            let run_state = tray_state_mgr.run_state.lock().unwrap().clone();
            update_tray(&app_handle2, &tray_id2, &run_state, &payload.runs);
        }
    });

    Ok(())
}

fn build_tray_menu(
    app: &AppHandle,
    run_state: &TrayRunState,
    recent_runs: &[TrayRecentRun],
) -> Result<Menu<tauri::Wry>, Box<dyn std::error::Error>> {
    let show = MenuItem::with_id(app, "show", "Show Demiurge", true, None::<&str>)?;
    let sep1 = PredefinedMenuItem::separator(app)?;

    let mut items: Vec<Box<dyn tauri::menu::IsMenuItem<tauri::Wry>>> = vec![
        Box::new(show),
        Box::new(sep1),
    ];

    // Active run info (if running)
    if run_state.status == "running" {
        let task_label = run_state.task.as_deref().unwrap_or("Unknown task");
        let truncated = truncate_str(task_label, 40);
        let elapsed_str = run_state.elapsed.as_deref().unwrap_or("0s");
        let active_item = MenuItem::with_id(
            app,
            "active_run",
            format!("▶ {} ({})", truncated, elapsed_str),
            true,
            None::<&str>,
        )?;
        items.push(Box::new(active_item));

        let sep_active = PredefinedMenuItem::separator(app)?;
        items.push(Box::new(sep_active));
    }

    // New Run...
    let new_run = MenuItem::with_id(app, "new_run", "New Run...", true, None::<&str>)?;
    items.push(Box::new(new_run));

    // Recent Runs submenu
    if !recent_runs.is_empty() {
        let mut sub_items: Vec<MenuItem<tauri::Wry>> = Vec::new();
        for (i, run) in recent_runs.iter().take(5).enumerate() {
            let verdict_icon = match run.verdict.as_str() {
                "Pass" | "Succeeded" => "✓",
                "Exhausted" | "Failed" => "✗",
                "Cancelled" => "⊘",
                _ => "•",
            };
            let label = format!("{} {}", verdict_icon, truncate_str(&run.task, 35));
            let item = MenuItem::with_id(
                app,
                &format!("recent_{}", i),
                &label,
                true,
                None::<&str>,
            )?;
            sub_items.push(item);
        }
        let refs: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> =
            sub_items.iter().map(|i| i as &dyn tauri::menu::IsMenuItem<tauri::Wry>).collect();
        let recent_submenu = Submenu::with_items(app, "Recent Runs", true, &refs)?;
        items.push(Box::new(recent_submenu));
    }

    let sep2 = PredefinedMenuItem::separator(app)?;
    items.push(Box::new(sep2));

    let settings = MenuItem::with_id(app, "settings", "Settings...", true, None::<&str>)?;
    items.push(Box::new(settings));

    let quit = MenuItem::with_id(app, "quit", "Quit Demiurge", true, None::<&str>)?;
    items.push(Box::new(quit));

    let refs: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> = items
        .iter()
        .map(|b| b.as_ref())
        .collect();
    let menu = Menu::with_items(app, &refs)?;
    Ok(menu)
}

fn update_tray(
    app: &AppHandle,
    tray_id: &tauri::tray::TrayIconId,
    run_state: &TrayRunState,
    recent_runs: &[TrayRecentRun],
) {
    // Update tooltip
    let tooltip = match run_state.status.as_str() {
        "running" => {
            let task = run_state.task.as_deref().unwrap_or("Task");
            format!("Demiurge — Running: {}", truncate_str(task, 40))
        }
        "succeeded" => "Demiurge — Last run succeeded".into(),
        "failed" => "Demiurge — Last run failed".into(),
        _ => "Demiurge — Idle".into(),
    };

    if let Some(tray) = app.tray_by_id(tray_id) {
        let _ = tray.set_tooltip(Some(&tooltip));

        // §12.4: Dynamic tray icon color based on run state
        // TODO: implement once colored icon PNGs are added to icons/
        // Requires loading raw RGBA data — Tauri v2 Image only supports new(rgba, w, h)

        // Rebuild menu with current state
        if let Ok(menu) = build_tray_menu(app, run_state, recent_runs) {
            let _ = tray.set_menu(Some(menu));
        }
    }
}

fn show_and_focus(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn handle_menu_event(app: &AppHandle, id: &str) {
    match id {
        "show" => {
            show_and_focus(app);
        }
        "active_run" => {
            let tray_state = app.state::<TrayState>();
            let run_state = tray_state.run_state.lock().unwrap();
            if let Some(run_id) = &run_state.run_id {
                let _ = app.emit("tray:navigate-to-run", run_id.clone());
            }
            show_and_focus(app);
        }
        "new_run" => {
            let _ = app.emit("tray:open-new-run", ());
            show_and_focus(app);
        }
        "settings" => {
            let _ = app.emit("tray:open-settings", ());
            show_and_focus(app);
        }
        "quit" => {
            app.exit(0);
        }
        id if id.starts_with("recent_") => {
            if let Ok(idx) = id.strip_prefix("recent_").unwrap_or("").parse::<usize>() {
                let tray_state = app.state::<TrayState>();
                let recent = tray_state.recent_runs.lock().unwrap();
                if let Some(run) = recent.get(idx) {
                    let _ = app.emit("tray:navigate-to-run", run.run_id.clone());
                }
            }
            show_and_focus(app);
        }
        _ => {}
    }
}

fn truncate_str(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    let end = max.saturating_sub(3);
    let truncated: String = s.chars().take(end).collect();
    format!("{}...", truncated)
}
