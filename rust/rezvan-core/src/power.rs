/// Power state machine for the mesh engine.
///
/// Seven states control how aggressively the radio scans, advertises, and
/// transmits.  Transitions are triggered by battery level, charging status, and

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PowerState {
    Emergency   = 0,
    Active      = 1,
    Balanced    = 2,
    PowerSaver  = 3,
    Minimal     = 4,
    Hibernation = 5,
    Dead        = 6,
}

/// Compute the recommended power state given current conditions.
///
/// * `battery` – battery percentage 0‑100
/// * `charging` – true if device is plugged in
/// * `density` – neighbour density (reserved for future use)
/// * `user_override` – if set, this state is returned immediately
pub fn compute_state(
    battery: u8,
    charging: bool,
    _density: f32,
    user_override: Option<PowerState>,
) -> PowerState {
    if let Some(state) = user_override {
        return state;
    }
    if charging {
        return PowerState::Active;
    }
    match battery {
        0..=5   => PowerState::Dead,
        6..=15  => PowerState::Hibernation,
        16..=30 => PowerState::Minimal,
        31..=50 => PowerState::PowerSaver,
        51..=80 => PowerState::Balanced,
        _       => PowerState::Active,
    }
}

/// Whether the engine should advertise at all in the given power state.
pub fn should_advertise(state: PowerState) -> bool {
    match state {
        PowerState::Emergency
        | PowerState::Active
        | PowerState::Balanced
        | PowerState::PowerSaver => true,
        PowerState::Minimal
        | PowerState::Hibernation
        | PowerState::Dead => false,
    }
}

/// Whether Wi‑Fi Direct should be enabled in the given power state.
pub fn should_enable_wifi(state: PowerState) -> bool {
    match state {
        PowerState::Emergency
        | PowerState::Active
        | PowerState::Balanced => true,
        _ => false,
    }
}

/// BLE scan interval and window (milliseconds) for the given power state.
pub fn get_scan_params(state: PowerState) -> (u32, u32) {
    match state {
        PowerState::Emergency   => (1000, 500),
        PowerState::Active      => (1000, 250),
        PowerState::Balanced    => (5000, 250),
        PowerState::PowerSaver  => (30000, 100),
        PowerState::Minimal     => (120000, 50),
        _ => (0, 0),
    }
}

/// OGM broadcast interval (seconds) for the given power state.
/// This is used by the routing table to throttle OGM flooding.
pub fn get_ogm_interval_secs(state: PowerState) -> u64 {
    match state {
        PowerState::Emergency   => 2,
        PowerState::Active      => 5,
        PowerState::Balanced    => 10,
        PowerState::PowerSaver  => 30,
        PowerState::Minimal     => 120,
        PowerState::Hibernation => 600,
        PowerState::Dead        => u64::MAX,
    }
}

/// Whether the engine should process incoming data packets in the current state.
pub fn should_process_data(state: PowerState) -> bool {
    state != PowerState::Dead
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_active_when_charging() {
        assert_eq!(compute_state(50, true, 0.0, None), PowerState::Active);
    }

    #[test]
    fn test_state_boundaries() {
        assert_eq!(compute_state(5, false, 0.0, None), PowerState::Dead);
        assert_eq!(compute_state(6, false, 0.0, None), PowerState::Hibernation);
        assert_eq!(compute_state(16, false, 0.0, None), PowerState::Minimal);
        assert_eq!(compute_state(31, false, 0.0, None), PowerState::PowerSaver);
        assert_eq!(compute_state(51, false, 0.0, None), PowerState::Balanced);
        assert_eq!(compute_state(81, false, 0.0, None), PowerState::Active);
    }

    #[test]
    fn test_user_override() {
        let state = compute_state(10, false, 0.0, Some(PowerState::Balanced));
        assert_eq!(state, PowerState::Balanced);
    }

    #[test]
    fn test_advertise_policy() {
        assert!(should_advertise(PowerState::Emergency));
        assert!(!should_advertise(PowerState::Hibernation));
    }

    #[test]
    fn test_wifi_policy() {
        assert!(should_enable_wifi(PowerState::Active));
        assert!(!should_enable_wifi(PowerState::PowerSaver));
    }
}
