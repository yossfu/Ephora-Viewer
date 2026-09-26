package com.lumiyaviewer.lumiya.slproto.grids

import java.util.concurrent.CopyOnWriteArrayList

/** Available login targets: deliberately limited to the production Second Life grid. */
object GridManager {

    private val grids = CopyOnWriteArrayList<Grid>()

    @Synchronized
    fun bootstrap() {
        if (grids.isNotEmpty()) return
        grids.add(
            Grid(
                id = "agni",
                name = "Second Life (Agni)",
                loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
                isDefault = true,
                openSim = false
            )
        )
    }

    fun all(): List<Grid> {
        bootstrap()
        return grids.toList()
    }

    fun defaultGrid(): Grid {
        bootstrap()
        return grids.first()
    }
}
