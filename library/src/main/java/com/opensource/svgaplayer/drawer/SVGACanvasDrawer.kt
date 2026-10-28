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
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.util.Size
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoShapeEntity
import com.opensource.svgaplayer.entities.SVGAVideoSpriteFrameEntity
import com.opensource.svgaplayer.utils.maxOf
import com.opensource.svgaplayer.utils.roundToIntSafe
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal class SVGACanvasDrawer(
    videoItem: SVGAVideoEntity,
    var dynamicItem: SVGADynamicEntity?
) : SGVADrawer(videoItem) {

    private val sharedValues = ShareValues()
    private val drawTextCache: HashMap<String, Bitmap> = hashMapOf()
    private val drawTextOffsetCache: HashMap<String, Float> = hashMapOf()
    private val drawTextRtlCache: HashMap<String, Boolean> = hashMapOf()
    private val drawTextMarqueeCache: HashMap<String, Boolean> = hashMapOf()
    private val dynamicImageSizeCache: HashMap<String, Size> = hashMapOf()

    /**
     * 文本替换用的放大占位图（ALPHA_8）。只存在 drawer 本地，不写回共享实体的 imageMap：
     * 缓存中的实体被多个 view 复用，运行期改写共享 map 既造成字节记账漂移，
     * 也让同一实体在不同 view 上互相污染占位尺寸（非主线程读 HashMap 的并发风险）
     */
    private val textPlaceHolderCache: HashMap<String, Bitmap> = hashMapOf()
    private val pathCache = PathCache()

    /**
     * 记录每个 key 的文本位图是由哪份数据渲染的（文本内容/Paint/Layout 实例）。
     * isTextDirty 只表示"有文本更新过"，不再据此整体清空 drawTextCache：
     * 源数据未变化的 key 直接复用已渲染位图，只有真正变化的 key 才重建，
     * 避免频繁更新单个文本（如礼物 combo 数字）时整屏重建 ARGB_8888 位图 + measureText/StaticLayout，
     * 高频分配触发并发 GC 卡住主线程（线上 ANR 主栈即卡在此路径）
     */
    private val drawTextSourceCache: HashMap<String, TextSource> = hashMapOf()

    //逐帧复用的临时对象，避免每帧反复分配加剧 GC 压力
    private val matrixValues = FloatArray(9)
    private val drawAreaRect = Rect()
    private val marqueeLayerBounds = RectF()
    private val marqueeSrcRect = Rect()
    private val marqueeDstRect = Rect()
    private val dstInXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    /**
     * 遮罩层精灵缓存，跨帧复用，每帧 drawFrame 开头 clear
     */
    private val matteSprites = HashMap<String, SVGADrawerSprite>()

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

    private var leftVolume = 1f
    private var rightVolume = 1f

    fun setVolume(leftVolume: Float, rightVolume: Float) {
        this.leftVolume = leftVolume
        this.rightVolume = rightVolume
    }

    override fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        super.drawFrame(canvas, frameIndex, scaleType)
        playAudio(frameIndex)
        this.pathCache.onSizeChanged(canvas)
        val sprites = requestFrameSprites(frameIndex)
        // Filter null sprites
        if (sprites.isEmpty()) return
        matteSprites.clear()
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
                        val playId = audio.playID
                        if (playId != null && playId != 0) {
                            soundPool.stop(playId)
                            audio.playID = 0
                        }
                        audio.playID = soundPool.play(soundID, leftVolume, rightVolume, 1, 0, 1.0f)
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

    private fun shareFrameMatrix(transform: Matrix?): Matrix {
        val matrix = this.sharedValues.sharedMatrix()
        matrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy) //这里不能随意调换顺序，先缩放再位移。否则位移会受到缩放的影响
        matrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
        transform?.let { matrix.preConcat(transform) }
        return matrix
    }

    private fun drawSprite(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        drawImage(sprite, canvas)
        drawShape(sprite, canvas)
        drawDynamic(sprite, canvas, frameIndex)
    }

    /**
     * 是否是需要替换的动态图key
     */
    private fun isReplaceImage(imageKey: String): Boolean {
        return dynamicItem?.isDynamicImage(imageKey) == true
    }

    /**
     * 是否是需要替换的动态文本key
     */
    private fun isReplaceText(imageKey: String): Boolean {
        return dynamicItem?.isDynamicText(imageKey) == true
    }

    private fun Size.area() = width * height

    /**
     * 获取精灵在所有帧里面的最大尺寸
     * 直接基于 videoItem.spriteList 计算，不走 requestFrameSprites：
     * 后者会从对象池取走 sprite 且这里不归还，并伴随每帧一个 ArrayList 的分配，
     * 首帧替换文本/图片时会瞬时产生 帧数×精灵数 量级的临时对象，加剧主线程 GC 压力。
     * 结果按 key 缓存，仅在首次调用时执行一次全量扫描
     */
    private fun getSpriteMaxSize(key: String): Size {
        val imageSizeCache = dynamicImageSizeCache
        imageSizeCache[key]?.let { return it }
        var tempSize = Size(0, 0)
        val frameCount = videoItem.frames
        val spriteList = videoItem.spriteList
        val tempMatrix = Matrix()
        val matrixArray = FloatArray(9)
        for (frameIndex in 0 until frameCount) {
            var matchedFrame: SVGAVideoSpriteFrameEntity? = null
            for (sprite in spriteList) {
                if (sprite.imageKey != key) continue
                val spriteFrames = sprite.frames ?: continue
                if (frameIndex >= spriteFrames.size) continue
                val frameEntity = spriteFrames[frameIndex]
                //与 requestFrameSprites 的可见性过滤保持一致（非遮罩精灵 alpha<=0 不参与）
                if (frameEntity.alpha <= 0.0) continue
                matchedFrame = frameEntity
                break
            }
            val frameEntity = matchedFrame ?: continue
            tempMatrix.reset()
            tempMatrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy)
            tempMatrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
            frameEntity.transform?.let { tempMatrix.preConcat(it) }
            tempMatrix.getValues(matrixArray)
            val rqWidth = frameEntity.layout.width * matrixArray[0]
            val rqHeight = frameEntity.layout.height * matrixArray[4]
            val size = Size(rqWidth.roundToIntSafe(), rqHeight.roundToIntSafe())
            if (size.area() > tempSize.area()) {
                tempSize = size
            }
        }
        imageSizeCache[key] = tempSize
        return tempSize
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
        //原图的bitmap
        var placeHolderBitmap = videoItem.imageMap[bitmapKey]
        //需要绘制的bitmap
        val drawingBitmap = if (isReplaceImage(imageKey)) {
            val size = getSpriteMaxSize(bitmapKey) //需要修改为最大尺寸
            //替换后的bitmap
            val replaceBitmap = dynamicItem?.requestImage(
                bitmapKey, size.width, size.height
            )
            (replaceBitmap ?: placeHolderBitmap)
        } else if (isReplaceText(imageKey)) {
            val size = getSpriteMaxSize(bitmapKey) //需要修改为最大尺寸
            //替换最大尺寸占位图（本地缓存，不污染共享实体）
            if (placeHolderBitmap != null) {
                val placeHolderArea = placeHolderBitmap.width * placeHolderBitmap.height
                if (size.area() > placeHolderArea) {
                    val cachedPlaceHolder = textPlaceHolderCache[bitmapKey]
                    placeHolderBitmap = if (cachedPlaceHolder != null &&
                        cachedPlaceHolder.width * cachedPlaceHolder.height >= size.area()
                    ) {
                        cachedPlaceHolder
                    } else {
                        createBitmap(size.width, size.height, Bitmap.Config.ALPHA_8)
                            ?.also { textPlaceHolderCache[bitmapKey] = it }
                    }
                }
            }
            placeHolderBitmap
        } else {
            placeHolderBitmap
        }
        if (drawingBitmap == null) return
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        val paint = this.sharedValues.sharedPaint()
        paint.isAntiAlias = videoItem.antiAlias
        paint.isFilterBitmap = videoItem.antiAlias
        paint.alpha = (sprite.frameEntity.alpha * 255).roundToIntSafe()
        if (sprite.frameEntity.maskPath != null) {
            val maskPath = sprite.frameEntity.maskPath ?: return
            canvas.save()
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            path.transform(frameMatrix)
            canvas.clipPath(path)
            frameMatrix.preScale(
                (sprite.frameEntity.layout.width / drawingBitmap.width.maxOf(1)).toFloat(),
                (sprite.frameEntity.layout.height / drawingBitmap.height.maxOf(1)).toFloat()
            )
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
            canvas.restore()
        } else {
            frameMatrix.preScale(
                (sprite.frameEntity.layout.width / drawingBitmap.width.maxOf(1)).toFloat(),
                (sprite.frameEntity.layout.height / drawingBitmap.height.maxOf(1)).toFloat()
            )
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
        }
        //绘制文本，每一帧的缩放不一样，文本大小按原图
        val matrixArray = this.matrixValues
        frameMatrix.getValues(matrixArray)
        val x0 = matrixArray[2].roundToIntSafe()
        val y0 = matrixArray[5].roundToIntSafe()
        val scaleX1 = matrixArray[0]
        val scaleY1 = matrixArray[4]
        val x1 = (drawingBitmap.width * scaleX1 + x0).roundToIntSafe()
        val y1 = (drawingBitmap.height * scaleY1 + y0).roundToIntSafe()
        //复用字段 Rect：onResponseArea 内部按值拷贝，不持有引用
        val rect = drawAreaRect
        rect.set(x0, y0, x1, y1)
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
        val svgaDynamicEntity = dynamicItem
        if (svgaDynamicEntity?.isTextDirty == true) {
            svgaDynamicEntity.isTextDirty = false
            //文本数据有更新：仅剔除已无数据来源的 key（如 clearDynamicObjects 后的残留），
            //其余 key 在下方按源数据比对，只有真正变化的才重建位图
            if (drawTextCache.isNotEmpty()) {
                val cacheIterator = drawTextCache.entries.iterator()
                while (cacheIterator.hasNext()) {
                    if (!svgaDynamicEntity.isDynamicText(cacheIterator.next().key)) {
                        cacheIterator.remove()
                    }
                }
                val sourceIterator = drawTextSourceCache.entries.iterator()
                while (sourceIterator.hasNext()) {
                    if (!svgaDynamicEntity.isDynamicText(sourceIterator.next().key)) {
                        sourceIterator.remove()
                    }
                }
            }
        }
        val imageKey = sprite.imageKey ?: return
        var textBitmap: Bitmap? = null
        svgaDynamicEntity?.dynamicText?.get(imageKey)?.let { drawingText ->
            svgaDynamicEntity.dynamicTextPaint[imageKey]?.let { drawingTextPaint ->
                val source = TextSource(
                    text = drawingText,
                    paint = drawingTextPaint,
                    textSize = drawingTextPaint.textSize,
                    color = drawingTextPaint.color,
                    typeface = drawingTextPaint.typeface
                )
                if (textBitmap == null) {
                    textBitmap = cachedTextBitmap(imageKey, source)
                }
                if (textBitmap == null) {
                    val bitmap = reuseOrCreateTextBitmap(
                        imageKey, drawingBitmap.width, drawingBitmap.height
                    )
                    if (bitmap != null) {
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
                        drawTextSourceCache.put(imageKey, source)
                    }
                }
            }
        }

        svgaDynamicEntity?.dynamicBoringLayoutText?.get(imageKey)?.let { dl ->
            val source = TextSource(layout = dl)
            if (textBitmap == null) {
                textBitmap = cachedTextBitmap(imageKey, source)
            }
            if (textBitmap == null) {
                dl.paint.isAntiAlias = true
                val bitmap = reuseOrCreateTextBitmap(
                    imageKey, drawingBitmap.width, drawingBitmap.height
                )
                if (bitmap != null) {
                    textBitmap = bitmap
                    val textCanvas = Canvas(bitmap)
                    textCanvas.translate(0f, ((drawingBitmap.height - dl.height) / 2).toFloat())
                    dl.draw(textCanvas)
                    drawTextCache.put(imageKey, bitmap)
                    drawTextSourceCache.put(imageKey, source)
                }
            }
        }

        svgaDynamicEntity?.dynamicStaticLayoutText?.get(imageKey)?.let {
            val source = TextSource(layout = it)
            if (textBitmap == null) {
                textBitmap = cachedTextBitmap(imageKey, source)
            }
            if (textBitmap == null) {
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
                val scaleTextY = drawingBitmap.height.toFloat() / it.height.maxOf(1)
                val backTextSize = it.paint.textSize
                val textSize = backTextSize * scaleTextY
                it.paint.textSize = textSize
                var textWidth = it.paint.measureText(it.text, 0, it.text.length).roundToIntSafe()
                val isMarquee =
                    (lineMax == 1 && textWidth > drawingBitmap.width && it.width != Int.MAX_VALUE)
                if (!isMarquee) {
                    it.paint.textSize = backTextSize
                    textWidth = it.paint.measureText(it.text, 0, it.text.length).roundToIntSafe()
                }
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
                    val textScale = svgaDynamicEntity.dynamicTextScale[imageKey] ?: 1f
                    val scaleY =
                        (drawingBitmap.height.toFloat() / layout.height.maxOf(1)) * textScale //内边距
                    val bitmapWidth = (targetWidth * scaleY).roundToIntSafe()
                    val bitmapHeight = drawingBitmap.height
                    val bitmap = reuseOrCreateTextBitmap(imageKey, bitmapWidth, bitmapHeight)
                    if (bitmap != null) {
                        textBitmap = bitmap
                        val textCanvas = Canvas(bitmap)
                        textCanvas.scale(scaleY, scaleY)
                        layout.draw(textCanvas)
                        drawTextCache[imageKey] = bitmap
                        drawTextSourceCache[imageKey] = source
                    }
                } else {
                    val bitmap = reuseOrCreateTextBitmap(
                        imageKey, drawingBitmap.width, drawingBitmap.height
                    )
                    if (bitmap != null) {
                        textBitmap = bitmap
                        val textCanvas = Canvas(bitmap)
                        val textScale = svgaDynamicEntity.dynamicTextScale[imageKey] ?: 1f
                        val scale =
                            drawingBitmap.height * 1f / layout.height.maxOf(1) * textScale //内边距
                        if (layout.lineCount == 1
                            && textWidth * scale <= drawingBitmap.width
                            && layout.height * scale <= drawingBitmap.height
                        ) {
                            //单行文本填充，不受字体大小控制
                            val tansX = -(layout.width * scale - drawingBitmap.width) / 2f
                            val tansY = -(layout.height * scale - drawingBitmap.height) / 2f
                            textCanvas.translate(tansX, tansY)
                            textCanvas.scale(scale, scale)
                        } else {
                            textCanvas.translate(0f, (drawingBitmap.height - layout.height) / 2f)
                        }
                        layout.draw(textCanvas)
                        drawTextCache[imageKey] = bitmap
                        drawTextSourceCache[imageKey] = source
                    }
                }
            }
        }
        textBitmap?.let { bitmap ->
            val paint = this.sharedValues.sharedPaint()
            paint.isAntiAlias = videoItem.antiAlias
            paint.alpha = (sprite.frameEntity.alpha * 255).roundToIntSafe()
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

    private fun createBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        if (width <= 0 || height <= 0) {
            return null
        }
        return runCatching {
            Bitmap.createBitmap(
                width, height, config
            )
        }.getOrNull()
    }

    /**
     * 缓存命中且渲染源数据未变化时，直接复用已渲染的文本位图
     */
    private fun cachedTextBitmap(imageKey: String, source: TextSource): Bitmap? {
        if (drawTextSourceCache[imageKey] != source) {
            return null
        }
        val cached = drawTextCache[imageKey] ?: return null
        if (cached.isRecycled) return null
        return cached
    }

    /**
     * 重建文本位图时优先复用旧位图内存（擦除重绘），
     * 尺寸一致时不再新分配 ARGB_8888，消除文本高频更新场景下的位图分配/GC 抖动。
     * 注：绘制与擦除都发生在主线程同一帧的记录阶段，不存在与 RenderThread 的并发冲突
     */
    private fun reuseOrCreateTextBitmap(imageKey: String, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) {
            return null
        }
        val cached = drawTextCache[imageKey]
        if (cached != null && !cached.isRecycled
            && cached.width == width && cached.height == height
        ) {
            cached.eraseColor(Color.TRANSPARENT)
            return cached
        }
        return createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        val matrixArray = this.matrixValues
        matrix.getValues(matrixArray)
        val scaleX = matrixArray[0]
        //val scaleY = matrixArray[4]
        marqueeLayerBounds.set(rect)
        val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            canvas.saveLayer(marqueeLayerBounds, paint)
        } else {
            canvas.save()
        }
        val isRtl = drawTextRtlCache[imageKey] ?: false
        val fps = videoItem.FPS
        //每秒偏移dp，计算每帧需要偏移多少dp
        val density = Resources.getSystem().displayMetrics.density
        val speed = maxOf(15f * density / fps.maxOf(1), 1f)
        val defOffset = -speed * fps //停顿1秒
        //每帧偏移量
        var offsetX = drawTextOffsetCache[imageKey] ?: defOffset
        offsetX += speed
        //截取文字的起始点，如果是rtl，从右侧开始
        val srcStart = if (isRtl) {
            minOf(bitmap.width.toFloat(), bitmap.width - offsetX).roundToIntSafe()
        } else {
            maxOf(0f, offsetX).roundToIntSafe()
        }
        //需要截取的宽度
        val cutWidth = if (isRtl)
            minOf(rect.width(), (srcStart * scaleX).roundToIntSafe())
        else
            minOf(rect.width(), ((bitmap.width - srcStart) * scaleX).roundToIntSafe())
        val srcWidth = (cutWidth.toFloat() / scaleX).roundToIntSafe()
        val srcEnd = if (isRtl) srcStart - srcWidth else srcStart + srcWidth
        //绘制原始文本 还未显示到结尾就是全部文本
        if (cutWidth > 0) {
            val srcRect = marqueeSrcRect
            srcRect.set(
                if (isRtl) srcEnd else srcStart,
                0,
                if (isRtl) srcStart else srcEnd,
                bitmap.height
            )
            val destRect = marqueeDstRect
            destRect.set(
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
                (rect.width() / 3f).roundToIntSafe(),
                (marqueeLinearGradientWidth * 2).roundToIntSafe()
            ) // 首尾相接中间空余
        val lastCutWidth = minOf(leftSpace - spaceWidth, rect.width()) //右侧需要绘制的文本宽度
        val lastWidth = (lastCutWidth.toFloat() / scaleX).roundToIntSafe()
        if (leftSpace > spaceWidth) { //如果右边空余超过一半，绘制右边文本
            val srcRect = marqueeSrcRect
            srcRect.set(
                if (isRtl) bitmap.width - lastWidth else 0,
                0,
                if (isRtl) bitmap.width else lastWidth,
                bitmap.height
            )
            val dstRect = marqueeDstRect
            dstRect.set(
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
            paint.xfermode = dstInXfermode
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
        sprite.frameEntity.shapes?.forEach { shape ->
            shape.buildPath()
            shape.shapePath?.let {
                val paint = this.sharedValues.sharedPaint()
                paint.reset()
                paint.isAntiAlias = videoItem.antiAlias
                paint.alpha = (sprite.frameEntity.alpha * 255).roundToIntSafe()
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
                            255.coerceAtMost(0.coerceAtLeast((sprite.frameEntity.alpha * 255).roundToIntSafe()))
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
                        paint.alpha = (sprite.frameEntity.alpha * 255).roundToIntSafe()
                        paint.style = Paint.Style.STROKE
                        shape.styles?.stroke?.let {
                            paint.color = it
                            val alpha = 255.coerceAtMost(
                                0.coerceAtLeast((sprite.frameEntity.alpha * 255).roundToIntSafe())
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
                sprite.frameEntity.layout.width.roundToIntSafe(),
                sprite.frameEntity.layout.height.roundToIntSafe()
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
        textPlaceHolderCache.values.filter {
            !it.isRecycled
        }.forEach {
            it.recycle()
        }
        textPlaceHolderCache.clear()
        drawTextSourceCache.clear()
    }

    class ShareValues {

        private val sharedPaint = Paint()
        private val sharedPath = Path()
        private val sharedPath2 = Path()
        private val sharedMatrix = Matrix()
        private val sharedMatrix2 = Matrix()

        private val shareMattePaint = Paint()
        private val matteDstInXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
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
            shareMattePaint.xfermode = matteDstInXfermode
            return shareMattePaint
        }

        fun sharedMatteBitmap(): Bitmap? {
            return sharedMatteBitmap
        }

        fun shareMatteCanvas(width: Int, height: Int): Canvas {
            if (shareMatteCanvas == null && width > 0 && height > 0) {
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

    /**
     * 文本位图的渲染来源快照。Paint/Layout 未重写 equals，按实例引用比较：
     * 只要文本内容、Paint 实例及其关键属性、Layout 实例没变，已渲染位图即可复用
     */
    private data class TextSource(
        val text: String? = null,
        val paint: Paint? = null,
        val textSize: Float = 0f,
        val color: Int = 0,
        val typeface: Typeface? = null,
        val layout: Layout? = null
    )

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
