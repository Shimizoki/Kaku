package ca.fuwafuwa.kaku;

import android.view.MotionEvent;

/**
 * Created by Xyresic on 4/12/2016.
 */
interface CaptureWindowCallback {
    boolean onMoveEvent(MotionEvent e);
    boolean onResizeEvent(MotionEvent e);
}