package com.seslikitap

import android.app.Application
import android.content.Context
import com.seslikitap.data.AppDatabase
import com.seslikitap.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SesliKitapApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Basit bağımlılık kabı: uygulama boyunca tek örnek. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.build(context)
    val library = LibraryRepository(context, db, appScope)
    val prefs = Prefs(context)
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    var librarySort: String
        get() = sp.getString("library_sort", "TITLE") ?: "TITLE"
        set(v) = sp.edit().putString("library_sort", v).apply()
}

val Context.container: AppContainer get() = (applicationContext as SesliKitapApp).container
