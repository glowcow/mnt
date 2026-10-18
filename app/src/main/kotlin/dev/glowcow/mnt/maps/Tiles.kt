package dev.glowcow.mnt.maps

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan

/** The arithmetic of map tiles in the Web Mercator projection, as every tile server lays them out. */
object Tiles {
    /** Pixels along the side of a tile. */
    const val SIZE = 256

    /** Where a longitude falls across the world at [zoom], in tiles from its western edge. */
    fun x(longitude: Double, zoom: Int): Double = (longitude + 180) / 360 * (1 shl zoom)

    /** Where a latitude falls down the world at [zoom], in tiles from its northern edge. */
    fun y(latitude: Double, zoom: Int): Double = (1 - asinh(tan(Math.toRadians(latitude))) / PI) / 2 * (1 shl zoom)
}
