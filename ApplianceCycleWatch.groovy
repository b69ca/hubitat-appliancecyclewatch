/**
 * Appliance Cycle Watch 1.0.0
 * Copyright 2026 Jon Wallace. MIT License.
 */
metadata {
    definition(name: "Appliance Cycle Watch", namespace: "jonw", author: "Jon Wallace",
        importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-appliancecyclewatch/main/ApplianceCycleWatch.groovy") {
        capability "Sensor"
        capability "PowerMeter"
        capability "Refresh"
        capability "Initialize"
        capability "PushableButton"
        attribute "cycleState", "enum", ["idle", "running", "complete"]
        attribute "status", "enum", ["waiting", "ready", "stale", "configurationError"]
        attribute "dataStale", "enum", ["true", "false"]
        attribute "lastSuccessfulUpdate", "string"
        attribute "dataAge", "number"
        attribute "cycleMinutes", "number"
        attribute "lastCycleMinutes", "number"
        attribute "completedCycles", "number"
        attribute "lastCompleted", "string"
        attribute "pendingTransition", "enum", ["none", "start", "finish"]
        attribute "longCycle", "enum", ["true", "false"]
        attribute "notificationText", "string"
        command "reportPower", [[name: "Watts", type: "NUMBER", description: "Current nonnegative power reading"]]
        command "acknowledge"
        command "resetCycle"
    }
    preferences {
        input name: "startWatts", type: "decimal", title: "Start threshold (watts)", defaultValue: 10, range: "0.1..100000", required: true
        input name: "finishWatts", type: "decimal", title: "Finish threshold (watts)", defaultValue: 3, range: "0..100000", required: true
        input name: "startSeconds", type: "number", title: "Confirm start for this many seconds", defaultValue: 30, range: "0..3600", required: true
        input name: "finishSeconds", type: "number", title: "Confirm low power for this many seconds", defaultValue: 180, range: "1..7200", required: true
        input name: "minimumMinutes", type: "number", title: "Minimum cycle length (minutes)", defaultValue: 5, range: "0..1440", required: true
        input name: "staleSeconds", type: "number", title: "Reading becomes stale after (seconds)", description: "Use periodic power reports, even when watts do not change.", defaultValue: 600, range: "10..86400", required: true
        input name: "longMinutes", type: "number", title: "Warn after this cycle length (minutes, 0 disables)", defaultValue: 180, range: "0..10080", required: true
        input name: "logEnable", type: "bool", title: "Debug logging for 30 minutes", defaultValue: false
    }
}

void installed() {
    state.cycle = "idle"
    state.completed = 0L
    initialize()
}

void updated() {
    // A preference change must not inherit a partially confirmed transition.
    state.pendingSince = null
    state.pending = null
    initialize()
}

void initialize() {
    unschedule()
    state.cycle = state.cycle in ["idle", "running", "complete"] ? state.cycle : "idle"
    state.completed = state.completed ?: 0L
    emit("numberOfButtons", 5)
    if (settings.logEnable) runIn(1800, "logsOff")
    refresh()
}

void uninstalled() { unschedule() }
void logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

private BigDecimal number(value, BigDecimal fallback, BigDecimal low, BigDecimal high) {
    try {
        BigDecimal result = new BigDecimal(value == null ? fallback.toString() : value.toString())
        return result >= low && result <= high ? result : fallback
    } catch (Exception ignored) { return fallback }
}

private Map config() {
    [start: number(settings.startWatts, 10G, 0.1G, 100000G),
     finish: number(settings.finishWatts, 3G, 0G, 100000G),
     startMs: number(settings.startSeconds, 30G, 0G, 3600G).longValue() * 1000L,
     finishMs: number(settings.finishSeconds, 180G, 1G, 7200G).longValue() * 1000L,
     minimumMs: number(settings.minimumMinutes, 5G, 0G, 1440G).longValue() * 60000L,
     staleMs: number(settings.staleSeconds, 600G, 10G, 86400G).longValue() * 1000L,
     longMs: number(settings.longMinutes, 180G, 0G, 10080G).longValue() * 60000L]
}

void reportPower(value) {
    BigDecimal watts
    try { watts = new BigDecimal(value.toString()) }
    catch (Exception ignored) { log.warn "reportPower requires a finite, nonnegative number of watts"; return }
    if (watts < 0G || watts > 100000G) {
        log.warn "reportPower must be between 0 and 100000 watts"
        return
    }
    Map c = config()
    Long previous = state.lastReport == null ? null : (state.lastReport as Long)
    long time = now()
    // Missing observations break continuity, even if the stale callback was delayed.
    if (previous == null || time < previous || time - previous >= c.staleMs) {
        state.pending = null
        state.pendingSince = null
    }
    state.lastReport = time
    state.watts = watts.toString()
    emit("power", watts, "W")
    emit("lastSuccessfulUpdate", stamp(time))
    if (c.finish < c.start) {
        if (state.cycle == "running") {
            if (watts <= c.finish) {
                pending("finish", time)
                if (time - (state.pendingSince as Long) >= c.finishMs &&
                    time - (state.started as Long) >= c.minimumMs) {
                    state.lastDuration = ((time - (state.started as Long)) / 60000.0G).setScale(2, BigDecimal.ROUND_HALF_UP).toString()
                    state.completed = (state.completed as Long) + 1L
                    state.lastCompleted = time
                    transition("complete", 2, "Cycle finished")
                }
            } else clearPending()
        } else {
            if (watts >= c.start) {
                pending("start", time)
                if (time - (state.pendingSince as Long) >= c.startMs) {
                    state.started = state.pendingSince
                    state.longWarned = false
                    transition("running", 1, "Cycle started")
                }
            } else clearPending()
        }
    } else clearPending()
    if (settings.logEnable) log.debug "Power ${watts} W; cycle ${state.cycle}; pending ${state.pending ?: 'none'}"
    refresh()
}

private void pending(String kind, long time) {
    if (state.pending != kind) {
        state.pending = kind
        state.pendingSince = time
    }
}
private void clearPending() { state.pending = null; state.pendingSince = null }

private void transition(String target, Integer button, String message) {
    state.cycle = target
    clearPending()
    emit("cycleState", target)
    emit("notificationText", "${device.displayName}: ${message}")
    push(button)
}

void acknowledge() {
    if (state.cycle != "complete") return
    transition("idle", 5, "Completion acknowledged")
    refresh()
}

void resetCycle() {
    state.cycle = "idle"
    state.started = null
    state.longWarned = false
    clearPending()
    emit("notificationText", "${device.displayName}: Cycle reset")
    refresh()
}

void refresh() {
    Map c = config()
    long time = now()
    boolean missing = state.lastReport == null
    long age = missing ? -1L : Math.max(0L, time - (state.lastReport as Long))
    boolean stale = missing || age >= c.staleMs || (state.lastReport as Long) > time
    String health = c.finish >= c.start ? "configurationError" : (missing ? "waiting" : (stale ? "stale" : "ready"))
    if (stale) clearPending()
    if (health == "stale" && device.currentValue("status") != "stale") {
        emit("notificationText", "${device.displayName}: Power readings are stale; cycle retained")
        push(3)
    }
    long elapsed = state.cycle == "running" && state.started != null ? Math.max(0L, time - (state.started as Long)) : 0L
    boolean overdue = state.cycle == "running" && c.longMs > 0 && elapsed >= c.longMs
    if (overdue && !state.longWarned) {
        state.longWarned = true
        emit("notificationText", "${device.displayName}: Cycle has exceeded the warning time")
        push(4)
    }
    emit("status", health)
    emit("dataStale", stale.toString())
    emit("dataAge", missing ? -1 : (age / 1000L).longValue(), "s")
    emit("cycleState", state.cycle ?: "idle")
    emit("pendingTransition", state.pending ?: "none")
    emit("cycleMinutes", (elapsed / 60000.0G).setScale(2, BigDecimal.ROUND_HALF_UP), "min")
    emit("lastCycleMinutes", new BigDecimal(state.lastDuration ?: "0"), "min")
    emit("completedCycles", state.completed ?: 0L)
    emit("longCycle", overdue.toString())
    if (state.lastCompleted != null) emit("lastCompleted", stamp(state.lastCompleted as Long))
    // This callback checks current state. Obsolete callbacks cannot finish a cycle.
    unschedule("refresh")
    long delay = 60000L
    if (!stale) delay = Math.min(delay, c.staleMs - age)
    if (state.cycle == "running" && c.longMs > elapsed) delay = Math.min(delay, c.longMs - elapsed)
    runIn(Math.max(1, Math.ceil(delay / 1000.0D).intValue()), "refresh")
}

void push(value) {
    Integer button
    try {
        BigDecimal candidate = new BigDecimal(value.toString())
        if (candidate < 1G || candidate > 5G || candidate.stripTrailingZeros().scale() > 0) return
        button = candidate.intValue()
    } catch (Exception ignored) { return }
    sendEvent(name: "pushed", value: button, isStateChange: true, type: "digital")
}

private String stamp(long time) { new Date(time).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone ?: TimeZone.getTimeZone("UTC")) }
private void emit(String name, value, String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString()) return
    Map event = [name: name, value: value]
    if (unit) event.unit = unit
    sendEvent(event)
}
