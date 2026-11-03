package com.kilocoder.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kilocoder.android.R
import com.kilocoder.android.data.provider.AuthStyle
import com.kilocoder.android.domain.account.RotationMode

@Composable
fun rotationLabel(mode: String): String {
    return when (modeOf(mode)) {
        RotationMode.MANUAL -> stringResource(R.string.rotation_mode_manual)
        RotationMode.NEXT -> stringResource(R.string.rotation_mode_next)
        RotationMode.ROUND_ROBIN -> stringResource(R.string.rotation_mode_round_robin)
        RotationMode.FAILOVER -> stringResource(R.string.rotation_mode_failover)
        RotationMode.LEAST_USED -> stringResource(R.string.rotation_mode_least_used)
        RotationMode.RANDOM -> stringResource(R.string.rotation_mode_random)
    }
}

@Composable
fun rotationHint(mode: String): String {
    return when (modeOf(mode)) {
        RotationMode.MANUAL -> stringResource(R.string.rotation_hint_manual)
        RotationMode.NEXT -> stringResource(R.string.rotation_hint_next)
        RotationMode.ROUND_ROBIN -> stringResource(R.string.rotation_hint_round_robin)
        RotationMode.FAILOVER -> stringResource(R.string.rotation_hint_failover)
        RotationMode.LEAST_USED -> stringResource(R.string.rotation_hint_least_used)
        RotationMode.RANDOM -> stringResource(R.string.rotation_hint_random)
    }
}

@Composable
fun accountCount(count: Int): String =
    pluralStringResource(R.plurals.account_count, count, count)

fun modeOf(mode: String): RotationMode =
    runCatching { RotationMode.valueOf(mode) }.getOrDefault(RotationMode.FAILOVER)

fun authLabel(style: AuthStyle): String = when (style) {
    AuthStyle.BEARER -> "Authorization: Bearer"
    AuthStyle.X_API_KEY -> "x-api-key"
    AuthStyle.API_KEY -> "api-key"
    AuthStyle.X_GOOG_API_KEY -> "x-goog-api-key"
}
