package com.ardas.tabletcontroller

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class ControlSurface(context: Context, private val client: CommandClient) : View(context) {
    private enum class Mode { KEYS, FREE_TOUCH, THEREMIN, FX, KEYBOARD, STENO, SMART_KEYBOARD, SYMBOLS }
    private data class Touch(val x: Float, val y: Float, val pressure: Float, val color: Int)
    private data class Trail(val x: Float, val y: Float, val pressure: Float, val color: Int, val time: Long)
    private data class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Int, val time: Long)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val theremin = ThereminEngine()
    private var mode = Mode.FREE_TOUCH
    private val held = mutableMapOf<Int, String>(); private val heldCounts = mutableMapOf<String, Int>()
    private val lastPos = mutableMapOf<Int, Pair<Float, Float>>(); private val active = mutableMapOf<Int, Touch>()
    private val starts = mutableMapOf<Int, Triple<Float, Float, Long>>(); private val moved = mutableSetOf<Int>()
    private val trails = ArrayDeque<Trail>(); private val sparks = ArrayDeque<Spark>()
    private var scrollAnchor: Float? = null; private var scrollAnchorX: Float? = null; private var maxPointers = 0; private var modePulseUntil = 0L; private var modeLabelUntil = 0L
    private var threeFingerStart: Pair<Float, Float>? = null; private var threeFingerEnd: Pair<Float, Float>? = null
    private var hasDynamicPressure = false
    private val keyboardLeftSlots = mutableSetOf<Int>(); private val keyboardRightSlots = mutableSetOf<Int>(); private var keyboardChordValid = true
    private var keyboardLeftCount = 0; private var keyboardRightCount = 0
    private var symbolQuadrant = -1; private var symbolCount = 0

    init { setBackgroundColor(Color.BLACK) }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); drawEffects(canvas) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    maxPointers = 1
                    keyboardLeftSlots.clear(); keyboardRightSlots.clear(); keyboardLeftCount = 0; keyboardRightCount = 0; keyboardChordValid = true; symbolQuadrant = -1; symbolCount = 0
                }
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.pointerCount == 3) { threeFingerStart = centroid(e); threeFingerEnd = threeFingerStart }
                val i = e.actionIndex; val id = e.getPointerId(i); val touch = newTouch(id, e.getX(i), e.getY(i), e.getPressure(i))
                active[id] = touch; lastPos[id] = touch.x to touch.y; starts[id] = Triple(touch.x, touch.y, SystemClock.uptimeMillis()); addTrail(touch)
                when (mode) { Mode.KEYS -> keyAt(touch.x, touch.y)?.let { press(id, it) }; Mode.KEYBOARD -> if (touch.x < width / 2f) keyboardLeftCount++ else keyboardRightCount++; Mode.STENO -> keyboardSlot(touch.x, touch.y)?.let { if (it.first) keyboardLeftSlots.add(it.second) else keyboardRightSlots.add(it.second) }; Mode.SYMBOLS -> addSymbolTouch(touch.x, touch.y); Mode.FREE_TOUCH -> updateScroll(e); Mode.THEREMIN -> updateTheremin(e, i); Mode.FX, Mode.SMART_KEYBOARD -> Unit }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i); val before = lastPos[id] ?: (x to y)
                    starts[id]?.let { s -> if ((x-s.first)*(x-s.first) + (y-s.second)*(y-s.second) > 100f) { moved.add(id); if (mode == Mode.KEYBOARD || mode == Mode.STENO) keyboardChordValid = false } }
                    if (mode == Mode.FREE_TOUCH && e.pointerCount == 1) {
                        val speed = if (e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) .3f + e.getPressure(i).coerceIn(0f, 1f) * 2.2f else 1f
                        client.send("mouse:move:${((x-before.first)*speed).toInt()}:${((y-before.second)*speed).toInt()}")
                    }
                    val touch = newTouch(id, x, y, e.getPressure(i)); active[id] = touch; lastPos[id] = x to y; addTrail(touch)
                }
                if (e.pointerCount == 3) threeFingerEnd = centroid(e)
                when (mode) { Mode.FREE_TOUCH -> updateScroll(e); Mode.THEREMIN -> updateTheremin(e, 0); Mode.KEYS, Mode.FX, Mode.KEYBOARD, Mode.STENO, Mode.SMART_KEYBOARD, Mode.SYMBOLS -> Unit }
            }
            MotionEvent.ACTION_CANCEL -> clearTouches()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex; val id = e.getPointerId(i); active[id]?.let(::addTrail)
                if (mode == Mode.KEYS) release(id)
                val isFinal = e.actionMasked == MotionEvent.ACTION_UP
                if (mode == Mode.FREE_TOUCH && isFinal && maxPointers == 3) sendThreeFingerSwipe()
                if (mode == Mode.KEYBOARD && isFinal && keyboardChordValid) {
                    (keyboardLetter() ?: keyboardSingleLetter())?.let { client.send("keyboard:letter:$it"); modePulseUntil = SystemClock.uptimeMillis() + 90 }; clearKeyboardChord()
                }
                if (mode == Mode.SYMBOLS && isFinal) { symbolCommand()?.let(client::send); symbolQuadrant = -1; symbolCount = 0 }
                if (mode == Mode.STENO && isFinal && keyboardChordValid) client.send("steno:stroke:${slotMask(keyboardLeftSlots)}:${slotMask(keyboardRightSlots)}")
                if (mode == Mode.SMART_KEYBOARD && isFinal && maxPointers == 1 && !moved.contains(id)) starts[id]?.let { smartKeyAt(it.first, it.second)?.let(client::send) }
                if (mode == Mode.FREE_TOUCH && isFinal && maxPointers == 1 && !moved.contains(id)) starts[id]?.let { if (SystemClock.uptimeMillis()-it.third < 220) { client.send("mouse:left:down"); client.send("mouse:left:up") } }
                active.remove(id); lastPos.remove(id); starts.remove(id); moved.remove(id)
                when (mode) { Mode.FREE_TOUCH -> updateScroll(e); Mode.THEREMIN -> updateRemainingTheremin(e, i); Mode.KEYS, Mode.FX, Mode.KEYBOARD, Mode.STENO, Mode.SMART_KEYBOARD, Mode.SYMBOLS -> Unit }
            }
        }; return true
    }

    private fun newTouch(id: Int, x: Float, y: Float, pressure: Float): Touch { observePressure(pressure); return Touch(x, y, pressure, active[id]?.color ?: visualColor(id)) }
    private fun keyAt(x: Float, y: Float): String? { val u=height*.18f; val h=height.toFloat(); return when { x in u..u*4 && y in (h-u*3)..(h-u*2) -> "key:W"; x in 0f..u*3 && y in (h-u*2)..(h-u) -> "key:A"; x in u*3..u*6 && y in (h-u*2)..(h-u) -> "key:D"; x in u..u*4 && y >= h-u -> "key:S"; x <= u*3 && y in (u*.6f)..(u*1.6f) -> "key:SPACE"; else -> null } }
    /** Five horizontal finger positions on each half form a 5×5 alphabet grid (A–Y). */
    private fun keyboardSlot(x: Float, y: Float): Pair<Boolean, Int>? {
        val half = width.coerceAtLeast(1) / 2f
        val isLeft = x < half
        val localX = if (isLeft) x else x - half
        val slot = (localX / (half / 5f)).toInt().coerceIn(0, 4)
        return isLeft to slot
    }
    private fun keyboardLetter(): Char? {
        if (keyboardLeftCount in 1..5 && keyboardRightCount in 1..5) return ('A'.code + (keyboardLeftCount - 1) * 5 + keyboardRightCount - 1).toChar() // A through Y
        return if (keyboardLeftCount == 0 && keyboardRightCount == 5) 'Z' else null
    }
    private fun keyboardSingleLetter(): Char? = when {
        keyboardLeftCount in 1..5 && keyboardRightCount == 0 -> ('A'.code + (keyboardLeftCount - 1) * 5).toChar()
        keyboardRightCount in 1..5 && keyboardLeftCount == 0 -> ('A'.code + keyboardRightCount - 1).toChar()
        else -> null
    }
    private fun clearKeyboardChord() { keyboardLeftCount = 0; keyboardRightCount = 0 }
    private fun addSymbolTouch(x: Float, y: Float) {
        val quadrant = (if (y < height / 2f) 0 else 2) + (if (x < width / 2f) 0 else 1)
        if (symbolQuadrant == -1 || quadrant != symbolQuadrant) { symbolQuadrant = quadrant; symbolCount = 1 } else symbolCount = (symbolCount + 1).coerceAtMost(5)
    }
    private fun symbolCommand(): String? {
        val map = arrayOf(arrayOf("Ç", "Ğ", "I", "İ", "Ö"), arrayOf("Ş", "Ü", "0", "1", "2"), arrayOf("3", "4", "5", "6", "7"), arrayOf("8", "9", "space", "backspace", "enter"))
        if (symbolQuadrant !in 0..3 || symbolCount !in 1..5) return null
        val value = map[symbolQuadrant][symbolCount - 1]
        return if (value.length == 1) "keyboard:letter:$value" else "keyboard:command:$value"
    }
    private fun slotMask(slots: Set<Int>): Int = slots.fold(0) { result, slot -> result or (1 shl slot) }
    private fun smartKeyAt(x: Float, y: Float): String? {
        val w = width.coerceAtLeast(1).toFloat(); val h = height.coerceAtLeast(1).toFloat()
        if (y > h * .76f) return when { x < w * .18f -> "keyboard:command:backspace"; x > w * .82f -> "keyboard:command:enter"; else -> "keyboard:command:space" }
        val rows = arrayOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç.")
        val row = (y / (h * .76f / 3f)).toInt().coerceIn(0, 2); val chars = rows[row]
        // Nearest-center choice gives generous tolerance around every invisible key.
        val key = chars[((x / w) * (chars.length - 1)).roundToInt().coerceIn(0, chars.length - 1)]
        return "keyboard:guess:$key"
    }
    private fun press(id: Int, command: String) { held[id]=command; val count=heldCounts[command]?:0; heldCounts[command]=count+1; if(count==0) client.send("$command:down") }
    private fun release(id: Int) { val command=held.remove(id)?:return; val remaining=(heldCounts[command]?:1)-1; if(remaining<=0){heldCounts.remove(command);client.send("$command:up")}else heldCounts[command]=remaining }
    private fun releaseAll() { held.keys.toList().forEach(::release) }
    private fun updateScroll(e: MotionEvent) {
        if (e.pointerCount == 2) {
            val averageY=(e.getY(0)+e.getY(1))/2; val averageX=(e.getX(0)+e.getX(1))/2
            // Inverted vertical scrolling, plus horizontal two-finger swipes for zoom.
            scrollAnchor?.let { if(abs(averageY-it)>3)client.send("mouse:scroll:${((averageY-it)*3).toInt()}") }
            scrollAnchorX?.let { if(abs(averageX-it)>28)client.send(if(averageX>it)"mouse:zoom:in" else "mouse:zoom:out") }
            scrollAnchor=averageY;scrollAnchorX=averageX
        } else { scrollAnchor=null;scrollAnchorX=null }
    }
    private fun updateTheremin(e: MotionEvent, i: Int) {
        val x=e.getX(i).coerceIn(0f,width.toFloat()); val y=e.getY(i).coerceIn(0f,height.toFloat()); val xn=x/width.coerceAtLeast(1); val yn=y/height.coerceAtLeast(1)
        val pitch=110.0*2.0.pow(4.0*xn); val fallback=(1f-yn*.8f).coerceIn(.05f,.7f); val pressure=e.getPressure(i).coerceIn(.03f,1f)
        theremin.setTone(pitch,(if(hasDynamicPressure)pressure else fallback).toDouble(),.35+yn*11.5,1.0+(1.0-yn)*3.0)
    }
    private fun updateRemainingTheremin(e: MotionEvent, removed: Int) { val i=(0 until e.pointerCount).firstOrNull{it!=removed};if(i==null)theremin.silence()else updateTheremin(e,i) }
    fun nextMode()=changeMode(1); fun previousMode()=changeMode(-1)
    private fun changeMode(step:Int){releaseAll();theremin.silence();val modes=Mode.values();mode=modes[(mode.ordinal+step+modes.size)%modes.size];modePulseUntil=SystemClock.uptimeMillis()+180;modeLabelUntil=SystemClock.uptimeMillis()+900;postInvalidateOnAnimation()}
    private fun clearTouches(){releaseAll();theremin.silence();active.clear();lastPos.clear();starts.clear();moved.clear();scrollAnchor=null;scrollAnchorX=null;threeFingerStart=null;threeFingerEnd=null;keyboardLeftSlots.clear();keyboardRightSlots.clear();clearKeyboardChord();keyboardChordValid=true;symbolQuadrant=-1;symbolCount=0}
    private fun centroid(e: MotionEvent): Pair<Float, Float> { var x=0f;var y=0f;for(i in 0 until e.pointerCount){x+=e.getX(i);y+=e.getY(i)};return x/e.pointerCount to y/e.pointerCount }
    private fun sendThreeFingerSwipe() {
        val start=threeFingerStart ?: return; val end=threeFingerEnd ?: return; val dx=end.first-start.first;val dy=end.second-start.second
        val direction=when { abs(dx)<100f && abs(dy)<100f -> null; abs(dx)>abs(dy) && dx>0 -> "right"; abs(dx)>abs(dy) -> "left"; dy>0 -> "down"; else -> "up" }
        direction?.let { client.send("gesture:three_swipe:$it") }; threeFingerStart=null;threeFingerEnd=null
    }

    private fun modeColor()=when(mode){Mode.KEYS->Color.rgb(74,222,128);Mode.FREE_TOUCH->Color.rgb(56,189,248);Mode.THEREMIN->Color.rgb(232,121,249);Mode.FX->Color.rgb(250,204,21);Mode.KEYBOARD->Color.rgb(251,146,60);Mode.STENO->Color.rgb(244,63,94);Mode.SMART_KEYBOARD->Color.rgb(129,140,248);Mode.SYMBOLS->Color.rgb(45,212,191)}
    private fun modeName()=when(mode){Mode.KEYS->"TUŞ MODU";Mode.FREE_TOUCH->"SERBEST DOKUNMA";Mode.THEREMIN->"FM THEREMIN";Mode.FX->"FX MODU";Mode.KEYBOARD->"CHORD KLAVYE";Mode.STENO->"TÜRKÇE STENO";Mode.SMART_KEYBOARD->"AKILLI KLAVYE";Mode.SYMBOLS->"TÜRKÇE & SAYILAR"}
    private fun visualColor(id:Int)=if(mode==Mode.FX)Color.HSVToColor(floatArrayOf(((id*53)+(SystemClock.uptimeMillis()%360)).toFloat()%360,.85f,1f))else modeColor()
    private fun observePressure(value:Float){if(abs(value-1f)>.03f)hasDynamicPressure=true}
    private fun addTrail(touch:Touch){
        val now=SystemClock.uptimeMillis();val last=trails.lastOrNull()
        if(last==null||(last.x-touch.x)*(last.x-touch.x)+(last.y-touch.y)*(last.y-touch.y)>64f){
            trails.addLast(Trail(touch.x,touch.y,touch.pressure,touch.color,now));while(trails.size>180)trails.removeFirst()
            if(mode==Mode.FX)repeat(3+(touch.pressure.coerceIn(0f,1f)*4).toInt()){sparks.addLast(Spark(touch.x,touch.y,Random.nextFloat()*360-180,Random.nextFloat()*360-180,touch.color,now))};while(sparks.size>520)sparks.removeFirst()
        };postInvalidateOnAnimation()
    }
    private fun drawEffects(canvas:Canvas){
        val now=SystemClock.uptimeMillis();while(trails.isNotEmpty()&&now-trails.first().time>650)trails.removeFirst();while(sparks.isNotEmpty()&&now-sparks.first().time>900)sparks.removeFirst();paint.style=Paint.Style.FILL
        trails.forEach{p->val life=1f-(now-p.time).toFloat()/650;val r=Color.red(p.color);val g=Color.green(p.color);val b=Color.blue(p.color);paint.color=Color.argb((life*life*65*p.pressure.coerceIn(.2f,1f)).toInt(),r,g,b);canvas.drawCircle(p.x,p.y,18f+(1-life)*56f,paint);paint.color=Color.argb((life*180).toInt(),r,g,b);canvas.drawCircle(p.x,p.y,3f+life*10f,paint)}
        sparks.forEach{s->val age=(now-s.time).toFloat()/900;val r=Color.red(s.color);val g=Color.green(s.color);val b=Color.blue(s.color);paint.color=Color.argb(((1-age)*230).toInt(),r,g,b);canvas.drawCircle(s.x+s.vx*age,s.y+s.vy*age+age*age*180f,2f+(1-age)*5f,paint)}
        active.values.forEach{t->val r=Color.red(t.color);val g=Color.green(t.color);val b=Color.blue(t.color);val radius=62f+t.pressure.coerceIn(0f,1f)*70f;paint.shader=RadialGradient(t.x,t.y,radius,intArrayOf(Color.WHITE,Color.argb(180,r,g,b),Color.argb(0,r,g,b)),floatArrayOf(0f,.14f,1f),Shader.TileMode.CLAMP);canvas.drawCircle(t.x,t.y,radius,paint);paint.shader=null}
        if(now<modePulseUntil){val c=modeColor();paint.color=Color.argb(((modePulseUntil-now)*.45f).toInt(),Color.red(c),Color.green(c),Color.blue(c));canvas.drawCircle(width/2f,height/2f,width.coerceAtLeast(height).toFloat(),paint)}
        if(now<modeLabelUntil){val progress=(modeLabelUntil-now).toFloat()/900f;paint.color=Color.argb((progress.coerceIn(0f,1f)*255).toInt(),255,255,255);paint.textSize=25f;paint.typeface=Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER;canvas.drawText(modeName(),width/2f,52f,paint);paint.textAlign=Paint.Align.LEFT}
        if(trails.isNotEmpty()||sparks.isNotEmpty()||active.isNotEmpty()||now<modePulseUntil||now<modeLabelUntil)postInvalidateOnAnimation()
    }
    override fun onDetachedFromWindow(){clearTouches();theremin.close();super.onDetachedFromWindow()}
}
