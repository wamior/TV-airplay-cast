package io.github.wamior.tvcast.net

class Arg(val name: String, val dir: String, val stateVar: String)

class Action(val name: String, val args: List<Arg>)

class StateVar(
    val name: String,
    val type: String,
    val events: Boolean = false,
    val allowed: List<String> = emptyList(),
    val range: IntRange? = null,
)

class ServiceDef(
    val shortName: String,
    val type: String,
    val id: String,
    val actions: List<Action>,
    val vars: List<StateVar>,
) {
    val scpdUrl = "/$shortName/scpd.xml"
    val controlUrl = "/$shortName/control"
    val eventUrl = "/$shortName/event"

    fun scpdXml(): String = buildString {
        append("<?xml version=\"1.0\"?>\n")
        append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>")
        append("<actionList>")
        for (a in actions) {
            append("<action><name>${a.name}</name>")
            if (a.args.isNotEmpty()) {
                append("<argumentList>")
                for (g in a.args) {
                    append("<argument><name>${g.name}</name><direction>${g.dir}</direction>")
                    append("<relatedStateVariable>${g.stateVar}</relatedStateVariable></argument>")
                }
                append("</argumentList>")
            }
            append("</action>")
        }
        append("</actionList><serviceStateTable>")
        for (v in vars) {
            append("<stateVariable sendEvents=\"${if (v.events) "yes" else "no"}\">")
            append("<name>${v.name}</name><dataType>${v.type}</dataType>")
            if (v.allowed.isNotEmpty()) {
                append("<allowedValueList>")
                v.allowed.forEach { append("<allowedValue>$it</allowedValue>") }
                append("</allowedValueList>")
            }
            v.range?.let {
                append("<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step></allowedValueRange>")
            }
            append("</stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }
}

object UpnpServices {
    private fun i(n: String, sv: String) = Arg(n, "in", sv)
    private fun o(n: String, sv: String) = Arg(n, "out", sv)
    private val inst = i("InstanceID", "A_ARG_TYPE_InstanceID")
    private fun s(name: String, type: String = "string") = StateVar(name, type)

    val avTransport = ServiceDef(
        shortName = "AVTransport",
        type = "urn:schemas-upnp-org:service:AVTransport:1",
        id = "urn:upnp-org:serviceId:AVTransport",
        actions = listOf(
            Action("SetAVTransportURI", listOf(inst, i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData"))),
            Action(
                "GetMediaInfo",
                listOf(
                    inst, o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"),
                    o("CurrentURI", "AVTransportURI"), o("CurrentURIMetaData", "AVTransportURIMetaData"),
                    o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"),
                    o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"),
                    o("WriteStatus", "RecordMediumWriteStatus"),
                ),
            ),
            Action(
                "GetTransportInfo",
                listOf(inst, o("CurrentTransportState", "TransportState"), o("CurrentTransportStatus", "TransportStatus"), o("CurrentSpeed", "TransportPlaySpeed")),
            ),
            Action(
                "GetPositionInfo",
                listOf(
                    inst, o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"),
                    o("TrackMetaData", "CurrentTrackMetaData"), o("TrackURI", "CurrentTrackURI"),
                    o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"),
                    o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition"),
                ),
            ),
            Action(
                "GetDeviceCapabilities",
                listOf(
                    inst, o("PlayMedia", "PossiblePlaybackStorageMedia"), o("RecMedia", "PossibleRecordStorageMedia"),
                    o("RecQualityModes", "PossibleRecordQualityModes"),
                ),
            ),
            Action("GetTransportSettings", listOf(inst, o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode"))),
            Action("Stop", listOf(inst)),
            Action("Play", listOf(inst, i("Speed", "TransportPlaySpeed"))),
            Action("Pause", listOf(inst)),
            Action("Seek", listOf(inst, i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget"))),
            Action("Next", listOf(inst)),
            Action("Previous", listOf(inst)),
            Action("GetCurrentTransportActions", listOf(inst, o("Actions", "CurrentTransportActions"))),
        ),
        vars = listOf(
            s("A_ARG_TYPE_InstanceID", "ui4"),
            s("AVTransportURI"), s("AVTransportURIMetaData"), s("NextAVTransportURI"), s("NextAVTransportURIMetaData"),
            s("NumberOfTracks", "ui4"), s("CurrentMediaDuration"),
            s("PlaybackStorageMedium"), s("RecordStorageMedium"), s("PossiblePlaybackStorageMedia"),
            s("PossibleRecordStorageMedia"), s("RecordMediumWriteStatus"),
            StateVar("TransportState", "string", allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")),
            StateVar("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
            StateVar("TransportPlaySpeed", "string", allowed = listOf("1")),
            StateVar("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
            s("CurrentRecordQualityMode"), s("PossibleRecordQualityModes"),
            s("CurrentTrack", "ui4"), s("CurrentTrackDuration"), s("CurrentTrackMetaData"), s("CurrentTrackURI"),
            s("RelativeTimePosition"), s("AbsoluteTimePosition"),
            s("RelativeCounterPosition", "i4"), s("AbsoluteCounterPosition", "ui4"),
            s("CurrentTransportActions"),
            StateVar("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
            s("A_ARG_TYPE_SeekTarget"),
            StateVar("LastChange", "string", events = true),
        ),
    )

    val renderingControl = ServiceDef(
        shortName = "RenderingControl",
        type = "urn:schemas-upnp-org:service:RenderingControl:1",
        id = "urn:upnp-org:serviceId:RenderingControl",
        actions = listOf(
            Action("GetVolume", listOf(inst, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentVolume", "Volume"))),
            Action("SetVolume", listOf(inst, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredVolume", "Volume"))),
            Action("GetMute", listOf(inst, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentMute", "Mute"))),
            Action("SetMute", listOf(inst, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredMute", "Mute"))),
        ),
        vars = listOf(
            s("A_ARG_TYPE_InstanceID", "ui4"),
            StateVar("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
            StateVar("Volume", "ui2", range = 0..100),
            s("Mute", "boolean"),
            StateVar("LastChange", "string", events = true),
        ),
    )

    val connectionManager = ServiceDef(
        shortName = "ConnectionManager",
        type = "urn:schemas-upnp-org:service:ConnectionManager:1",
        id = "urn:upnp-org:serviceId:ConnectionManager",
        actions = listOf(
            Action("GetProtocolInfo", listOf(o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo"))),
            Action("GetCurrentConnectionIDs", listOf(o("ConnectionIDs", "CurrentConnectionIDs"))),
            Action(
                "GetCurrentConnectionInfo",
                listOf(
                    i("ConnectionID", "A_ARG_TYPE_ConnectionID"), o("RcsID", "A_ARG_TYPE_RcsID"),
                    o("AVTransportID", "A_ARG_TYPE_AVTransportID"), o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                    o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                    o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), o("Direction", "A_ARG_TYPE_Direction"),
                    o("Status", "A_ARG_TYPE_ConnectionStatus"),
                ),
            ),
        ),
        vars = listOf(
            StateVar("SourceProtocolInfo", "string", events = true),
            StateVar("SinkProtocolInfo", "string", events = true),
            StateVar("CurrentConnectionIDs", "string", events = true),
            s("A_ARG_TYPE_ConnectionStatus"), s("A_ARG_TYPE_ConnectionManager"), s("A_ARG_TYPE_Direction"),
            s("A_ARG_TYPE_ProtocolInfo"), s("A_ARG_TYPE_ConnectionID", "i4"), s("A_ARG_TYPE_AVTransportID", "i4"),
            s("A_ARG_TYPE_RcsID", "i4"),
        ),
    )
}
