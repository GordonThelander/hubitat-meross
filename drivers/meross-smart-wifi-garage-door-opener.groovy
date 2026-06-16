/**
 * Meross Smart WiFi Garage Door Opener
 *
 * Author: Daniel Tijerina
 * Last updated: 2026-06-16 - patched for robust scheduled polling, creation rename support, command follow-up status polling and Gordon Thelander modification attribution
 *
 * Modified by Gordon Thelander.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import java.security.MessageDigest

metadata {
    definition(
        name: 'Meross Smart WiFi Garage Door Opener',
        namespace: 'ithinkdancan',
        author: 'Daniel Tijerina'
    ) {
        capability 'DoorControl'
        capability 'GarageDoorControl'
        capability 'Actuator'
        capability 'ContactSensor'
        capability 'Refresh'
        capability 'Polling'
        capability 'Initialize'

        attribute 'model', 'string'
        attribute 'version', 'string'
        attribute 'lastRefresh', 'string'
    }
    preferences {
        section('Device Selection') {
            input('deviceIp', 'text', title: 'Device IP Address', description: '', required: true, defaultValue: '')
            input('key', 'text', title: 'Key', description: 'Required for firmware version 3.2.3 and greater', required: false, defaultValue: '')
            input('messageId', 'text', title: 'Message ID', description: '', required: true, defaultValue: '')
            input('timestamp', 'number', title: 'Timestamp', description: '', required: true, defaultValue: '')
            input('sign', 'text', title: 'Sign', description: '', required: true, defaultValue: '')
            input('uuid', 'text', title: 'UUID', description: '', required: true, defaultValue: '')
            input('channel', 'number', title: 'Garage Door Port', description: '', required: true, defaultValue: 1)
            input('openCommandPollDelaySeconds','number',title: 'Status poll after open command (seconds)', description:'Extra refresh after an open command is sent', required: true, defaultValue: 2)
            input('closeCommandPollDelaySeconds','number',title: 'Status poll after close command (seconds)', description:'Extra refresh after a close command is sent', required: true, defaultValue: 20)
            input('pollFrequencySeconds', 'enum', title: 'Polling frequency', description: 'How often Hubitat refreshes the garage door status from the Meross device', required: true, defaultValue: '60', options: ['0':'Disabled','15':'15 seconds','30':'30 seconds','60':'1 minute','120':'2 minutes','300':'5 minutes','600':'10 minutes','900':'15 minutes','1800':'30 minutes'])
            input('DebugLogging', 'bool', title: 'Enable debug logging', defaultValue: true)
        }
    }
}

def getDriverVersion() {
    5
}

def installed() {
    log.info('Installed')
    initialize()
}

def initialize() {
    log 'Initializing Device'
    unschedule('poll')
    refresh()
    schedulePolling()
}

private Integer getPollFrequencySeconds() {
    try {
        return (settings.pollFrequencySeconds ?: '60').toString().toInteger()
    } catch (Exception e) {
        return 60
    }
}

private Integer getIntegerSetting(String settingName, Integer defaultValue) {
    try {
        def value = settings[settingName]
        if(value == null || value.toString().trim().length() == 0) return defaultValue
        return value.toString().toInteger()
    } catch (Exception ignored) {
        return defaultValue
    }
}

private Integer getOpenCommandPollDelaySeconds() {
    return getIntegerSetting('openCommandPollDelaySeconds', 2)
}

private Integer getCloseCommandPollDelaySeconds() {
    return getIntegerSetting('closeCommandPollDelaySeconds', 20)
}

private void scheduleCommandFollowUpPoll(int open) {
    Integer delay = (open == 1) ? getOpenCommandPollDelaySeconds() : getCloseCommandPollDelaySeconds()
    String handlerName = (open == 1) ? 'refreshAfterOpenCommand' : 'refreshAfterCloseCommand'
    String commandName = (open == 1) ? 'open' : 'close'

    if(delay > 0) {
        runIn(delay, handlerName, [overwrite: true])
        log.info("Command follow-up status poll scheduled in ${delay} seconds after ${commandName} command")
    }
}

def refreshAfterOpenCommand() {
    log.info('Running follow-up status poll after open command')
    refresh()
}

def refreshAfterCloseCommand() {
    log.info('Running follow-up status poll after close command')
    refresh()
}

private void schedulePolling() {
    Integer seconds = getPollFrequencySeconds()
    unschedule('poll')

    if (seconds <= 0) {
        state.pollScheduleMode = 'disabled'
        log.info('Polling disabled')
        return
    }

    // Use Hubitat's recurring scheduler for the 1 minute default so it appears as a proper scheduled job.
    // Sub-minute intervals still use a runIn loop because Hubitat does not provide runEvery15Seconds/runEvery30Seconds helpers.
    if (seconds == 60) {
        state.pollScheduleMode = 'recurring'
        runEvery1Minute('poll')
        log.info('Polling scheduled every 1 minute using runEvery1Minute')
    } else if (seconds == 300) {
        state.pollScheduleMode = 'recurring'
        runEvery5Minutes('poll')
        log.info('Polling scheduled every 5 minutes using runEvery5Minutes')
    } else if (seconds == 600) {
        state.pollScheduleMode = 'recurring'
        runEvery10Minutes('poll')
        log.info('Polling scheduled every 10 minutes using runEvery10Minutes')
    } else if (seconds == 900) {
        state.pollScheduleMode = 'recurring'
        runEvery15Minutes('poll')
        log.info('Polling scheduled every 15 minutes using runEvery15Minutes')
    } else if (seconds == 1800) {
        state.pollScheduleMode = 'recurring'
        runEvery30Minutes('poll')
        log.info('Polling scheduled every 30 minutes using runEvery30Minutes')
    } else {
        state.pollScheduleMode = 'runIn'
        runIn(seconds, 'poll', [overwrite: true])
        log.info("Polling scheduled every ${seconds} seconds using runIn self-reschedule")
    }
}

def poll() {
    log.info('Polling Meross garage door status')
    refresh()

    // Only self-reschedule for custom/sub-minute intervals. Recurring Hubitat schedules keep themselves alive.
    if ((state.pollScheduleMode ?: 'runIn') == 'runIn') {
        schedulePolling()
    }
}


private Boolean settingPresent(value) {
    if (value == null) return false
    def s = value.toString().trim()
    return s.length() > 0 && s.toUpperCase() != 'N/A'
}

private Boolean hasModernKeySigning() {
    return settingPresent(settings.key)
}

private Boolean hasLegacySigning() {
    return settingPresent(settings.messageId) && settingPresent(settings.sign) && settingPresent(settings.timestamp) && settings.timestamp.toString() != '0'
}

private Boolean hasMinimumConfig() {
    return settingPresent(settings.deviceIp) && settingPresent(settings.uuid) && (hasModernKeySigning() || hasLegacySigning())
}

private def payloadSigningData() {
    if (hasModernKeySigning()) {
        return getSign()
    }
    return [MessageId: settings.messageId, Sign: settings.sign, CurrentTime: settings.timestamp]
}

private Integer configuredChannel() {
    return settings.channel == null ? 0 : settings.channel.toInteger()
}

private void warnMissingConfig() {
    sendEvent(name: 'door', value: 'unknown', isStateChange: false)
    log.warn("missing setting configuration - deviceIp=${settingPresent(settings.deviceIp)}, uuid=${settingPresent(settings.uuid)}, key=${hasModernKeySigning()}, legacySigning=${hasLegacySigning()}, channel=${settings.channel}")
}

def sendCommand(int open) {
    
    if (!hasMinimumConfig()) {
        warnMissingConfig()
        return
    }
    sendEvent(name: 'door', value: open ? 'opening' : 'closing', isStateChange: true)

    try {
        def payloadData = payloadSigningData()
        
        def hubAction = new hubitat.device.HubAction([
        method: 'POST',
        path: '/config',
        headers: [
            'HOST': settings.deviceIp,
            'Content-Type': 'application/json',
        ],
        body: '{"payload":{"state":{"open":' + open + ',"channel":' + configuredChannel() + ',"uuid":"' + settings.uuid + '"}},"header":{"messageId":"'+payloadData.get('MessageId')+'","method":"SET","from":"http://'+settings.deviceIp+'/config","sign":"'+payloadData.get('Sign')+'","namespace":"Appliance.GarageDoor.State","triggerSrc":"AndroidLocal","timestamp":' + payloadData.get('CurrentTime') + ',"payloadVersion":1' + ',"uuid":"' + settings.uuid + '"}}'
    ])
        scheduleCommandFollowUpPoll(open)
        return hubAction
    } catch (e) {
        log.error("runCmd hit exception ${e} on ${hubAction}")
    }
}


def refresh() {
    if (!hasMinimumConfig()) {
        warnMissingConfig()
        return
    }
    def hubAction = null
    try {
        def payloadData = payloadSigningData()

        log.info('Refreshing Meross garage door status')
        
        hubAction = new hubitat.device.HubAction([
            method: 'POST',
            path: '/config',
            headers: [
                'HOST': settings.deviceIp,
                'Content-Type': 'application/json',
            ],
            body: '{"payload":{},"header":{"messageId":"'+payloadData.get('MessageId')+'","method":"GET","from":"http://'+settings.deviceIp+'/subscribe","sign":"'+ payloadData.get('Sign') +'","namespace": "Appliance.System.All","triggerSrc":"AndroidLocal","timestamp":' + payloadData.get('CurrentTime') + ',"payloadVersion":1}}'
        ])
        log hubAction
        sendHubCommand(hubAction)
        def tz = location?.timeZone ?: TimeZone.getTimeZone('UTC')
        sendEvent(name: 'lastRefresh', value: new Date().format('yyyy-MM-dd HH:mm:ss', tz), isStateChange: true)
    } catch (Exception e) {
        log.debug "refresh hit exception ${e} on ${hubAction}"
    }
}

def open() {
    log.info('Opening Garage')
    return sendCommand(1)
}

def close() {
    log.info('Closing Garage')
    return sendCommand(0)
}

def updated() {
    log.info('Updated')
    initialize()
}

def parse(String description) {
    def msg = parseLanMessage(description)
    def body = parseJson(msg.body)
    
    if(msg.status != 200) {
         log.error("Request failed")
         return
    }
    
    // Close/Open request was sent
    if(body.header.method == "SETACK") return
    
    if (body.payload.all) {
        def doors = body.payload.all.digest.garageDoor
        def idx = configuredChannel()
        if (idx >= doors.size() && idx > 0) idx = idx - 1
        if (idx < 0) idx = 0
        def state = doors[idx].open
        sendEvent(name: 'door', value: state ? 'open' : 'closed')
        sendEvent(name: 'contact', value: state ? 'open' : 'closed')
        sendEvent(name: 'version', value: body.payload.all.system.firmware.version, isStateChange: false)
        sendEvent(name: 'model', value: body.payload.all.system.hardware.type, isStateChange: false)
    } else {
        //refresh()
        log.error ("Request failed")
    }
}

def getSign(int stringLength = 16){
    
    // Generate a random string 
    def chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
    def randomString = new Random().with { (0..stringLength).collect { chars[ nextInt(chars.length() ) ] }.join()}    
    
    int currentTime = new Date().getTime() / 1000
    def messageId = MessageDigest.getInstance("MD5").digest((randomString + currentTime.toString()).bytes).encodeHex().toString()
    def sign = MessageDigest.getInstance("MD5").digest((messageId + settings.key + currentTime.toString()).bytes).encodeHex().toString()
    
    def requestData = [
         CurrentTime: currentTime,
         MessageId: messageId,
         Sign: sign
    ]
    
    return requestData
}

def log(msg) {
    if (DebugLogging) {
        log.debug(msg)
    }
}