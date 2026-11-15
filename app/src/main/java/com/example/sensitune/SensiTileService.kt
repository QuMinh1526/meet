package com.example.sensitune

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class SensiTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(R.string.tile_subtitle)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            sendBroadcast(
                Intent(this, ReleaseReceiver::class.java)
                    .setAction(Actions.RELEASE)
            )
            LogBuffer.warn("SensiTile", "tile clicked - release broadcast sent")
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                label = getString(R.string.tile_released)
                updateTile()
            }
        } catch (t: Throwable) {
            LogBuffer.error("SensiTile", "tile release failed", t)
        }
    }
}
