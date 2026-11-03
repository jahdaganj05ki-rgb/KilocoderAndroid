package com.kilocoder.android.domain.account

enum class RotationMode {
    MANUAL,
    NEXT,
    ROUND_ROBIN,
    FAILOVER,
    LEAST_USED,
    RANDOM,
}
