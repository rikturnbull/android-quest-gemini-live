package uk.co.controlz.live.views

import android.view.Surface

interface ISurfaceProvider {
  val surface: Surface?
  val surfaceAvailable: Boolean
    get() = surface != null
}