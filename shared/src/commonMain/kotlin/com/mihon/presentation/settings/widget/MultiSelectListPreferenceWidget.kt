package com.mihon.presentation.settings.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream4.compose.MultiSelectDialog
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.cancel
import com.lagradost.cloudstream4.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

@Composable
fun <T> MultiSelectListPreferenceWidget(
    values: Set<T>,
    title: String,
    subtitle: String?,
    icon: Painter?,
    entries: Map<out T, String>,
    onValuesChange: (Set<T>) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        MultiSelectDialog(
            title = title,
            entries = entries,
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
            ),
            confirmText = stringResource(Res.string.ok),
            confirm = { selection ->
                onValuesChange(selection)
                isDialogShown = false
            },
            dismissText = stringResource(Res.string.cancel),
            dismiss = {
                isDialogShown = false
            },
            selectedKeys = values,
        )
    }
}
