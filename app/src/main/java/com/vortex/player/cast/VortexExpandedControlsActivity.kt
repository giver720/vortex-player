package com.vortex.player.cast

import android.view.Menu
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.vortex.player.R

class VortexExpandedControlsActivity : ExpandedControllerActivity() {
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.cast_expanded_controls, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.cast_route_menu_item)
        return true
    }
}
