package com.parkingroom.app

import android.app.Application
import com.parkingroom.app.game.AppController

class ParkingRoomApp : Application() {
    val controller: AppController by lazy { AppController(this) }
}
