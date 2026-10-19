package io.localplayer.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity

/** The system splash hands off to content, without a second screen or network wait. */
internal fun ComponentActivity.configureLaunchTransition() {
    splashScreen.setOnExitAnimationListener { screen ->
        if (!ValueAnimator.areAnimatorsEnabled()) screen.remove()
        else {
            screen.animate().alpha(0f).setDuration(220)
                .setInterpolator(PathInterpolator(.2f,0f,0f,1f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { screen.remove() }
                    override fun onAnimationCancel(animation: Animator) { screen.remove() }
                }).start()
            screen.iconView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(220)?.start()
        }
    }
}
