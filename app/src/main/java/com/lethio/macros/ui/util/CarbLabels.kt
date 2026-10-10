package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lethio.macros.R
import com.lethio.macros.domain.model.CarbLabel

/**
 * A short note for a carbohydrate figure that is not plain available carbohydrate; empty otherwise.
 */
@Composable
fun carbLabelSuffix(carbLabel: CarbLabel?): String = when (carbLabel) {
    null -> ""
    CarbLabel.INCLUDES_FIBRE -> " (" + stringResource(R.string.carb_label_includes_fibre) + ")"
    CarbLabel.MONOSACCHARIDE_EQUIVALENT ->
        " (" + stringResource(R.string.carb_label_monosaccharide) + ")"
}
