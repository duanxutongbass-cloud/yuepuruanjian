package com.ensemble.scorelibrary.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

/** Native Robolectric drawing of real Compose components with synthetic catalog fixtures. */
internal fun saveUiProof(activity:Activity,name:String) {
    val view=activity.window.decorView
    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
    try {
        view.draw(Canvas(bitmap))
        val target=File("build/reports/screenshots/$name")
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    } finally { bitmap.recycle() }
}
