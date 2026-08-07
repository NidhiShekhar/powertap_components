package com.drivool.iot.powertap

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/** Resolves Material theme colors so programmatic UIs follow light/dark. */
object ThemeColors {
    @ColorInt
    fun resolve(context: Context, @AttrRes attr: Int): Int =
        MaterialColors.getColor(context, attr, 0)

    @ColorInt fun surface(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorSurface)

    @ColorInt fun surfaceContainer(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorSurfaceContainer)

    @ColorInt fun surfaceContainerHigh(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorSurfaceContainerHigh)

    @ColorInt fun surfaceContainerHighest(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorSurfaceContainerHighest)

    @ColorInt fun onSurface(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorOnSurface)

    @ColorInt fun onSurfaceVariant(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

    @ColorInt fun primary(context: Context) =
        ContextCompat.getColor(context, R.color.primary_blue)

    @ColorInt fun onPrimary(context: Context) =
        ContextCompat.getColor(context, R.color.white)

    @ColorInt fun outlineVariant(context: Context) =
        resolve(context, com.google.android.material.R.attr.colorOutlineVariant)

    @ColorInt fun error(context: Context) =
        ContextCompat.getColor(context, R.color.status_error)
}
