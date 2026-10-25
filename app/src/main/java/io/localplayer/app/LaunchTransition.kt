package io.localplayer.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Brand motion on the existing system splash; no second activity or network work. */
internal fun ComponentActivity.configureLaunchTransition(firstCreation:Boolean) {
    if (!firstCreation || intent.action != Intent.ACTION_MAIN ||
        !intent.hasCategory(Intent.CATEGORY_LAUNCHER) || !openingClaimed.compareAndSet(false,true)) return
    splashScreen.setOnExitAnimationListener { screen ->
        splashScreen.clearOnExitAnimationListener()
        val icon=screen.iconView
        if (!ValueAnimator.areAnimatorsEnabled() || icon==null || isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            screen.remove();return@setOnExitAnimationListener
        }
        val routes=SignalArrivalView(this,icon)
        screen.addView(routes,FrameLayout.LayoutParams(-1,-1))
        val finished=AtomicBoolean(false)
        lateinit var animator:ValueAnimator
        val observer=object:DefaultLifecycleObserver{
            override fun onStop(owner:LifecycleOwner){animator.cancel()}
            override fun onDestroy(owner:LifecycleOwner){animator.cancel()}
        }
        fun remove(){
            if(finished.compareAndSet(false,true)){
                lifecycle.removeObserver(observer);screen.remove()
            }
        }
        animator=ValueAnimator.ofFloat(0f,1f).apply{
            duration=880
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener{
                val p=it.animatedValue as Float
                routes.progress=p;routes.invalidate()
                icon.scaleX=.92f+.08f*phase(p,0f,.4f);icon.scaleY=icon.scaleX
                icon.alpha=.65f+.35f*phase(p,0f,.3f)
                screen.alpha=1f-phase(p,.76f,1f)
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:Animator){remove()}
                override fun onAnimationCancel(animation:Animator){remove()}
            })
        }
        screen.setOnClickListener{animator.cancel()}
        screen.contentDescription="映汐放映站，版本 ${BuildConfig.VERSION_NAME}，轻触跳过开屏动画"
        screen.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        icon.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        lifecycle.addObserver(observer);animator.start()
    }
}
private val openingClaimed=AtomicBoolean(false)
private fun phase(p:Float,start:Float,end:Float)=((p-start)/(end-start)).coerceIn(0f,1f)

/** Two drawn paths converge on the real station icon. This is decoration, not connection status. */
private class SignalArrivalView(context:Context,private val icon:View):View(context){
    var progress=0f
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(185,30,75);strokeWidth=1.5f*density;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val version=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(104,111,105);textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)}
    private val title=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(43,48,45);textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
    private val pathA=Path();private val pathB=Path();private val segment=Path()
    private val a=PathMeasure();private val b=PathMeasure()
    private val here=IntArray(2);private val there=IntArray(2)
    private var cx=0f;private var cy=0f;private var half=0f
    private var x1=0f;private var y1=0f;private var x2=0f;private var y2=0f
    private var ready=false
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(!ready){
            if(icon.width==0||icon.height==0)return
            getLocationOnScreen(here);icon.getLocationOnScreen(there)
            cx=there[0]-here[0]+icon.width/2f;cy=there[1]-here[1]+icon.height/2f;half=maxOf(icon.width,icon.height)*.72f/2f+4*density
            val dx=minOf(width*.32f,140*density);val dy=minOf(height*.16f,125*density)
            x1=cx-dx;y1=cy-dy;x2=cx+dx;y2=cy+dy
            pathA.moveTo(x1,y1);pathA.lineTo(cx-half-18*density,y1);pathA.lineTo(cx-half-18*density,cy);pathA.lineTo(cx-half,cy)
            pathB.moveTo(x2,y2);pathB.lineTo(cx+half+18*density,y2);pathB.lineTo(cx+half+18*density,cy);pathB.lineTo(cx+half,cy)
            a.setPath(pathA,false);b.setPath(pathB,false);ready=true
        }
        val fade=1f-phase(progress,.67f,.88f)
        paint.alpha=(fade*210).toInt()
        fun line(measure:PathMeasure,amount:Float){segment.reset();measure.getSegment(0f,measure.length*amount,segment,true);canvas.drawPath(segment,paint)}
        line(a,phase(progress,.02f,.46f));line(b,phase(progress,.12f,.56f))
        canvas.drawCircle(x1,y1,3*density,paint);canvas.drawCircle(x2,y2,3*density,paint)
        val ring=phase(progress,.38f,.7f)
        paint.alpha=((1-ring)*fade*120).toInt();canvas.drawCircle(cx,cy,half+(8+ring*16)*density,paint)
        title.textSize=minOf(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,26f,resources.displayMetrics),(width-48*density)/6f)
        title.alpha=(phase(progress,.2f,.5f)*255).toInt()
        canvas.drawText("映汐放映站",cx,cy+half+48*density,title)
        val label="v${BuildConfig.VERSION_NAME}"
        version.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,13f,resources.displayMetrics)
        if(version.measureText(label)>width-48*density)version.textSize*=(width-48*density)/version.measureText(label)
        version.alpha=title.alpha
        canvas.drawText(label,cx,(cy+half+48*density+title.fontMetrics.descent+10*density-version.fontMetrics.ascent).coerceAtMost(height-24*density),version)
    }
}
