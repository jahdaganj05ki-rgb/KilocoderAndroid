package com.kilocoder.android.domain

fun interface Clock {
    fun now(): Long
}

object WallClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
