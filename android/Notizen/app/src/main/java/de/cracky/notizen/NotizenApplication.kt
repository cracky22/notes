package de.cracky.notizen

import android.app.Application
import de.cracky.notizen.data.NotesRepository

class NotizenApplication : Application() {
    lateinit var repository: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = NotesRepository(this)
    }
}
