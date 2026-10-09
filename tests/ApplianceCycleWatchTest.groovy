import groovy.json.JsonOutput
import groovy.json.JsonSlurper
class HubDevice {
    Map values = [:], settings
    String displayName = 'Test appliance'
    Object currentValue(String key) { values[key] }
    void updateSetting(String key, Map setting) { settings[key] = setting.value == 'true' }
}
class Harness extends Script {
    Map state = [:], settings = [:], jobs = [:]
    List events = [], warnings = []
    HubDevice device = new HubDevice(settings: settings)
    Map location = [timeZone: TimeZone.getTimeZone('UTC')]
    long clock = 1000000L
    def log = [warn: { warnings << it }, debug: { }]
    Object run() { null }
    void metadata(Closure body) { }
    long now() { clock }
    void sendEvent(Map event) { events << event; device.values[event.name] = event.value }
    void runIn(Number seconds, String handler) { jobs[handler] = clock + seconds.longValue() * 1000L }
    void unschedule(String handler = null) { if (handler) jobs.remove(handler); else jobs.clear() }
    void restore(Map recovered) { state = recovered }
    void advance(long seconds) { clock += seconds * 1000L }
    int presses(int button) { events.count { it.name == 'pushed' && it.value == button } }
}
def compiler = new org.codehaus.groovy.control.CompilerConfiguration(scriptBaseClass: Harness.name)
def shell = new GroovyShell(this.class.classLoader, new Binding(), compiler)
def fresh = { Map prefs = [:] ->
    def d = shell.parse(new File(args ? args[0] : 'ApplianceCycleWatch.groovy'))
    d.settings.putAll(prefs); d.run(); d.installed(); d
}
int groups = 0
def check = { String label, Closure test -> test(); groups++; println "PASS ${label}" }
check('waiting and initialization') {
    def d = fresh(); assert d.device.values.status == 'waiting'; assert d.device.values.cycleState == 'idle'
    assert d.device.values.dataAge == -1; assert d.jobs.refresh; assert d.device.values.numberOfButtons == 5
}
check('start confirmation and spike cancellation') {
    def d = fresh(); d.reportPower(10); d.advance(20); d.reportPower(9); d.advance(20); d.reportPower(10)
    assert d.device.values.cycleState == 'idle'; d.advance(30); d.reportPower(10)
    assert d.device.values.cycleState == 'running'; d.reportPower(100); assert d.presses(1) == 1
}
check('dip cancellation and finish confirmation') {
    def d = fresh([startSeconds:0]); d.reportPower(50); d.advance(100); d.reportPower(3)
    d.advance(100); d.reportPower(4); d.reportPower(3); d.advance(180); d.reportPower(3)
    assert d.device.values.cycleState == 'complete'; assert d.presses(2) == 1
    assert d.device.values.lastCycleMinutes == 6.33G; d.reportPower(0); assert d.presses(2) == 1
}
check('minimum cycle length') {
    def d = fresh([startSeconds:0]); d.reportPower(50); d.reportPower(0); d.advance(180); d.reportPower(0)
    assert d.device.values.cycleState == 'running'; d.advance(120); d.reportPower(0)
    assert d.device.values.cycleState == 'complete'
}
check('timers cannot finish without new readings') {
    def d = fresh([startSeconds:0, minimumMinutes:0]); d.reportPower(50); d.reportPower(0)
    d.advance(180); d.refresh(); assert d.device.values.cycleState == 'running'
    d.reportPower(0); assert d.device.values.cycleState == 'complete'
}
check('stale data retains cycle and recovery restarts confirmation') {
    def d = fresh([startSeconds:0, minimumMinutes:0, staleSeconds:60, finishSeconds:30])
    d.reportPower(50); d.reportPower(0); d.advance(60); d.refresh(); d.refresh()
    assert d.device.values.status == 'stale'; assert d.device.values.cycleState == 'running'
    assert d.device.values.pendingTransition == 'none'; assert d.presses(3) == 1
    d.reportPower(0); assert d.device.values.status == 'ready'; assert d.device.values.cycleState == 'running'
    d.advance(30); d.reportPower(0); assert d.device.values.cycleState == 'complete'
}
check('delayed callbacks and stale boundary break continuity') {
    def d = fresh([staleSeconds:30]); d.reportPower(50); d.advance(30); d.reportPower(50)
    assert d.device.values.cycleState == 'idle'; d.advance(29); d.reportPower(50)
    assert d.device.values.cycleState == 'idle'; d.advance(1); d.reportPower(50)
    assert d.device.values.cycleState == 'running'
}
check('invalid input cannot renew freshness') {
    def d = fresh(); d.reportPower(1); def before = d.state.clone(); d.advance(1)
    [null, '', 'NaN', 'Infinity', -1, 100001, '1 W'].each { d.reportPower(it) }
    assert d.state == before; assert d.warnings.size() == 7
}
check('crossed thresholds block transitions and recover') {
    def d = fresh([startWatts:3, finishWatts:3, startSeconds:0]); d.reportPower(50)
    assert d.device.values.status == 'configurationError'; assert d.presses(1) == 0
    d.settings.startWatts = 10; d.updated(); d.reportPower(50); assert d.presses(1) == 1
}
check('long warning once, including during outage') {
    def d = fresh([startSeconds:0, longMinutes:1, staleSeconds:10]); d.reportPower(50)
    d.advance(60); d.refresh(); d.refresh(); assert d.presses(4) == 1
    assert d.device.values.longCycle == 'true'; assert d.device.values.cycleState == 'running'
    d.resetCycle(); assert d.device.values.longCycle == 'false'
}
check('acknowledge, next cycle and reset retain history') {
    def d = fresh([startSeconds:0, minimumMinutes:0, finishSeconds:1]); d.reportPower(50); d.acknowledge()
    assert d.device.values.cycleState == 'running'; d.reportPower(0); d.advance(1); d.reportPower(0)
    d.acknowledge(); d.acknowledge(); assert d.presses(5) == 1; assert d.device.values.cycleState == 'idle'
    d.reportPower(50); d.reportPower(0); d.advance(1); d.reportPower(0); d.reportPower(50)
    assert d.device.values.cycleState == 'running'; assert d.device.values.completedCycles == 2
    d.resetCycle(); assert d.device.values.completedCycles == 2
}
check('restart, changed preferences and obsolete callbacks') {
    def d = fresh(); d.reportPower(50); d.advance(10); d.initialize(); d.advance(20); d.reportPower(50)
    assert d.device.values.cycleState == 'running'; d.reportPower(0); d.updated()
    assert d.device.values.pendingTransition == 'none'; d.advance(180); d.refresh()
    assert d.device.values.cycleState == 'running'; d.uninstalled(); assert d.jobs.isEmpty()
}
check('button validation and repeated triggers') {
    def d = fresh(); [0,6,1.5,null,'NaN'].each { d.push(it) }; assert d.presses(1) == 0
    assert !d.events.any { it.name == 'pushed' }; d.push(2); d.push('2'); assert d.presses(2) == 2
    assert d.device.values.cycleState == 'idle'
}
check('JSON state and debug shutoff') {
    def d = fresh([logEnable:true,startSeconds:0]); assert d.jobs.logsOff; d.reportPower(50)
    d.restore(new JsonSlurper().parseText(JsonOutput.toJson(d.state))); d.initialize()
    assert d.device.values.cycleState == 'running'; d.logsOff(); assert !d.settings.logEnable
}
check('clock rollback') {
    def d = fresh(); d.reportPower(50); d.advance(-1); d.refresh(); assert d.device.values.status == 'stale'
    d.reportPower(50); assert d.device.values.cycleState == 'idle'
}
println "${groups} behavior groups passed"
