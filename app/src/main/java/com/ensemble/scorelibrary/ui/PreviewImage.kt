package com.ensemble.scorelibrary.ui

import android.content.Context
import android.widget.ImageView
import com.ensemble.scorelibrary.pdf.PdfFrame
import java.io.Closeable

/** One owner in the ViewModel plus a lease for each attached view. Retired frames cannot be acquired. */
class PreviewImage(val frame: PdfFrame) {
    private var owner = true
    private var references = 1
    @Synchronized fun canDisplay(): Boolean = owner && !frame.bitmap.isRecycled
    @Synchronized fun acquire(): Lease? {
        if (!owner || frame.bitmap.isRecycled) return null
        references++
        return Lease(frame) { releaseReference() }
    }
    @Synchronized fun releaseOwner() {
        if (!owner) return
        owner = false
        releaseReference()
    }
    @Synchronized private fun releaseReference() {
        references--
        if (references == 0) frame.bitmap.recycle()
    }
    class Lease internal constructor(val frame: PdfFrame, private val release: () -> Unit) : Closeable {
        private var closed = false
        @Synchronized override fun close() {
            if (!closed) { closed = true; release() }
        }
    }
}

/** Clear the drawable before releasing its lease; never recycle an image still attached to a view. */
internal class PreviewImageView(context: Context) : ImageView(context) {
    private var source: PreviewImage? = null
    private var lease: PreviewImage.Lease? = null
    init { scaleType = ScaleType.FIT_CENTER }
    fun show(image: PreviewImage) {
        if (source === image) return
        val next = image.acquire()
        setImageBitmap(next?.frame?.bitmap)
        lease?.close()
        source = image
        lease = next
    }
    fun clearPreview() {
        setImageDrawable(null)
        lease?.close()
        lease = null
        source = null
    }
}
