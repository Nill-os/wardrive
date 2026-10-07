package com.dreknil.wardrivebridge

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One Meshtastic LoRa node known to the user's radio during a run (MeshtasticRadioLink). One row
 * per node per run, updated in place as the radio reports it. lat/lon is the node's own broadcast
 * position (0,0 = none); heardLat/heardLon is where this phone was when the radio heard the node
 * live during the run (0,0 = not heard live). WDGWars counts a node once, worldwide, and needs a
 * position: MeshUploader sends the node's own position, else where it was heard.
 */
@Entity(
    tableName = "mesh_nodes",
    indices = [Index(value = ["runId", "nodeNum"], unique = true)],
)
data class MeshNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val nodeNum: Long,
    val nodeId: String,
    val longName: String,
    val shortName: String,
    val hwModel: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Int,
    val positionTime: Long,
    val lastHeard: Long,
    val snr: Float,
    val hopsAway: Int,
    val viaMqtt: Boolean,
    val heardLat: Double,
    val heardLon: Double,
    val heardAtMs: Long,
    val updatedAtMs: Long,
)
