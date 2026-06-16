import groovy.json.*
import java.net.URLEncoder
import java.security.MessageDigest

// Modified by Gordon Thelander: AP sign-in support, MSG100 channel 0 handling, configurable polling, creation-time naming, command follow-up polling, definition menu placement, and child-device uninstall cleanup.

def appVersion() { return "0.2.3-ap-signin-msg100-channel0-polling-rename-menu-cleanup-schedulefix" }

definition(
	name: "Meross Garage Door Manager",
	namespace: "ajardolino3",
	author: "Art Ardolino",
	description: "Manages the addition and removal of Meross Garage Door Devices. Modified by Gordon Thelander.",
	category: "Bluetooth",
    menu: "Integrations",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
    importUrl: ""
)

preferences() {
    page name: "mainPage"
    page name: "addGarageDoorStep1"
    page name: "addGarageDoorStep2"
    page name: "addGarageDoorStep3"
    page name: "addGarageDoorStep4"
    page name: "addGarageDoorStep5"
    page name: "listGarageDoorPage"
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "Meross Garage Door Manager", uninstall: true, install: true) {
        section(){ 
            paragraph("The Meross Garage Door Manager assists with the configuration of Meross Garage Door Opener devices.")
			href "addGarageDoorStep1", title: "<b>Add New Garage Doors</b>", description: "Adds new garage door devices."
			href "listGarageDoorPage", title: "<b>List Garage Doors</b>", description: "Lists added garage door devices."
			input "debugLog", "bool", title: "Enable debug logging", submitOnChange: true, defaultValue: false
        }
    }
}

def listGarageDoorPage() {
    def devices = getChildDevices()
    def message = ""
    devices.each{ device ->
        message += "\t${device.label} (ID: ${device.getDeviceNetworkId()})\n"
    }
    return dynamicPage(name: "listGarageDoorPage", title: "List Garage Doors", install: false, nextPage: mainPage) {
        section() {
            paragraph "The following devices were added using the 'Add New Garage Doors' feature."
            paragraph message
        }
    }
}

def addGarageDoorStep1() {
	def newBeacons = [:]
	state.beacons?.each { beacon ->
		def isChild = getChildDevice(beacon.value.dni)
		if (!isChild && beacon.value.present) {
            newBeacons["${beacon.value.dni}"] = "${beacon.value.type}: ${beacon.value.dni}"
		}
	}
    
    return dynamicPage(name: "addGarageDoorStep1", title: "Add New Garage Doors (Step 1)", install: false, nextPage: addGarageDoorStep2) {
        section(){
            input "merossUsername", "string", required: true, title: "Enter your Meross username"
            input "merossPassword", "password", required: true, title: "Enter your Meross password"
            input "merossIP", "string", required: true, title: "Enter the IP address of your Meross device"
            input "merossApiBase", "string", required: false, title: "Meross API base URL", defaultValue: "https://iotx-ap.meross.com"
        }
    }
}

def addGarageDoorStep2() {
    def response = loginMeross(merossUsername,merossPassword)
    if(response.code == 200) {
        state.data = getMerossData(response.token)
        state.merossKey = response.key
        if(!(state.data instanceof List)) {
            return dynamicPage(name: "addGarageDoorStep2", title: "Device Discovery Failed", install: false, nextPage: mainPage) {
                section(){
                    paragraph "${state.data?.error ?: state.data ?: 'Unable to retrieve Meross device list'}"
                }
            }
        }
        def devices = [:]
        state.data.each{ device ->
            devices["${device.uuid}"] = device.devName
        }
        return dynamicPage(name: "addGarageDoorStep2", title: "Add New Garage Doors (Step 2)", install: false, nextPage: addGarageDoorStep3) {
            section(){
    			input ("selectedDevice", "enum",
				   required: true,
				   multiple: false,
				   title: "Select a device to add (${devices.size() ?: 0} devices detected)",
				   description: "Use the dropdown to select a device.",
                   options: devices)
            }
        }
    }
    else {
        return dynamicPage(name: "addGarageDoorStep2", title: "Login Failed", install: false, nextPage: mainPage) {
        section(){
            paragraph "${response.error ?: 'Unknown login error'}"
            }
        }
    }
}

def addGarageDoorStep3() {
    def doors = [:]
    def selectedMerossDevice = null
    state.data.each { device ->
        if(device.uuid == selectedDevice) {
            selectedMerossDevice = device
            doors = getDoorChannelMap(device)
        }
    }

    return dynamicPage(name: "addGarageDoorStep3", title: "Add New Garage Doors (Step 3)", install: false, nextPage: addGarageDoorStep4) {
        section(){
            if(doors.size() > 0) {
                input ("selectedDoors", "enum",
                       required: true,
                       multiple: true,
                       title: "Select one or more garage doors to add (${doors.size() ?: 0} new doors detected)",
                       description: "Use the dropdown to select the door(s).",
                       options: doors)
            } else {
                paragraph "No un-added garage door channels were detected for the selected Meross device."
                paragraph "Selected device: ${selectedMerossDevice?.devName ?: selectedDevice}"
                paragraph "Raw channel count reported by Meross: ${selectedMerossDevice?.channels?.size() ?: 0}"
                paragraph "Raw selected device data: ${selectedMerossDevice}"
            }
        }
    }
}

def addGarageDoorStep4() {

    def doors = [:]
    state.data.each { device ->
        if(device.uuid == selectedDevice) {
            doors = getDoorChannelObjectMap(device)
        }
    }

    def selectedDoorList = normalisedSelectedDoors()

    return dynamicPage(name:"addGarageDoorStep4",
                       title: "Name Garage Doors (Step 4)",
                       nextPage: addGarageDoorStep5,
                       install: false) {
        section() {
            paragraph "Enter the Hubitat child device name to use when each garage door is created."
            selectedDoorList.each { door_index ->
                def door = doors[door_index]
                def sourceName = door?.devName ?: door?.name ?: door?.channelName ?: "Meross channel ${door_index}"
                def defaultName = defaultGarageDoorName(door_index)
                input("garageDoorName_${settingSuffix(door_index)}", "string",
                      required: true,
                      title: "Garage door name for channel ${door_index}",
                      description: "Meross reported name: ${sourceName}. Default: ${defaultName}",
                      defaultValue: defaultName)
            }
        }
    }
}

def addGarageDoorStep5() {
    
    def doors = [:]
    state.data.each { device ->
        if(device.uuid == selectedDevice) {
            doors = getDoorChannelObjectMap(device)
        }
    }
    
    def status = []
    def message = ""
    def selectedDoorList = normalisedSelectedDoors()

    selectedDoorList.each{ door_index -> 
        def door = doors[door_index]
        def merossDoorName = door?.devName ?: door?.name ?: "Garage door channel ${door_index}"
        def doorName = configuredGarageDoorName(door_index)
        logDebug("index: " + door_index + ", door:" + door + ", label:" + doorName)
        def dni = selectedDevice + ":" + door_index
        def isChild = getChildDevice(dni)
        def success = false
        def err = ""
        if (!isChild) {
            try {
                isChild = addChildDevice("ithinkdancan", "Meross Smart WiFi Garage Door Opener", dni, ["label": doorName])
                isChild.updateSetting("deviceIp", merossIP)
                isChild.updateSetting("channel", Integer.parseInt(door_index.toString()))
                isChild.updateSetting("uuid", selectedDevice)
                isChild.updateSetting("key", state.merossKey)
                isChild.updateSetting("messageId", "N/A")
                isChild.updateSetting("sign", "N/A")
                isChild.updateSetting("timestamp", 0)
                isChild.updateSetting("pollFrequencySeconds", [value:"60", type:"enum"])
                isChild.updateSetting("openCommandPollDelaySeconds", [value:2, type:"number"])
                isChild.updateSetting("closeCommandPollDelaySeconds", [value:20, type:"number"])
                initialiseManagedChildDevice(isChild)
                success = true
            }
            catch(exception) {
                err = exception
            }
        }
        if(success) {
            message += "New door added successfully as '" + doorName + "' (Meross: " + merossDoorName + ").<br/>"
        } else {
            message += "Unable to add door channel " + door_index + ": " + err + "<br/>";
        }
    }
    app?.removeSetting("selectedDevice")
    app?.removeSetting("selectedDoors")
    selectedDoorList.each { door_index ->
        app?.removeSetting("garageDoorName_${settingSuffix(door_index)}")
    }
    
    return dynamicPage(name:"addGarageDoorStep5",
                       title: "Add Garage Door Status",
                       nextPage: mainPage,
                       install: false) {
        section() {
            paragraph message
        }
    }
}

def normalisedSelectedDoors() {
    def selectedDoorList = []
    if(selectedDoors instanceof List) {
        selectedDoorList = selectedDoors.collect { it.toString() }
    } else if(selectedDoors) {
        selectedDoorList = [selectedDoors.toString()]
    }
    return selectedDoorList
}

def settingSuffix(value) {
    return (value ?: "0").toString().replaceAll(/[^A-Za-z0-9_]/, "_")
}

def defaultGarageDoorName(door_index) {
    def suffix = ""
    try {
        suffix = (Integer.parseInt(door_index.toString()) + 1).toString()
    } catch(ignored) {
        suffix = door_index?.toString() ?: "1"
    }
    return "Garage Door ${suffix}"
}

def configuredGarageDoorName(door_index) {
    def settingName = "garageDoorName_${settingSuffix(door_index)}"
    def configuredName = settings[settingName]?.toString()?.trim()
    return configuredName ?: defaultGarageDoorName(door_index)
}

def getDoorChannelMap(device) {
    def doors = [:]
    def channels = device?.channels

    if(channels instanceof List && channels.size() > 0) {
        for(int i = 0; i < channels.size(); i++) {
            def ch = channels[i]
            def dni = device.uuid + ":${i}"
            def isChild = getChildDevice(dni)
            if(!isChild) {
                def chName = ch?.devName ?: ch?.name ?: ch?.channelName ?: "Garage door channel ${i}"
                doors["${i}"] = chName
            }
        }
    } else {
        def dni = device.uuid + ":0"
        def isChild = getChildDevice(dni)
        if(!isChild) {
            doors["0"] = device?.devName ?: "Garage door channel 0"
        }
    }

    return doors
}

def getDoorChannelObjectMap(device) {
    def doors = [:]
    def channels = device?.channels

    if(channels instanceof List && channels.size() > 0) {
        for(int i = 0; i < channels.size(); i++) {
            doors["${i}"] = channels[i]
        }
    } else {
        doors["0"] = [devName: (device?.devName ?: "Garage door channel 0")]
    }

    return doors
}

def installed() {
    initialiseManagedChildDevices()
}

def updated() {
    initialiseManagedChildDevices()
}

def initialiseManagedChildDevices() {
    getChildDevices()?.each { child ->
        initialiseManagedChildDevice(child)
    }
}

def initialiseManagedChildDevice(child) {
    if(!child) return
    try {
        child.initialize()
        logDebug("Initialised child device ${child.displayName ?: child.label}")
    } catch(Exception e1) {
        try {
            child.poll()
            logDebug("Polled child device ${child.displayName ?: child.label} to seed polling schedule")
        } catch(Exception e2) {
            log.warn("Unable to initialise/poll child device ${child.displayName ?: child.label}: ${e2}")
        }
    }
}

def uninstalled() {
    logDebug("Uninstalling Meross Garage Door Manager")
    unschedule()
    deleteManagedChildDevices()
    state.clear()
}

def deleteManagedChildDevices() {
    def children = getChildDevices()
    if(!children || children.size() == 0) {
        logDebug("No managed child devices found to delete")
        return
    }

    children.each { child ->
        try {
            def dni = child.getDeviceNetworkId()
            logDebug("Deleting managed child device: ${child.displayName ?: child.label ?: dni} (${dni})")
            deleteChildDevice(dni)
        } catch (Exception e) {
            log.warn "Unable to delete managed child device ${child?.displayName ?: child?.label ?: child?.deviceNetworkId}. It may still be in use by another app, dashboard, rule, or automation. Remove those references first, then delete it manually. Error: ${e.message}"
        }
    }
}

def getMerossApiBase() {
    def base = (settings?.merossApiBase ?: "https://iotx-ap.meross.com").toString().trim()
    if(base.endsWith("/")) {
        base = base.substring(0, base.length() - 1)
    }
    return base
}

def generator(alphabet,n) {
  return new Random().with {
    (1..n).collect { alphabet[ nextInt( alphabet.length() ) ] }.join()
  }
}

// Hubitat / Meross HTTP responses can arrive in several odd shapes depending on
// Hubitat's HTTP parser and the Meross endpoint. Keep this deliberately simple
// because Hubitat's Groovy sandbox blocks some reflective calls.

def md5Hex(value) {
    MessageDigest digest = MessageDigest.getInstance('MD5')
    digest.update(value.bytes, 0, value.length())
    return new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
}

def encodeFormValue(value) {
    return URLEncoder.encode((value ?: '').toString(), 'UTF-8')
}

def normaliseMerossResponse(raw) {
    def parsed = raw

    try {
        if(raw instanceof String) {
            parsed = new JsonSlurper().parseText(raw)
        }
    } catch (e) {
        parsed = raw
    }

    if(parsed instanceof List) {
        parsed = parsed.find { it instanceof Map } ?: (parsed ? parsed[0] : null)
    }

    // Some Hubitat/Meross responses have appeared as a Map whose key is the
    // actual response object and whose value is null, for example:
    // [{"apiStatus":0,"data":{"token":"...","key":"..."}}:null]
    if(parsed instanceof Map && !parsed.containsKey("data") && parsed.size() == 1) {
        def firstKey = parsed.keySet().iterator().next()
        if(firstKey instanceof Map || firstKey instanceof String) {
            parsed = firstKey
            try {
                if(parsed instanceof String) {
                    parsed = new JsonSlurper().parseText(parsed)
                }
            } catch (ignored) {
                // Leave parsed as-is; regex fallback below will still handle strings.
            }
        }
    }

    return parsed
}

def extractFieldFromRaw(raw, fieldName) {
    def text = raw?.toString() ?: ""
    def pattern = java.util.regex.Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")
    def matcher = pattern.matcher(text)
    if(matcher.find()) {
        return matcher.group(1)
    }
    return ""
}

def getMerossData(token) {
    def nonce = generator( (('A'..'Z')+('0'..'9')).join(), 16 )    
    def unix_time = (Integer)Math.floor(new Date().getTime() / 1000);

    def param = "e30=";
    def encoded_param = param;

    def concat_sign = ["23x17ahWarFH6w29", unix_time, nonce, encoded_param].join("")
    def sign = md5Hex(concat_sign)

    def data = [:]
    data.params = encoded_param
    data.sign = sign
    data.timestamp = unix_time
    data.nonce = nonce
    def json = JsonOutput.toJson(data)
    
    def commandParams = [
		uri: "${getMerossApiBase()}/v1/Device/devList",
		contentType: "application/json",
		requestContentType: 'application/json',
        headers: ['Authorization':'Basic ' + token],
		body : data
	]
	def respData
	try {
		httpPostJson(commandParams) {resp ->
            if (resp.status == 200) {
                logDebug("meross data raw: " + resp.data)
                def parsed = normaliseMerossResponse(resp.data)
                if(parsed instanceof Map && parsed.containsKey("apiStatus") && parsed.apiStatus != 0) {
                    respData = [code: 9999, error: "Meross API devList failed. apiStatus=${parsed.apiStatus}, info=${parsed.info ?: 'no info returned'}"]
                } else {
                    respData = parsed?.data
                }
                logDebug("meross data parsed: " + respData)
			} else {
				respData = [code: resp.status, error: "HTTP Protocol Error: ${resp.status}"]
			}
		}
	} catch (e) {
		def msg = "Error = ${e}\n\n"
		respData = [code: 9999, error: "${e}"]
	}
    
    return respData
}

def loginMeross(email, password) {
    def nonce = generator( (('A'..'Z')+('0'..'9')).join(), 16 )    
    def unix_time = (Integer)Math.floor(new Date().getTime() / 1000);

    def param = [:]
    param.email = email
    param.password = password
    def json = JsonOutput.toJson(param)
    def encoded_param = json.bytes.encodeBase64().toString();

    def concat_sign = ["23x17ahWarFH6w29", unix_time, nonce, encoded_param].join("")
    def sign = md5Hex(concat_sign)
  
    def formBody = "params=${encodeFormValue(encoded_param)}&sign=${encodeFormValue(sign)}&timestamp=${encodeFormValue(unix_time)}&nonce=${encodeFormValue(nonce)}"
      
	def commandParams = [
		uri: "${getMerossApiBase()}/v1/Auth/signIn",
		contentType: 'application/x-www-form-urlencoded',
		body : formBody
	]
	def respData
	try {
		httpPost(commandParams) {resp ->
            if (resp.status == 200) {
                def rawRespData = resp.data
                def retobj = [:]
                retobj.code = 200
                retobj.token = ""
                retobj.key = ""
                logDebug("respData:" + rawRespData)

                def parsed = normaliseMerossResponse(rawRespData)

                // Meross often returns HTTP 200 even when the API-level call failed.
                // apiStatus=0 means success. Anything else should be treated as failure.
                if(parsed instanceof Map && parsed.containsKey("apiStatus") && parsed.apiStatus != 0) {
                    retobj.code = 9999
                    retobj.error = "Meross API login failed. apiStatus=${parsed.apiStatus}, info=${parsed.info ?: 'no info returned'}"
                    respData = retobj
                    return
                }

                def authData = (parsed?.data instanceof Map) ? parsed.data : parsed

                retobj.token = (authData?.token ?: parsed?.token ?: "").toString()
                retobj.key = (authData?.key ?: parsed?.key ?: "").toString()

                // Fallback for Hubitat's odd map-as-key response shape, where the
                // visible response clearly contains token/key but normal Map access fails.
                if(retobj.token.length()==0) {
                    retobj.token = extractFieldFromRaw(rawRespData, "token")
                }
                if(retobj.key.length()==0) {
                    retobj.key = extractFieldFromRaw(rawRespData, "key")
                }

                if(retobj.token.length()==0 || retobj.key.length()==0)
                {
                    retobj.code = 9999
                    retobj.error = "Login succeeded but token/key was not found in Meross response. Response: ${rawRespData}"
                }
                respData = retobj
			} else {
				respData = [code: resp.status, error: "HTTP Protocol Error: ${resp.status}"]
			}
		}
	} catch (e) {
		def msg = "Error = ${e}\n\n"
		respData = [code: 9999, error: "${e}"]
	}
    
    return respData
}

def logDebug(msg) {
    if(debugLog) log.debug(msg)
}
