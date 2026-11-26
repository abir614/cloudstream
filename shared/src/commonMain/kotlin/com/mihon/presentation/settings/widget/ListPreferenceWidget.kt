package com.mihon.presentation.settings.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.cancel
import com.lagradost.cloudstream4.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

@Composable
fun <T> ListPreferenceWidget(
    value: T,
    title: String,
    subtitle: String?,
    icon: Painter?,
    entries: Map<out T, String>,
    onValueChange: (T) -> Unit,
    iconProvider: (@Composable (key: T, value: String) -> Unit)? = null
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        SingleSelectDialog(
            dismiss = { isDialogShown = false },
            title = title,
            entries = entries,
            selectedKey = value,
            confirm = { key ->
                onValueChange(key)
                isDialogShown = false
            },
            confirmText = stringResource(Res.string.ok),
            dismissText = stringResource(Res.string.cancel),
            iconProvider = iconProvider
        )
    }
}