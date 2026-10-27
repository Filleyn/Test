package com.trailguidegps.app.data

/** Plain-English names for the PAD-US public land codes. */
object PublicLand {
    val MANAGERS = mapOf(
        "USFS" to "US Forest Service", "BLM" to "Bureau of Land Management", "NPS" to "National Park Service",
        "FWS" to "US Fish and Wildlife Service", "USBR" to "Bureau of Reclamation", "USACE" to "Army Corps of Engineers",
        "DOD" to "Department of Defense", "NRCS" to "Natural Resources Conservation Service", "OTHF" to "Other federal agency",
        "SLB" to "State Land Board", "SDOL" to "State Department of Lands", "SFW" to "State Fish and Wildlife",
        "SPR" to "State Parks", "SDNR" to "State natural resources department", "SDC" to "State conservation department",
        "OTHS" to "Other state agency", "CITY" to "City", "CNTY" to "County", "REG" to "Regional agency",
        "RWD" to "Regional water district", "UNKL" to "Local government", "TRIB" to "Tribal", "NGO" to "Nonprofit",
        "PVT" to "Private", "JNT" to "Jointly managed", "UNK" to "Unknown manager",
    )

    val DESIGNATIONS = mapOf(
        "WA" to "Wilderness", "WSA" to "Wilderness study area", "NM" to "National monument",
        "NRA" to "National recreation area", "NCA" to "National conservation area", "TRIBL" to "Tribal land",
        "NF" to "National forest", "PUB" to "Public land", "SP" to "State park", "SREC" to "State recreation area",
        "SCA" to "State conservation area", "NWR" to "National wildlife refuge", "LP" to "Local park",
        "LREC" to "Local recreation area", "PREC" to "Private recreation or open space", "PCON" to "Private conservation land",
        "SFW" to "State wildlife area", "RMA" to "Recreation management area", "SRMA" to "Special recreation management area",
        "ACC" to "Access area", "ND" to "Not designated",
    )

    val ACCESS = mapOf(
        "OA" to "Open to the public", "RA" to "Restricted access (permit, fee or limited hours)",
        "XA" to "Closed to the public", "UK" to "Public access unknown",
    )

    /** "National forest" for a PROC boundary, depending on who manages it. */
    fun boundaryKind(des: String?, mgr: String?): String = when {
        des == "TRIBL" -> "Tribal land (reservation)"
        mgr == "USFS" -> "National forest"
        mgr == "NPS" -> "National park"
        mgr == "FWS" -> "National wildlife refuge"
        mgr == "DOD" -> "Military land"
        else -> DESIGNATIONS[des] ?: "Boundary"
    }

    /**
     * Title and subtitle for a tap on public land. Each argument is the props of
     * the feature hit in that layer, or null.
     */
    fun describe(special: Map<String, String?>?, boundary: Map<String, String?>?, land: Map<String, String?>?): Pair<String, String>? {
        if (special == null && boundary == null && land == null) return null
        val title = special?.get("name") ?: land?.get("name")?.takeIf { boundary?.get("name") == null || land["mgr"] !in setOf("USFS", "BLM") }
            ?: boundary?.get("name") ?: land?.get("name")
            ?: land?.get("mgr")?.let { MANAGERS[it] } ?: "Public land"
        val parts = mutableListOf<String>()
        special?.let { parts += DESIGNATIONS[it["des"]] ?: "Special area" }
        boundary?.let { b ->
            val n = b["name"]
            if (n != null && n != title) parts += n else parts += boundaryKind(b["des"], b["mgr"])
        }
        land?.let { l ->
            l["mgr"]?.let { m -> MANAGERS[m]?.let { if (it !in parts) parts += it } }
            ACCESS[l["access"]]?.let { parts += it }
        }
        if (land == null && boundary?.get("des") != "TRIBL") parts += "Land inside may be private; turn on Land manager to check"
        if (land == null && boundary?.get("des") == "TRIBL") parts += "Check with the tribe before entering"
        return title to parts.distinct().joinToString(" · ")
    }
}
