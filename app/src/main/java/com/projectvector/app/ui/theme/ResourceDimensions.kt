package com.projectvector.app.ui.theme

import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Reads an Android `sp` dimension without converting it through `dp`. */
@Composable
fun textUnitResource(@DimenRes id: Int): TextUnit {
    val resources = LocalContext.current.resources
    val value = TypedValue()
    resources.getValue(id, value, true)
    return TypedValue.complexToFloat(value.data).sp
}
