package com.izzdarki.editlabelscomponent

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
import androidx.annotation.ColorInt
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.google.android.material.chip.Chip

fun generateStrikethroughChip(checkedColor: Int, uncheckedColor: Int, context: Context, label: String, isChecked: Boolean): Chip {
    val chip = Chip(context)
    chip.isCheckable =
        false // disables default checking behaviour, while still allowing to set isChecked
    strikethroughOnCheckedChanged(checkedColor, uncheckedColor, label, isChecked, chip) // sets strikethrough if unchecked
    return chip
}

fun strikethroughOnCheckedChanged(checkedColor: Int, uncheckedColor: Int, label: String, nowChecked: Boolean, chip: Chip) {
     // Text color
    chip.chipBackgroundColor = ColorStateList.valueOf(
        if (nowChecked) checkedColor
        else uncheckedColor
    )

    // Strikethrough
    chip.paintFlags =
        if (nowChecked) chip.paintFlags and STRIKE_THRU_TEXT_FLAG.inv()
        else chip.paintFlags or STRIKE_THRU_TEXT_FLAG
}