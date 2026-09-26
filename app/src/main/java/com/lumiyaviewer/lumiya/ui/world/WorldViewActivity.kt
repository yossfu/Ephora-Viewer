package com.lumiyaviewer.lumiya.ui.world

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityWorldViewBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.slproto.movement.MoveAction
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Real Second Life 3D world view. No offline/test scene controls are exposed. */
class WorldViewActivity : BaseActivity() {

    private lateinit var binding: ActivityWorldViewBinding
    private lateinit var worldView: FilamentWorldView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorldViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        worldView = FilamentWorldView(this)
        binding.viewportCtn.addView(
            worldView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        holdToMove(binding.forwardBtn, MoveAction.FORWARD)
        holdToMove(binding.backwardBtn, MoveAction.BACKWARD)
        holdToMove(binding.strafeLeftBtn, MoveAction.STRAFE_LEFT)
        holdToMove(binding.strafeRightBtn, MoveAction.STRAFE_RIGHT)
        holdToMove(binding.turnLeftBtn, MoveAction.TURN_LEFT)
        holdToMove(binding.turnRightBtn, MoveAction.TURN_RIGHT)
        holdToMove(binding.upBtn, MoveAction.UP)
        holdToMove(binding.downBtn, MoveAction.DOWN)
        holdToMove(binding.runBtn, MoveAction.RUN)

        binding.flyBtn.setOnClickListener {
            val connection = SLClient.connection
            connection.setFly(!connection.isFlying)
        }
        binding.stopBtn.setOnClickListener { SLClient.connection.stopMovement() }
        binding.recenterBtn.setOnClickListener { worldView.recenterCamera() }
        binding.mapBtn.setOnClickListener {
            startActivity(Intent(this, MovementActivity::class.java))
        }

        lifecycleScope.launch {
            while (true) {
                updateHud()
                delay(HUD_INTERVAL_MILLIS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        worldView.setActive(true)
        if (SLClient.connection.state.value == ConnectionState.CONNECTED ||
            SLClient.connection.state.value == ConnectionState.ERROR) {
            com.lumiyaviewer.lumiya.ui.service.ViewerSessionService.start(this)
        }
    }

    override fun onPause() {
        worldView.setActive(false)
        super.onPause()
    }

    private fun updateHud() {
        val session = SLClient.connection.session.value
        val stats = worldView.stats
        val builder = StringBuilder(160)
        builder.append(getString(R.string.world_region_label)).append(": ")
            .append(session.regionName.ifEmpty { "-" })
            .append("   ·   ").append(session.agentName.ifEmpty { "-" }).append('\n')
        builder.append(getString(R.string.world_position_label)).append(": ")
        if (session.positionKnown) {
            builder.append(String.format(Locale.US, "%.0f, %.0f, %.0f m",
                session.position.x, session.position.y, session.position.z))
        } else {
            builder.append("-")
        }
        builder.append("   ·   ").append(getString(R.string.world_heading_label)).append(": ")
            .append(String.format(Locale.US, "%.0f°", session.headingDegrees)).append('\n')
        builder.append(getString(R.string.world_water_label)).append(": ")
            .append(String.format(Locale.US, "%.0f m", session.waterHeight))
            .append("   ·   ").append(getString(R.string.world_terrain_label)).append(": ")
        if (session.terrainReady) {
            builder.append(String.format(Locale.US, "%.0f .. %.0f m", session.terrainMin, session.terrainMax))
        } else {
            builder.append("-")
        }
        builder.append('\n')
        builder.append(getString(R.string.world_objects_label)).append(": ")
            .append(session.objectCount)
            .append(" (").append(session.avatarCount).append(' ')
            .append(getString(R.string.world_avatars_word)).append(")")
            .append("   ·   ").append(getString(R.string.world_drawn_label)).append(": ")
            .append(worldView.drawnObjects).append(" / ")
            .append(worldView.drawnTriangles / 1000).append("k tris")
            .append('\n').append(worldView.sceneText)
        if (SLClient.connection.state.value != ConnectionState.CONNECTED) {
            builder.append('\n').append(getString(R.string.world_signal_lost))
        }
        binding.infoEl.text = builder.toString()
        binding.fpsEl.text = getString(R.string.world_fps, stats.framesPerSecond)
    }

    private fun holdToMove(view: View, action: MoveAction) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedView.isPressed = true
                    SLClient.connection.setMoveAction(action, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressedView.isPressed = false
                    SLClient.connection.setMoveAction(action, false)
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val HUD_INTERVAL_MILLIS = 400L
    }
}
