package com.opensource.svgaplayer.drawer

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.StaticLayout
import android.text.TextUtils
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoShapeEntity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal class SVGACanvasDrawer(
    videoItem: SVGAVideoEntity, private val dynamicItem: SVGADynamicEntity?
) : SGVADrawer(videoItem) {

    private val sharedValues = ShareValues()
    private val drawTextCache: HashMap<String, Bitmap> = hashMapOf()
    private val drawTextOffsetCache: HashMap<String, Float> = hashMapOf()
    private val drawTextRtlCache: HashMap<String, Boolean> = hashMapOf()
    private val drawTextMarqueeCache: HashMap<String, Boolean> = hashMapOf()
    private val pathCache = PathCache()

    private var beginIndexList: Array<Boolean>? = null
    private var endIndexList: Array<Boolean>? = null

    private val marqueeLinearGradientWidth = 20f
    private val marqueeLeftLinearGradient by lazy {
        LinearGradient(
            0f,
            0f,
            marqueeLinearGradientWidth,
            0f,
            Color.TRANSPARENT,
            Color.BLACK,
            Shader.TileMode.CLAMP
        )
    }
    private val marqueeRightLinearGradient by lazy {
        LinearGradient(
            0f,
            0f,
            marqueeLinearGradientWidth,
            0f,
            Color.BLACK,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    override fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        super.drawFrame(canvas, frameIndex, scaleType)
        playAudio(frameIndex)
        this.pathCache.onSizeChanged(canvas)
        val sprites = requestFrameSprites(frameIndex)
        // Filter null sprites
        if (sprites.isEmpty()) return
        val matteSprites = mutableMapOf<String, SVGADrawerSprite>()
        var saveID = -1
        beginIndexList = null
        endIndexList = null

        // Filter no matte layer
        var hasMatteLayer = false
        sprites.getOrNull(0)?.imageKey?.let {
            if (it.endsWith(".matte")) {
                hasMatteLayer = true
            }
        }
        sprites.forEachIndexed { index, svgaDrawerSprite ->

            // Save matte sprite
            svgaDrawerSprite.imageKey?.let {
                /// No matte layer included or VERSION Unsopport matte
                if (!hasMatteLayer || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // Normal sprite
                    drawSprite(svgaDrawerSprite, canvas, frameIndex)
                    // Continue
                    return@forEachIndexed
                }
                /// Cache matte sprite
                if (it.endsWith(".matte")) {
                    matteSprites[it] = svgaDrawerSprite
                    // Continue
                    return@forEachIndexed
                }
            }
            /// Is matte begin
            if (isMatteBegin(index, sprites)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    saveID = canvas.saveLayer(
                        0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), null
                    )
                } else {
                    canvas.save()
                }
            }
            /// Normal matte
            drawSprite(svgaDrawerSprite, canvas, frameIndex)

            /// Is matte end
            if (isMatteEnd(index, sprites)) {
                matteSprites[svgaDrawerSprite.matteKey]?.let {
                    drawSprite(
                        it,
                        this.sharedValues.shareMatteCanvas(canvas.width, canvas.height),
                        frameIndex
                    )
                    this.sharedValues.sharedMatteBitmap()?.let { bitmap ->
                        canvas.drawBitmap(
                            bitmap, 0f, 0f, this.sharedValues.shareMattePaint()
                        )
                    }
                    if (saveID != -1) {
                        canvas.restoreToCount(saveID)
                    } else {
                        canvas.restore()
                    }
                    // Continue
                    return@forEachIndexed
                }
            }
        }
        releaseFrameSprites(sprites)
    }

    private fun isMatteBegin(spriteIndex: Int, sprites: List<SVGADrawerSprite>): Boolean {
        if (beginIndexList == null) {
            val boolArray = Array(sprites.count()) { false }
            sprites.forEachIndexed { index, svgaDrawerSprite ->
                svgaDrawerSprite.imageKey?.let {
                    /// Filter matte sprite
                    if (it.endsWith(".matte")) {
                        // Continue
                        return@forEachIndexed
                    }
                }
                svgaDrawerSprite.matteKey?.let {
                    if (it.isNotEmpty()) {
                        sprites.getOrNull(index - 1)?.let { lastSprite ->
                            if (lastSprite.matteKey.isNullOrEmpty()) {
                                boolArray[index] = true
                            } else {
                                if (lastSprite.matteKey != svgaDrawerSprite.matteKey) {
                                    boolArray[index] = true
                                }
                            }
                        }
                    }
                }
            }
            beginIndexList = boolArray
        }
        return beginIndexList?.get(spriteIndex) ?: false
    }

    private fun isMatteEnd(spriteIndex: Int, sprites: List<SVGADrawerSprite>): Boolean {
        if (endIndexList == null) {
            val boolArray = Array(sprites.count()) { false }
            sprites.forEachIndexed { index, svgaDrawerSprite ->
                svgaDrawerSprite.imageKey?.let {
                    /// Filter matte sprite
                    if (it.endsWith(".matte")) {
                        // Continue
                        return@forEachIndexed
                    }
                }
                svgaDrawerSprite.matteKey?.let {
                    if (it.isNotEmpty()) {
                        // Last one
                        if (index == sprites.count() - 1) {
                            boolArray[index] = true
                        } else {
                            sprites.getOrNull(index + 1)?.let { nextSprite ->
                                if (nextSprite.matteKey.isNullOrEmpty()) {
                                    boolArray[index] = true
                                } else {
                                    if (nextSprite.matteKey != svgaDrawerSprite.matteKey) {
                                        boolArray[index] = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            endIndexList = boolArray
        }
        return endIndexList?.get(spriteIndex) ?: false
    }

    private fun playAudio(frameIndex: Int) {
        this.videoItem.audioList.forEach { audio ->
            if (audio.startFrame == frameIndex) {
                this.videoItem.soundPool?.let { soundPool ->
                    audio.soundID?.let { soundID ->
                        audio.playID = soundPool.play(soundID, 1.0f, 1.0f, 1, 0, 1.0f)
                    }
                }
            }
            if (audio.endFrame <= frameIndex) {
                audio.playID?.let {
                    this.videoItem.soundPool?.stop(it)
                }
                audio.playID = null
            }
        }
    }

    private fun shareFrameMatrix(transform: Matrix): Matrix {
        val matrix = this.sharedValues.sharedMatrix()
        matrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy) //这里不能随意调换顺序，先缩放再位移。否则位移会受到缩放的影响
        matrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
        matrix.preConcat(transform)
        return matrix
    }

    private fun drawSprite(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        drawImage(sprite, canvas)
        drawShape(sprite, canvas)
        drawDynamic(sprite, canvas, frameIndex)
    }

    private fun drawImage(sprite: SVGADrawerSprite, canvas: Canvas) {
        val imageKey = sprite.imageKey ?: return
        val isHidden = dynamicItem?.dynamicHidden?.get(imageKey) == true
        if (isHidden) {
            return
        }
        val bitmapKey = if (imageKey.endsWith(".matte")) imageKey.substring(
            0, imageKey.length - 6
        ) else imageKey
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        val ma = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        frameMatrix.getValues(ma)
        val scaleX = ma[0]
        val scaleY = ma[4]
        val rqWidth = sprite.frameEntity.layout.width * scaleX
        val rqHeight = sprite.frameEntity.layout.height * scaleY
        //原图的bitmap
        val placeHolderBitmap = videoItem.imageMap[bitmapKey]
        //替换后的bitmap
        val replaceBitmap = dynamicItem?.requestImage(
            bitmapKey, rqWidth.roundToInt(), rqHeight.roundToInt()
        )
        //需要绘制的bitmap
        val drawingBitmap = (replaceBitmap ?: placeHolderBitmap) ?: return
        val paint = this.sharedValues.sharedPaint()
        paint.isAntiAlias = videoItem.antiAlias
        paint.isFilterBitmap = videoItem.antiAlias
        paint.alpha = (sprite.frameEntity.alpha * 255).roundToInt()
        if (sprite.frameEntity.maskPath != null) {
            val maskPath = sprite.frameEntity.maskPath ?: return
            canvas.save()
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            path.transform(frameMatrix)
            canvas.clipPath(path)
            frameMatrix.preScale(
                (sprite.frameEntity.layout.width / drawingBitmap.width).toFloat(),
                (sprite.frameEntity.layout.height / drawingBitmap.height).toFloat()
            )
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
            canvas.restore()
        } else {
            frameMatrix.preScale(
                (sprite.frameEntity.layout.width / drawingBitmap.width).toFloat(),
                (sprite.frameEntity.layout.height / drawingBitmap.height).toFloat()
            )
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
        }
        //绘制文本，每一帧的缩放不一样，文本大小按原图
        val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        frameMatrix.getValues(matrixArray)
        val x0 = matrixArray[2].roundToInt()
        val y0 = matrixArray[5].roundToInt()
        val scaleX1 = matrixArray[0]
        val scaleY1 = matrixArray[4]
        val x1 = (drawingBitmap.width * scaleX1 + x0).roundToInt()
        val y1 = (drawingBitmap.height * scaleY1 + y0).roundToInt()
        val rect = Rect(x0, y0, x1, y1)
        //点击位置
        dynamicItem?.dynamicIClickArea.let {
            it?.get(imageKey)?.onResponseArea(imageKey, x0, y0, x1, y1)
        }
        drawTextOnBitmap(canvas, drawingBitmap, sprite, frameMatrix, rect)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun drawTextOnBitmap(
        canvas: Canvas,
        drawingBitmap: Bitmap,  //原svga 占位图大小
        sprite: SVGADrawerSprite,
        frameMatrix: Matrix,
        rect: Rect,  //绘制区域
    ) {
        if (dynamicItem?.isTextDirty == true) {
            this.drawTextCache.clear()
            dynamicItem.isTextDirty = false
        }
        val imageKey = sprite.imageKey ?: return
        var textBitmap: Bitmap? = null
        dynamicItem?.dynamicText?.get(imageKey)?.let { drawingText ->
            dynamicItem.dynamicTextPaint[imageKey]?.let { drawingTextPaint ->
                drawTextCache[imageKey]?.let {
                    textBitmap = it
                } ?: kotlin.run {
                    val bitmap = Bitmap.createBitmap(
                        drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888
                    )
                    textBitmap = bitmap
                    val drawRect = Rect(0, 0, drawingBitmap.width, drawingBitmap.height)
                    val textCanvas = Canvas(bitmap)
                    drawingTextPaint.isAntiAlias = true
                    val fontMetrics = drawingTextPaint.fontMetrics
                    val top = fontMetrics.top
                    val bottom = fontMetrics.bottom
                    val baseLineY = drawRect.centerY() - top / 2 - bottom / 2
                    val left = drawRect.centerX().toFloat()
                    textCanvas.drawText(
                        drawingText, left, baseLineY, drawingTextPaint
                    )
                    drawTextCache.put(imageKey, bitmap)
                }
            }
        }

        dynamicItem?.dynamicBoringLayoutText?.get(imageKey)?.let { dl ->
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                dl.paint.isAntiAlias = true
                val bitmap = Bitmap.createBitmap(
                    drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888
                )
                textBitmap = bitmap
                val textCanvas = Canvas(bitmap)
                textCanvas.translate(0f, ((drawingBitmap.height - dl.height) / 2).toFloat())
                dl.draw(textCanvas)
                drawTextCache.put(imageKey, bitmap)
            }
        }

        dynamicItem?.dynamicStaticLayoutText?.get(imageKey)?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                val lineMax = try {
                    val field =
                        StaticLayout::class.java.getDeclaredField("mMaximumVisibleLineCount")
                    field.isAccessible = true
                    field.getInt(it)
                } catch (e: Exception) {
                    Int.MAX_VALUE
                }
                //是否是跑马灯文本，是的话文本 bitmap 宽度为 textWidth，否则为 drawingBitmap 宽度
                val textWidth = it.paint.measureText(it.text, 0, it.text.length).roundToInt()
                val scaleTextY = drawingBitmap.height.toFloat() / it.height
                val fixWidth = (textWidth * scaleTextY).roundToInt()
                val isMarquee =
                    (lineMax == 1 && fixWidth > drawingBitmap.width && it.width != Int.MAX_VALUE)
                drawTextMarqueeCache[imageKey] = isMarquee
                val targetWidth = if (isMarquee) textWidth else drawingBitmap.width
                val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder
                        .obtain(it.text, 0, it.text.length, it.paint, targetWidth)
                        .setAlignment(it.alignment)
                        .setMaxLines(lineMax)
                        .setLineSpacing(it.spacingAdd, it.spacingMultiplier)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()
                } else {
                    StaticLayout(
                        it.text,
                        0,
                        it.text.length,
                        it.paint,
                        targetWidth,
                        it.alignment,
                        it.spacingMultiplier,
                        it.spacingAdd,
                        false
                    )
                }

                drawTextRtlCache[imageKey] = layout.text.indices.any { layout.isRtlCharAt(it) }
                if (isMarquee) {
                    val textScale = dynamicItem.dynamicTextScale[imageKey] ?: 1f
                    val scaleY = (drawingBitmap.height.toFloat() / layout.height) * textScale //内边距
                    val bitmapWidth = (targetWidth * scaleY).roundToInt()
                    val bitmapHeight = drawingBitmap.height
                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888
                    )
                    textBitmap = bitmap
                    val textCanvas = Canvas(bitmap)
                    textCanvas.scale(scaleY, scaleY)
                    layout.draw(textCanvas)
                    drawTextCache[imageKey] = bitmap
                } else {
                    val bitmap = Bitmap.createBitmap(
                        drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888
                    )
                    textBitmap = bitmap
                    val textCanvas = Canvas(bitmap)
                    val textScale = dynamicItem.dynamicTextScale[imageKey] ?: 1f
                    val scale = drawingBitmap.height / layout.height.toFloat() * textScale //内边距
                    if (scale < 5f && layout.lineCount == 1) {
                        val tansX = -(layout.width * scale - drawingBitmap.width) / 2f
                        val tansY = -(layout.height * scale - drawingBitmap.height) / 2f
                        textCanvas.translate(tansX, tansY)
                        textCanvas.scale(scale, scale)
                    } else {
                        textCanvas.translate(0f, (drawingBitmap.height - layout.height) / 2f)
                    }
                    layout.draw(textCanvas)
                    drawTextCache[imageKey] = bitmap
                }
            }
        }
        textBitmap?.let { bitmap ->
            val paint = this.sharedValues.sharedPaint()
            paint.isAntiAlias = videoItem.antiAlias
            paint.alpha = (sprite.frameEntity.alpha * 255).roundToInt()
            if (sprite.frameEntity.maskPath != null) {
                val maskPath = sprite.frameEntity.maskPath ?: return@let
                canvas.save()
                canvas.concat(frameMatrix)
                canvas.clipRect(0, 0, drawingBitmap.width, drawingBitmap.height)
                val bitmapShader =
                    BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                paint.shader = bitmapShader
                val path = this.sharedValues.sharedPath()
                maskPath.buildPath(path)
                canvas.drawPath(path, paint)
                canvas.restore()
            } else {
                val isMarquee = drawTextMarqueeCache[imageKey] ?: false
                if (isMarquee) {
                    drawMarquee(imageKey, rect, canvas, bitmap, frameMatrix, paint)
                } else {
                    paint.isFilterBitmap = videoItem.antiAlias
                    canvas.drawBitmap(bitmap, frameMatrix, paint)
                }
            }
        }
    }

    /**
     * 绘制跑马灯
     */
    private fun drawMarquee(
        imageKey: String,
        rect: Rect,
        canvas: Canvas,
        bitmap: Bitmap,
        matrix: Matrix,
        paint: Paint
    ) {
        val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        matrix.getValues(matrixArray)
        val scaleX = matrixArray[0]
        //val scaleY = matrixArray[4]
        val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            canvas.saveLayer(RectF(rect), paint)
        } else {
            canvas.save()
        }
        val isRtl = drawTextRtlCache[imageKey] ?: false
        val fps = videoItem.FPS
        //每秒偏移dp，计算每帧需要偏移多少dp
        val density = Resources.getSystem().displayMetrics.density
        val speed = maxOf(15f * density / fps, 1f)
        val defOffset = -speed * fps //停顿1秒
        //每帧偏移量
        var offsetX = drawTextOffsetCache[imageKey] ?: defOffset
        offsetX += speed
        //截取文字的起始点，如果是rtl，从右侧开始
        val srcStart = if (isRtl) {
            minOf(bitmap.width.toFloat(), bitmap.width - offsetX).roundToInt()
        } else {
            maxOf(0f, offsetX).roundToInt()
        }
        //需要截取的宽度
        val cutWidth = if (isRtl)
            minOf(rect.width(), (srcStart * scaleX).roundToInt())
        else
            minOf(rect.width(), ((bitmap.width - srcStart) * scaleX).roundToInt())
        val srcWidth = (cutWidth.toFloat() / scaleX).roundToInt()
        val srcEnd = if (isRtl) srcStart - srcWidth else srcStart + srcWidth
        //绘制原始文本 还未显示到结尾就是全部文本
        if (cutWidth > 0) {
            val srcRect = Rect(
                if (isRtl) srcEnd else srcStart,
                0,
                if (isRtl) srcStart else srcEnd,
                bitmap.height
            )
            val destRect = Rect(
                if (isRtl) rect.right - cutWidth else rect.left,
                rect.top,
                if (isRtl) rect.right else rect.left + cutWidth,
                rect.bottom
            )
            canvas.drawBitmap(bitmap, srcRect, destRect, paint)
        }
        val leftSpace = rect.width() - cutWidth //剩余空间
        //绘制首尾相接部分的下一段首部文本
        val spaceWidth =
            minOf(
                (rect.width() / 3f).roundToInt(),
                (marqueeLinearGradientWidth * 2).roundToInt()
            ) // 首尾相接中间空余
        val lastCutWidth = minOf(leftSpace - spaceWidth, rect.width()) //右侧需要绘制的文本宽度
        val lastWidth = (lastCutWidth.toFloat() / scaleX).roundToInt()
        if (leftSpace > spaceWidth) { //如果右边空余超过一半，绘制右边文本
            val srcRect =
                Rect(
                    if (isRtl) bitmap.width - lastWidth else 0,
                    0,
                    if (isRtl) bitmap.width else lastWidth,
                    bitmap.height
                )
            val dstRect =
                Rect(
                    if (isRtl) rect.left else rect.left + rect.width() - lastCutWidth,
                    rect.top,
                    if (isRtl) rect.left + lastCutWidth else rect.right,
                    rect.bottom
                )
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
        if (lastCutWidth >= rect.width()) {
            offsetX = defOffset
        }
        drawTextOffsetCache[imageKey] = offsetX
        //宽度太小，不绘制阴影部分
        val isDrawLinearGradient = rect.width() > marqueeLinearGradientWidth * 2
        val isWait = offsetX < 0
        if (isDrawLinearGradient) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            if (!isWait || isRtl) {
                paint.shader = marqueeLeftLinearGradient
                canvas.translate(rect.left.toFloat(), rect.top.toFloat())
                canvas.drawRect(0f, 0f, marqueeLinearGradientWidth, rect.height().toFloat(), paint)
            }
            if (!isWait || !isRtl) {
                paint.shader = marqueeRightLinearGradient
                canvas.translate(rect.width() - marqueeLinearGradientWidth, 0f)
                canvas.drawRect(0f, 0f, marqueeLinearGradientWidth, rect.height().toFloat(), paint)
            }
            paint.shader = null
        }
        canvas.restoreToCount(layer)
    }


    private fun drawShape(sprite: SVGADrawerSprite, canvas: Canvas) {
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        sprite.frameEntity.shapes.forEach { shape ->
            shape.buildPath()
            shape.shapePath?.let {
                val paint = this.sharedValues.sharedPaint()
                paint.reset()
                paint.isAntiAlias = videoItem.antiAlias
                paint.alpha = (sprite.frameEntity.alpha * 255).roundToInt()
                val path = this.sharedValues.sharedPath()
                path.reset()
                path.addPath(this.pathCache.buildPath(shape))
                val shapeMatrix = this.sharedValues.sharedMatrix2()
                shapeMatrix.reset()
                shape.transform?.let {
                    shapeMatrix.postConcat(it)
                }
                shapeMatrix.postConcat(frameMatrix)
                path.transform(shapeMatrix)
                shape.styles?.fill?.let {
                    if (it != 0x00000000) {
                        paint.style = Paint.Style.FILL
                        paint.color = it
                        val alpha =
                            255.coerceAtMost(0.coerceAtLeast((sprite.frameEntity.alpha * 255).roundToInt()))
                        if (alpha != 255) {
                            paint.alpha = alpha
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
                shape.styles?.strokeWidth?.let { strokeWidth ->
                    if (strokeWidth > 0) {
                        paint.alpha = (sprite.frameEntity.alpha * 255).roundToInt()
                        paint.style = Paint.Style.STROKE
                        shape.styles?.stroke?.let {
                            paint.color = it
                            val alpha = 255.coerceAtMost(
                                0.coerceAtLeast((sprite.frameEntity.alpha * 255).roundToInt())
                            )
                            if (alpha != 255) {
                                paint.alpha = alpha
                            }
                        }
                        val scale = matrixScale(frameMatrix)
                        paint.strokeWidth = strokeWidth * scale
                        shape.styles?.lineCap?.let { lineCap ->
                            when {
                                lineCap.equals("butt", true) -> paint.strokeCap = Paint.Cap.BUTT
                                lineCap.equals("round", true) -> paint.strokeCap = Paint.Cap.ROUND
                                lineCap.equals("square", true) -> paint.strokeCap = Paint.Cap.SQUARE
                            }
                        }
                        shape.styles?.lineJoin?.let { lineJoin ->
                            when {
                                lineJoin.equals("miter", true) -> paint.strokeJoin =
                                    Paint.Join.MITER

                                lineJoin.equals("round", true) -> paint.strokeJoin =
                                    Paint.Join.ROUND

                                lineJoin.equals("bevel", true) -> paint.strokeJoin =
                                    Paint.Join.BEVEL
                            }
                        }
                        shape.styles?.miterLimit?.let { miterLimit ->
                            paint.strokeMiter = miterLimit.toFloat() * scale
                        }
                        shape.styles?.lineDash?.let { lineDash ->
                            if (lineDash.size == 3 && (lineDash[0] > 0 || lineDash[1] > 0)) {
                                paint.pathEffect = DashPathEffect(
                                    floatArrayOf(
                                        (if (lineDash[0] < 1.0f) 1.0f else lineDash[0]) * scale,
                                        (if (lineDash[1] < 0.1f) 0.1f else lineDash[1]) * scale
                                    ), lineDash[2] * scale
                                )
                            }
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
            }

        }
    }

    private val matrixScaleTempValues = FloatArray(16)

    private fun matrixScale(matrix: Matrix): Float {
        matrix.getValues(matrixScaleTempValues)
        if (matrixScaleTempValues[0] == 0f) {
            return 0f
        }
        var A = matrixScaleTempValues[0].toDouble()
        var B = matrixScaleTempValues[3].toDouble()
        var C = matrixScaleTempValues[1].toDouble()
        var D = matrixScaleTempValues[4].toDouble()
        if (A * D == B * C) return 0f
        var scaleX = sqrt(A * A + B * B)
        A /= scaleX
        B /= scaleX
        var skew = A * C + B * D
        C -= A * skew
        D -= B * skew
        val scaleY = sqrt(C * C + D * D)
        C /= scaleY
        D /= scaleY
        skew /= scaleY
        if (A * D < B * C) {
            scaleX = -scaleX
        }
        return if (scaleInfo.ratioX) abs(scaleX.toFloat()) else abs(scaleY.toFloat())
    }

    private fun drawDynamic(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        val imageKey = sprite.imageKey ?: return
        dynamicItem?.dynamicDrawer?.get(imageKey)?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(canvas, frameIndex)
            canvas.restore()
        }
        dynamicItem?.dynamicDrawerSized?.get(imageKey)?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(
                canvas,
                frameIndex,
                sprite.frameEntity.layout.width.roundToInt(),
                sprite.frameEntity.layout.height.roundToInt()
            )
            canvas.restore()
        }
    }

    fun clear() {
        drawTextCache.values.filter {
            !it.isRecycled
        }.forEach {
            it.recycle()
        }
        drawTextCache.clear()
    }

    class ShareValues {

        private val sharedPaint = Paint()
        private val sharedPath = Path()
        private val sharedPath2 = Path()
        private val sharedMatrix = Matrix()
        private val sharedMatrix2 = Matrix()

        private val shareMattePaint = Paint()
        private var shareMatteCanvas: Canvas? = null
        private var sharedMatteBitmap: Bitmap? = null

        fun sharedPaint(): Paint {
            sharedPaint.reset()
            return sharedPaint
        }

        fun sharedPath(): Path {
            sharedPath.reset()
            return sharedPath
        }

        fun sharedPath2(): Path {
            sharedPath2.reset()
            return sharedPath2
        }

        fun sharedMatrix(): Matrix {
            sharedMatrix.reset()
            return sharedMatrix
        }

        fun sharedMatrix2(): Matrix {
            sharedMatrix2.reset()
            return sharedMatrix2
        }

        fun shareMattePaint(): Paint {
            shareMattePaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_IN))
            return shareMattePaint
        }

        fun sharedMatteBitmap(): Bitmap? {
            return sharedMatteBitmap
        }

        fun shareMatteCanvas(width: Int, height: Int): Canvas {
            if (shareMatteCanvas == null) {
                sharedMatteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
//                shareMatteCanvas = Canvas(sharedMatteBitmap)
            }
//            val matteCanvas = shareMatteCanvas as Canvas
//            matteCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//            return matteCanvas
            val bitmap =
                sharedMatteBitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            return Canvas(bitmap)
        }
    }

    class PathCache {

        private var canvasWidth: Int = 0
        private var canvasHeight: Int = 0
        private val cache = HashMap<SVGAVideoShapeEntity, Path>()

        fun onSizeChanged(canvas: Canvas) {
            if (this.canvasWidth != canvas.width || this.canvasHeight != canvas.height) {
                this.cache.clear()
            }
            this.canvasWidth = canvas.width
            this.canvasHeight = canvas.height
        }

        fun buildPath(shape: SVGAVideoShapeEntity): Path {
            if (!this.cache.containsKey(shape)) {
                val path = Path()
                shape.shapePath?.let { path.set(it) }
                this.cache[shape] = path
            }
            return this.cache[shape] ?: Path()
        }

    }

}
