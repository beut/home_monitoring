package pl.home.monitoring.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A string resource plus arguments, so view models stay free of Context. */
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    @Composable
    fun asString(): String = stringResource(id, *args.toTypedArray())
}

fun uiText(@StringRes id: Int, vararg args: Any) = UiText(id, args.toList())
