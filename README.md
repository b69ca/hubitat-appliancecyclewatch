# Appliance Cycle Watch

A local Hubitat virtual driver that turns a power-meter stream into appliance
**started**, **finished**, **stale**, and **taking too long** events. Use it for
laundry reminders, a dishwasher indicator, or a notification when an appliance
has been running unusually long.

It requires an existing power-reporting device and a Rule Machine rule (or other
local automation) that calls `reportPower(watts)`. It does not control the outlet,
subscribe to other devices, poll a meter, or make network requests. No API key,
account, external library, or cloud service is needed. Use an appropriately rated
meter for your appliance; this driver only interprets its readings.

## Install and update

1. In **Drivers Code → New Driver**, paste
   [ApplianceCycleWatch.groovy](https://raw.githubusercontent.com/b69ca/hubitat-appliancecyclewatch/main/ApplianceCycleWatch.groovy)
   and Save.
2. In **Devices → Add Device → Virtual**, create a device using **Appliance Cycle
   Watch**. Give it a name such as Washer Cycle.
3. Save Preferences, then connect the power-reporting rule described below.
4. Tune thresholds from observed standby and operating watts.

For updates, replace the existing driver code, Save, and run Initialize on the
virtual device. Active cycles and completed history survive Initialize and hub
startup. Saving preferences clears partial confirmations but retains the active
cycle. Existing source-device drivers remain in place.

## Connect readings with Rule Machine

Create a rule triggered by **Power changed** on the physical meter. Set a Decimal
local variable to that device's current `power` attribute. Add **Run Custom Action**,
select this virtual device (Sensor or PowerMeter), select `reportPower`, and pass
the variable as the numeric argument.

Also arrange a periodic report, for example every minute, using the meter's
current power **only when that reading is known to be recent**. Many meters emit
only changed values. Configure actual periodic reporting on the meter when
available. A repeated old attribute value cannot prove the sensor is alive:
forwarding cached values without checking the source timestamp defeats stale
protection. Choose the stale timeout longer than the meter's maximum normal
report interval. This driver treats each call as a new observation.

For a simple initial smoke test, manually call Report Power with 50, wait at least
30 seconds, and call 50 again. The cycle should be running. Call 0, then continue
calling 0 at intervals shorter than ten minutes. Completion requires at least
three minutes of low readings and five minutes from the first qualifying high
reading.

## Configuration

| Preference | Default | Meaning |
| --- | --- | --- |
| Start threshold | 10 W | A reading at or above this starts or continues start confirmation. |
| Finish threshold | 3 W | A running cycle needs readings at or below this to finish. Must be below start threshold. |
| Confirm start | 30 seconds | Reject short spikes. Zero starts immediately. |
| Confirm low power | 180 seconds | Reject short pauses. Increase beyond the appliance's longest normal pause. |
| Minimum cycle length | 5 minutes | Do not finish before this length. Zero disables this restriction. |
| Stale timeout | 600 seconds | A gap at or beyond this breaks confirmation and marks data stale. |
| Long-cycle warning | 180 minutes | Warn once per cycle. Zero disables it. Does not stop or finish the cycle. |
| Debug logging | Off | Logs watts and cycle state; automatically disables after 30 minutes. |

Thresholds are starting values, not universal appliance profiles. Observe a full
cycle before depending on a completion notification. Standby must be below the
finish threshold. Normal operation must exceed the start threshold long enough
to confirm. Fractional timing preferences are truncated to whole seconds or
minutes. Missing, invalid, or out-of-range preferences use the documented default;
crossed thresholds expose `configurationError` and block transitions.

## How decisions work

- `idle` or `complete`: high readings begin start confirmation. A later high
  reading after the confirmation time starts a cycle. Any lower reading cancels
  that confirmation.
- `running`: low readings begin finish confirmation. A later low reading after
  that period and the minimum cycle length completes the cycle. Any reading
  above the finish threshold cancels finish confirmation.
- Power between the two thresholds preserves the current cycle but cancels the
  applicable partial confirmation.
- A gap reaching the stale timeout clears partial confirmation. The first new
  reading begins a new confirmation period. Last known cycle and power remain.
- Completion stays visible until Acknowledge or a new confirmed start. Acknowledge
  does nothing during a running cycle.

**Timers never start or finish a cycle.** Confirmations require another fresh
report. Decisions are therefore limited by the report cadence; brief changes
between observations cannot be detected. Cycle duration runs from the first
qualifying high reading to the confirming finish report, including pauses and
outages. It is wall-clock duration, not motor runtime or energy consumption.

## Commands and capabilities

Capabilities: Sensor, PowerMeter, Refresh, Initialize, PushableButton.

| Command | Behavior |
| --- | --- |
| `reportPower(watts)` | Submit a fresh observation from 0 through 100000 W. Rejects malformed, negative, non-finite, or excessive values without renewing freshness. |
| `refresh()` | Recalculate age and warnings and restore the next health check. Does not poll the source meter. |
| `initialize()` | Restore schedules and public attributes; retains cycle and history. |
| `acknowledge()` | Change complete to idle and emit button 5. |
| `resetCycle()` | Deliberately abandon an active/partial cycle and return to idle; retains last completion and cumulative count. Does not emit a completion or acknowledgement button. |
| `push(buttonNumber)` | Emit a test button event (integer 1–5), without changing cycle or history. Can trigger real rules. |

| Button | Event |
| --- | --- |
| 1 | Cycle started |
| 2 | Cycle finished |
| 3 | Readings became stale (once per stale episode) |
| 4 | Cycle exceeded warning time (once per cycle) |
| 5 | Completion acknowledged |

| Attribute | Values / meaning |
| --- | --- |
| `power` | Last accepted reading, W; retained while stale. |
| `cycleState` | `idle`, `running`, `complete`. |
| `status` | `waiting` before a report, `ready`, `stale`, or `configurationError`. |
| `dataStale` | String `true` or `false`; true before the first report. |
| `lastSuccessfulUpdate` | ISO timestamp of last accepted report in hub time zone; absent until first report. |
| `dataAge` | Whole seconds since last report; -1 before first report. |
| `pendingTransition` | `none`, `start`, `finish`. |
| `cycleMinutes` | Current running wall-clock duration, rounded to two decimals; otherwise 0. |
| `lastCycleMinutes` | Last completed duration, minutes; initially 0. |
| `completedCycles` | Completed count since device creation; reset/acknowledge retain it. |
| `lastCompleted` | ISO timestamp; absent until first completion. |
| `longCycle` | String `true` while running past the configured warning time; otherwise `false`. |
| `notificationText` | Last transition, warning, acknowledgement, or reset message. Test pushes leave it unchanged. |
| `numberOfButtons` | 5. |
| `pushed` | Latest button number; intentional repeated events are emitted. |

## Automation examples

- **Laundry ready:** Button 2 pushed → notify “Washer finished.” For testing,
  use Push 2. A fixed notification avoids using a previous `notificationText`
  during a test push.
- **Dashboard lamp:** Button 1 → set an indicator blue; button 2 → green;
  button 5 → turn it off. An acknowledge dashboard action represents unloading.
- **Sensor trouble:** Button 3 → notify that cycle detection has lost fresh
  readings. A separate rule on `status` changing to `ready` can announce recovery.
- **Long cycle:** Button 4 → notify that the appliance needs checking. This warning
  does not indicate that the appliance has failed.
- **Door acknowledgement:** Appliance door opens → call `acknowledge`. It only
  clears a completed cycle, so opening a door during operation cannot cancel it.

## Failure behavior and troubleshooting

A silent meter cannot generate a finished event. Health checks run at most once
per minute, sooner at the next stale or long-warning deadline. Scheduled callbacks
read current state, so old callbacks cannot complete or restore a cancelled cycle.
Ordinary attributes only emit changed values; button events intentionally repeat.

If the device stays waiting, check the forwarding rule. If it goes stale, inspect
actual reporting frequency and source timestamps before increasing the timeout.
If a start never confirms, check that there are at least two qualifying reports
unless start confirmation is zero, and that gaps remain below the stale timeout.
If completion happens during a pause, lengthen low-power confirmation. If it never
finishes, check standby watts, minimum length, and whether repeated low readings
are being forwarded. Use Reset Cycle after a deliberately aborted appliance run.

A reboot retains serializable state and Initialize restores the schedule. If the
last report is stale, confirmation is cleared; active cycles are retained.
Wall-clock rollback marks future-dated reports stale and breaks continuity on the
next report. Substantial clock changes may affect measured duration.

Disable or remove forwarding rules before removing the virtual device. Removal
unschedules its jobs. Recreating the device starts a fresh history. No physical
outlet settings or other devices are changed by the driver.

## Development and validation

Run with Groovy 2.4 and Java:

```sh
groovy tests/ApplianceCycleWatchTest.groovy
```

The harness runs the actual driver with simulated Hubitat events, time, settings,
and schedules. Fifteen behavior groups cover confirmations, cancelled spikes and
dips, minimum length, stale boundaries and recovery, delayed callbacks, invalid
input, configuration errors, long warnings, repeated cycles, acknowledgement,
restart, JSON state, test buttons, logging, and clock rollback.

Platform references: [Hubitat capabilities](https://docs2.hubitat.com/en/developer/driver/capability-list)
and [common methods](https://docs2.hubitat.com/en/developer/common-methods-object).
There is no upstream data service, polling limit, or external data license.

License: MIT.

The 1.0.0 source compiled and ran on a real Hubitat hub on October 9, 2026.
Live checks exercised two starts and completions, acknowledgement, reset,
Initialize, test button events, scheduled stale detection, recovery confirmation,
and the one-minute long-cycle warning with accelerated test preferences. During
an outage, the running cycle remained intact and no completion was emitted.
The device log view contained no entries or errors. Production defaults were
restored afterward. The saved hub editor source matched the tested local source
by SHA-256 (`06176c8537f2ba8519f80de91ee91353f06fa26b82afd6075b1578c95b9e4903`).
Actual household appliance thresholds and a physical-meter forwarding rule still
need configuration for each installation; live tests used manually submitted
power observations.
