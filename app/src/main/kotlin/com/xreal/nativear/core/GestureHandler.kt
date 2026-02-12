package com.xreal.nativear.core

import com.xreal.nativear.GestureManager
import com.xreal.nativear.UserAction

/**
 * GestureHandler: Extracts gesture handling logic from CoreEngine.
 */
class GestureHandler(
    var onUserAction: (UserAction) -> Unit = {}
) : GestureManager.GestureListener {

    override fun onDoubleTap() = onUserAction(UserAction.CYCLE_CAMERA)
    override fun onTripleTap() = onUserAction(UserAction.OPEN_MEM_QUERY)
    override fun onQuadTap() = onUserAction(UserAction.SYNC_MEMORY)

    override fun onNod() = onUserAction(UserAction.CONFIRM_AI_ACTION)
    override fun onShake() = onUserAction(UserAction.CANCEL_AI_ACTION)
}

